package fr.spiritbox.compta.resources

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import fr.spiritbox.compta.security.IdentityStub
import fr.spiritbox.compta.services.BackupService
import io.agroal.api.AgroalDataSource
import io.quarkus.test.common.QuarkusTestResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@QuarkusTest
@QuarkusTestResource(IdentityStub::class)
class BackupResourceTest {
    @Inject lateinit var dataSource: AgroalDataSource
    @Inject lateinit var mapper: ObjectMapper
    private val root = Path.of("/tmp/compta-zik-test-documents")
    private val token get() = IdentityStub.token(admin = true)
    private fun request() = given().header("Authorization", "Bearer $token")

    @Test
    fun `complete archive survives import and restores exact billing records and PDF bytes`() {
        val fixture = seed()
        val before = database()
        val created = request().post("/data/backups").then().statusCode(201).extract().path<String>("fileName")
        val zip = request().get("/data/backups/$created").then().statusCode(200).extract().asByteArray()
        val entries = unzip(zip)
        val manifest = mapper.readTree(entries.getValue("manifest.json"))
        assertEquals(before, manifest.path("tables"))
        fixture.pdfs.forEach { (id, bytes) -> assertArrayEquals(bytes, entries["documents/$id.pdf"]) }
        request().contentType("application/octet-stream").body(zip).post("/data/restore/analyze")
            .then().statusCode(200).body("valid", equalTo(true)).body("confirmationValue", equalTo("RESTORE_ALL_DATA"))

        // A missing source PDF must prevent a destructive import and any incomplete backup.
        val pdfPath = root.resolve(fixture.paths.values.first())
        val original = Files.readAllBytes(pdfPath)
        Files.delete(pdfPath)
        try {
            importEmpty().then().statusCode(400)
            assertEquals(before, database())
        } finally { Files.write(pdfPath, original) }

        val automatic = importEmpty().then().statusCode(200).extract().path<String>("backupFile")
        assertTrue(automatic.endsWith(".zip"))
        val automaticZip = request().get("/data/backups/$automatic").then().statusCode(200).extract().asByteArray()
        assertEquals(before, mapper.readTree(unzip(automaticZip).getValue("manifest.json")).path("tables"))
        assertTrue(database().path("generated_document").isEmpty)
        request().get("/documents/${fixture.pdfs.keys.first()}").then().statusCode(404)

        val empty = database()
        request().contentType("application/octet-stream").body(zip).post("/data/restore").then().statusCode(400)
        assertEquals(empty, database())
        // Restore solely from the archive: none of the original files is still present.
        fixture.paths.values.forEach { Files.delete(root.resolve(it)) }
        val restored = request().header("X-Restore-Confirmation", "RESTORE_ALL_DATA")
            .contentType("application/octet-stream").body(zip).post("/data/restore")
            .then().statusCode(200).extract().path<String>("backupFile")
        assertTrue(restored.startsWith("before-restore-"))
        val after = database()
        BackupService.TABLES.filter { it !in setOf("audit_event", "generated_document") }.forEach {
            assertEquals(before.path(it), after.path(it), "Table $it must retain all IDs, timestamps and versions")
        }
        fun withoutPaths(rows: JsonNode) = rows.deepCopy<JsonNode>().also { list -> list.forEach { (it as ObjectNode).remove("storage_path") } }
        assertEquals(withoutPaths(before.path("generated_document")), withoutPaths(after.path("generated_document")))
        assertTrue(after.path("audit_event").toSet().containsAll(before.path("audit_event").toSet()))
        assertTrue(after.path("audit_event").any { it.path("action").asText() == "RESTORED" })
        fixture.pdfs.forEach { (id, bytes) ->
            assertArrayEquals(bytes, request().get("/documents/$id").then().statusCode(200).extract().asByteArray())
        }

        // A constraint failure after DELETE must roll back the whole database, including audit.
        val invalid = unzip(zip).toMutableMap()
        val badManifest = mapper.readTree(invalid.getValue("manifest.json")) as ObjectNode
        (badManifest.path("tables").path("accounting_year_settings").first() as ObjectNode).put("teacher_hourly_rate", -1)
        invalid["manifest.json"] = mapper.writeValueAsBytes(badManifest)
        request().header("X-Restore-Confirmation", "RESTORE_ALL_DATA").contentType("application/octet-stream")
            .body(zip(invalid)).post("/data/restore").then().statusCode(400)
        assertEquals(after, database())
        fixture.pdfs.forEach { (id, bytes) ->
            assertArrayEquals(bytes, request().get("/documents/$id").then().statusCode(200).extract().asByteArray())
        }

        // PDF integrity, path traversal, incompatible schema and orphan references are rejected.
        val corrupt = unzip(zip).toMutableMap()
        corrupt["documents/${fixture.pdfs.keys.first()}.pdf"] = "changed".toByteArray()
        rejectsWithoutChanges(zip(corrupt), after)
        rejectsWithoutChanges(zip(unzip(zip) + ("../outside.pdf" to byteArrayOf(1))), after)
        val missing = unzip(zip).toMutableMap().also { it.remove("documents/${fixture.pdfs.keys.first()}.pdf") }
        rejectsWithoutChanges(zip(missing), after)
        val badSchema = unzip(zip).toMutableMap()
        val changed = mapper.readTree(badSchema.getValue("manifest.json")) as ObjectNode
        changed.put("schema", "old")
        badSchema["manifest.json"] = mapper.writeValueAsBytes(changed)
        rejectsWithoutChanges(zip(badSchema), after)
        val orphan = unzip(zip).toMutableMap()
        val orphanManifest = mapper.readTree(orphan.getValue("manifest.json")) as ObjectNode
        (orphanManifest.path("tables").path("teacher_invoice_attendance_allocation").first() as ObjectNode)
            .put("attendance_id", UUID.randomUUID().toString())
        orphan["manifest.json"] = mapper.writeValueAsBytes(orphanManifest)
        rejectsWithoutChanges(zip(orphan), after)
        // A tiny compressed upload must not bypass the expansion limit for a PDF.
        val oversized = zip(mapOf("documents/${fixture.pdfs.keys.first()}.pdf" to ByteArray(32 * 1024 * 1024 + 1)))
        assertTrue(oversized.size < 64 * 1024)
        rejectsWithoutChanges(oversized, after)
        // Audit entries may never be overwritten by a conflicting archive.
        val auditConflict = unzip(zip).toMutableMap()
        val conflictManifest = mapper.readTree(auditConflict.getValue("manifest.json")) as ObjectNode
        (conflictManifest.path("tables").path("audit_event").first() as ObjectNode).put("action", "FORGED")
        auditConflict["manifest.json"] = mapper.writeValueAsBytes(conflictManifest)
        rejectsWithoutChanges(zip(auditConflict), after)
    }

