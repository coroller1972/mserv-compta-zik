package fr.spiritbox.compta.services

import com.fasterxml.jackson.databind.cfg.JsonNodeFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import fr.spiritbox.compta.backup.BackupArchiveStore
import fr.spiritbox.compta.openapi.model.BackupAnalysis
import fr.spiritbox.compta.openapi.model.BackupInfo
import fr.spiritbox.compta.openapi.model.BackupRestoreResult
import fr.spiritbox.compta.utils.PanacheCoroutine
import io.quarkus.hibernate.reactive.panache.kotlin.Panache
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.BadRequestException
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@ApplicationScoped
class BackupService(
    private val transactions: PanacheCoroutine,
    private val mapper: ObjectMapper,
    private val store: BackupArchiveStore,
    private val audit: AuditService,
) {
    suspend fun create(): BackupInfo = transactions.withExclusiveTransaction { createWithinTransaction("backup") }
    suspend fun list() = store.list()
    suspend fun download(name: String) = store.download(name)

    /** Caller must hold the exclusive application transaction lock until replacement commits. */
    suspend fun createWithinTransaction(reason: String): BackupInfo {
        val manifest = mapper.createObjectNode().put("formatVersion", 1)
            .put("createdAt", OffsetDateTime.now(ZoneOffset.UTC).toString())
        manifest.set<JsonNode>("schema", schema())
        val tables = manifest.putObject("tables")
        for (table in TABLES) {
            val rows = tables.putArray(table)
            var lastId: String? = null
            while (true) {
                val predicate = if (lastId == null) "" else "where id > cast(:lastId as uuid)"
                val query = session().createNativeQuery(
                    "select cast(to_jsonb(t) as text) from $table t $predicate order by id limit 500",
                    String::class.java,
                )
                if (lastId != null) query.setParameter("lastId", lastId)
                val page = query.resultList.awaitSuspending()
                if (page.isEmpty()) break
                page.forEach { rows.add(parse(it)) }
                lastId = rows.last().path("id").asText()
            }
        }
        validate(manifest)
        return store.write(manifest, reason)
    }

    suspend fun analyze(bytes: ByteArray): BackupAnalysis {
        val archive = store.read(bytes)
        return transactions.withSession {
            validate(archive.manifest)
            BackupAnalysis(
                valid = true,
                documents = archive.manifest.path("tables").path("generated_document").size(),
                pdfs = archive.files.size,
                confirmationValue = CONFIRMATION,
            )
        }
    }

    suspend fun restore(bytes: ByteArray, confirmation: String?): BackupRestoreResult {
        if (confirmation != CONFIRMATION) throw BadRequestException("La restauration exige la confirmation $CONFIRMATION.")
        val archive = store.read(bytes)
        return transactions.withExclusiveTransaction {
            validate(archive.manifest)
            val before = createWithinTransaction("before-restore")
            store.stage(archive)
            // SQL inserts retain identifiers, versions, timestamps and immutable billing snapshots.
            // File bytes are retained, with paths relocated to an isolated immutable directory.
            try {
                for (table in TABLES.filter { it != "audit_event" }.asReversed()) {
                    session().createNativeQuery<Int>("delete from $table").executeUpdate().awaitSuspending()
                }
                val tables = archive.manifest.path("tables")
                for (table in TABLES) {
                    // A single statement also restores self-referencing credit notes atomically.
                    val conflict = if (table == "audit_event") " on conflict (id) do nothing" else ""
                    session().createNativeQuery<Int>(
                        "insert into $table select * from jsonb_populate_recordset(null::$table, cast(cast(:rows as text) as jsonb))$conflict",
                    ).setParameter("rows", tables.path(table).toString()).executeUpdate().awaitSuspending()
                }
                audit.record("DATA", null, "RESTORED", mapOf("backupFile" to before.fileName, "documents" to tables.path("generated_document").size()))
                session().flush().awaitSuspending()
                BackupRestoreResult(
                    restoredAt = OffsetDateTime.now(ZoneOffset.UTC),
                    documents = tables.path("generated_document").size(),
                    backupFile = before.fileName,
                )
            } catch (e: BadRequestException) {
                throw e
            } catch (e: Exception) {
                throw BadRequestException("Restauration refusée : données incompatibles avec les contraintes de la base. Aucune donnée n’a été remplacée.")
            }
        }
    }

    private suspend fun validate(manifest: ObjectNode) {
        val currentSchema = schema()
        if (manifest.path("schema") != currentSchema) throw BadRequestException("Archive incompatible avec la version du schéma de cette application.")
        val tables = manifest.path("tables")
        if (tables.fieldNames().asSequence().toSet() != TABLES.toSet()) throw BadRequestException("Archive invalide : tables manquantes ou inconnues.")
        val ids = mutableMapOf<String, Set<String>>()
        for (table in TABLES) {
            val rows = tables.path(table)
            if (!rows.isArray) throw BadRequestException("Archive invalide : contenu de table incorrect.")
            val columns = currentSchema.path("columns").path(table).map { it.asText() }.toSet()
            val seen = mutableSetOf<String>()
            rows.forEach { row ->
                val id = row.path("id").asText()
                if (!row.isObject || row.fieldNames().asSequence().toSet() != columns ||
                    runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false).not() || !seen.add(id)) {
                    throw BadRequestException("Archive invalide : colonnes ou identifiants incorrects dans $table.")
                }
            }
            ids[table] = seen
        }
        fun reference(table: String, field: String, target: String, optional: Boolean = false) {
            tables.path(table).forEach { row ->
                val value = row.path(field)
                if (!(optional && value.isNull) && value.asText() !in ids.getValue(target)) {
                    throw BadRequestException("Archive invalide : référence $table.$field absente.")
                }
            }
        }
        reference("term", "settings_id", "accounting_year_settings")
        reference("band", "settings_id", "accounting_year_settings")
        reference("band", "teacher_id", "teacher", true)
        reference("band_member", "band_id", "band")
        reference("band_member", "musician_id", "musician")
        reference("individual_course", "settings_id", "accounting_year_settings")
        reference("individual_course", "teacher_id", "teacher")
        reference("individual_course", "musician_id", "musician")
        reference("attendance", "term_id", "term")
        tables.path("attendance").forEach { row ->
            val target = when (row.path("entity_type").asText()) {
                "INDIVIDUAL_COURSE" -> "individual_course"
                "WORKSHOP" -> "band"
                else -> throw BadRequestException("Archive invalide : type de présence inconnu.")
            }
            if (row.path("entity_id").asText() !in ids.getValue(target)) throw BadRequestException("Archive invalide : présence sans cours ou groupe.")
        }
        reference("generated_document", "term_id", "term")
        reference("generated_document", "musician_id", "musician", true)
        reference("generated_document", "teacher_id", "teacher", true)
        reference("generated_document", "parent_document_id", "generated_document", true)
        reference("teacher_invoice_attendance_allocation", "document_id", "generated_document")
        reference("teacher_invoice_attendance_allocation", "attendance_id", "attendance")
        reference("teacher_billing_adjustment", "teacher_id", "teacher")
        reference("teacher_billing_adjustment", "source_document_id", "generated_document")
        reference("teacher_billing_adjustment", "applied_document_id", "generated_document", true)
        // Append-only audit history is merged. Reusing an ID with different content is forbidden.
        val conflicts = session().createNativeQuery(
            "select count(*) from audit_event a join jsonb_populate_recordset(null::audit_event, cast(cast(:rows as text) as jsonb)) b on a.id=b.id where to_jsonb(a) <> to_jsonb(b)",
            Long::class.javaObjectType,
        ).setParameter("rows", tables.path("audit_event").toString()).singleResult.awaitSuspending()
        if (conflicts.toLong() != 0L) throw BadRequestException("Archive incompatible : conflit avec le journal d’audit existant.")
    }

    private suspend fun schema(): ObjectNode {
        val schema = mapper.createObjectNode()
        val columns = schema.putObject("columns")
        for (table in TABLES) {
            val names = session().createNativeQuery(
                "select column_name from information_schema.columns where table_schema=current_schema() and table_name=:table order by ordinal_position",
                String::class.java,
            ).setParameter("table", table).resultList.awaitSuspending()
            columns.putArray(table).also { array -> names.forEach { array.add(it) } }
        }
        val migrations = session().createNativeQuery(
            "select cast(coalesce(jsonb_agg(jsonb_build_object('version', version, 'checksum', checksum) order by installed_rank), '[]'::jsonb) as text) from flyway_schema_history where success=true",
            String::class.java,
        ).singleResult.awaitSuspending()
        schema.set<JsonNode>("migrations", parse(migrations))
        return schema
    }

    private fun parse(value: String): JsonNode = mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).without(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES).readTree(value)
    private suspend fun session() = Panache.getSession().awaitSuspending()

    companion object {
        const val CONFIRMATION = "RESTORE_ALL_DATA"
        // Dependency order; identifiers are never taken from the archive for SQL construction.
        val TABLES = listOf(
            "accounting_year_settings", "term", "teacher", "musician", "band", "band_member",
            "individual_course", "attendance", "music_expense", "generated_document",
            "teacher_invoice_attendance_allocation", "teacher_billing_adjustment", "audit_event",
        )
    }
}