    @Test
    fun `archives require import export permission`() {
        val limited = IdentityStub.token(permissions = listOf("BILLING_READ"))
        for (path in listOf("/data/backups", "/data/backups/backup-00000000-0000-0000-0000-000000000000.zip")) {
            given().header("Authorization", "Bearer $limited").get(path).then().statusCode(403)
        }
        for (path in listOf("/data/backups", "/data/restore/analyze", "/data/restore")) {
            given().header("Authorization", "Bearer $limited").contentType("application/octet-stream")
                .body(byteArrayOf(1)).post(path).then().statusCode(403)
        }
    }

    @Test
    fun `backup and normal writes wait for the corresponding database maintenance lock`() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            for (exclusive in listOf(false, true)) {
                dataSource.connection.use { connection ->
                    connection.autoCommit = false
                    val lock = if (exclusive) "pg_advisory_xact_lock" else "pg_advisory_xact_lock_shared"
                    connection.createStatement().execute("select $lock(721640982173)")
                    val future = pool.submit<Int> {
                        if (exclusive) request().contentType("application/json")
                            .body("""{"firstName":"Concurrent","lastName":"Backup","instrument":"Piano","active":true}""")
                            .post("/teachers").statusCode()
                        else request().post("/data/backups").statusCode()
                    }
                    assertThrows(TimeoutException::class.java) { future.get(500, TimeUnit.MILLISECONDS) }
                    connection.commit()
                    val status = future.get(15, TimeUnit.SECONDS)
                    assertTrue(status in listOf(200, 201), "Operation must finish after the lock is released: $status")
                }
            }
        } finally { pool.shutdownNow() }
    }

    private fun rejectsWithoutChanges(bytes: ByteArray, before: JsonNode) {
        request().contentType("application/octet-stream").body(bytes).post("/data/restore/analyze").then().statusCode(400)
        request().header("X-Restore-Confirmation", "RESTORE_ALL_DATA").contentType("application/octet-stream")
            .body(bytes).post("/data/restore").then().statusCode(400)
        assertEquals(before, database())
    }

    private fun importEmpty() = request().header("X-Import-Confirmation", "REPLACE_ALL_DATA").contentType("application/json")
        .body("""{"formatVersion":2,"exportedAt":"2026-09-12T12:00:00Z","settings":[],"teachers":[],"musicians":[],"bands":[],"individualCourses":[],"attendance":[],"expenses":[]}""")
        .post("/data/import")

    private fun database(): ObjectNode = mapper.createObjectNode().also { result ->
        dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute("set time zone 'UTC'") }
            for (table in BackupService.TABLES) {
                val rows = result.putArray(table)
                connection.createStatement().executeQuery("select to_jsonb(t)::text from $table t order by id").use { query ->
                    while (query.next()) rows.add(mapper.readTree(query.getString(1)))
                }
            }
        }
    }

    private fun seed(): Fixture {
        val settings = UUID.randomUUID(); val term = UUID.randomUUID(); val teacher = UUID.randomUUID()
        val musician = UUID.randomUUID(); val band = UUID.randomUUID(); val course = UUID.randomUUID()
        val attendance = UUID.randomUUID(); val releasedAttendance = UUID.randomUUID()
        val invoice = UUID.randomUUID(); val credit = UUID.randomUUID(); val teacherInvoice = UUID.randomUUID()
        val paths = listOf(invoice, credit, teacherInvoice).associate { it.toString() to "backup-test/$it.pdf" }
        val pdfs = paths.mapValues { (id, _) -> "%PDF-1.4\nOriginal test document $id\n%%EOF".toByteArray() }
        Files.createDirectories(root.resolve("backup-test"))
        paths.forEach { (id, path) -> Files.write(root.resolve(path), pdfs.getValue(id)) }
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            val sql = listOf(
                "insert into accounting_year_settings(id,year,teacher_hourly_rate,group_membership_fee,status,closed_at,version) values('$settings',2097,0,0,'CLOSED',now(),7)",
                "insert into term(id,settings_id,name,start_week,end_week) values('$term','$settings','Archive',1,10)",
                "insert into teacher(id,first_name,last_name,instrument) values('00000000-0000-0000-0000-000000000000','Zero','Archive','Piano')",
                "insert into teacher(first_name,last_name,instrument) select 'Pagination', 'Archive ' || n, 'Piano' from generate_series(1,501) n",
                "insert into teacher(id,first_name,last_name,instrument) values('$teacher','Test','Archive','Piano')",
                "insert into musician(id,first_name,last_name,email,active) values('$musician','Test','Archive','test@example.invalid',false)",
                "insert into band(id,name,type,teacher_id,settings_id,weekday) values('$band','Archive','WORKSHOP','$teacher','$settings','Lundi')",
                "insert into band_member(band_id,musician_id) values('$band','$musician')",
                "insert into individual_course(id,musician_id,teacher_id,instrument,weekday,start_time,settings_id) values('$course','$musician','$teacher','Piano','Lundi','10:00','$settings')",
                "insert into attendance(id,term_id,week,entity_type,entity_id,present,status,session_date,version) values('$attendance','$term',2,'INDIVIDUAL_COURSE','$course',true,'PRESENT','2097-01-07',3)",
                "insert into attendance(id,term_id,week,entity_type,entity_id,present,status,session_date) values('$releasedAttendance','$term',3,'WORKSHOP','$band',false,'CANCELLED','2097-01-14')",
                "insert into music_expense(year,expense_date,category,label,amount,version) values(2097,'2097-01-07','OTHER','Archive',12.34,4)",
                "insert into generated_document(id,term_id,type,status,musician_id,document_number,file_name,storage_path,content_type,total_amount,metadata,version) values('$invoice','$term','STUDENT_INVOICE','CREDITED','$musician','ARCH-1','$invoice.pdf','${paths[invoice.toString()]}','application/pdf',12.34,'{\"frozen\":{\"rate\":12.34,\"lines\":[1,2]}}',8)",
                "insert into generated_document(id,term_id,type,status,musician_id,parent_document_id,correction_reason,file_name,storage_path,content_type,total_amount) values('$credit','$term','CREDIT_NOTE','GENERATED','$musician','$invoice','Correction archive','$credit.pdf','${paths[credit.toString()]}','application/pdf',12.34)",
                "insert into generated_document(id,term_id,type,status,teacher_id,file_name,storage_path,content_type,total_amount,period_start_week,period_end_week,installment_number) values('$teacherInvoice','$term','TEACHER_INVOICE_REQUEST','SENT','$teacher','$teacherInvoice.pdf','${paths[teacherInvoice.toString()]}','application/pdf',99.99,1,3,1)",
                "insert into generated_document(term_id,type,status,teacher_id,total_amount) values('$term','TEACHER_INVOICE_REQUEST','DRAFT','$teacher',20)",
                "insert into teacher_invoice_attendance_allocation(document_id,attendance_id) values('$teacherInvoice','$attendance')",
                "insert into teacher_invoice_attendance_allocation(document_id,attendance_id,released_at) values('$teacherInvoice','$releasedAttendance',now())",
                "insert into teacher_billing_adjustment(teacher_id,source_document_id,applied_document_id,amount,reason,applied_at) values('$teacher','$teacherInvoice','$teacherInvoice',-12.34,'Ajustement appliqué',now())",
                "insert into teacher_billing_adjustment(teacher_id,source_document_id,amount,reason) values('$teacher','$teacherInvoice',9.87,'Ajustement en attente')",
                "insert into audit_event(entity_type,entity_id,action,user_id,details) values('DOCUMENT','$invoice','CREATED','archive-test','{\"original\":true}')",
            )
            sql.forEach { connection.createStatement().use { statement -> statement.execute(it) } }
            connection.commit()
        }
        return Fixture(paths, pdfs)
    }

    private data class Fixture(val paths: Map<String, String>, val pdfs: Map<String, ByteArray>)
    private fun unzip(bytes: ByteArray): Map<String, ByteArray> = mutableMapOf<String, ByteArray>().also { result ->
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; result[entry.name] = zip.readBytes() }
        }
    }
    private fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
    }.toByteArray()
}
