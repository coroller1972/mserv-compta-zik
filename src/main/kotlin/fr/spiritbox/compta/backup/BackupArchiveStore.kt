package fr.spiritbox.compta.backup

import com.fasterxml.jackson.databind.cfg.JsonNodeFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import fr.spiritbox.compta.openapi.model.BackupInfo
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.NotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import java.time.ZoneOffset
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** File and compression work stays off the Vert.x event loop. No ZIP entry becomes a disk path. */
@ApplicationScoped
class BackupArchiveStore(
    private val mapper: ObjectMapper,
    @ConfigProperty(name = "compta.documents.storage-dir", defaultValue = "documents") storageDirectory: String,
) {
    private val root = Path.of(storageDirectory).toAbsolutePath().normalize()
    private val directory get() = root.resolve("import-backups")
    private val reader get() = mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).without(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)

    suspend fun write(manifest: ObjectNode, reason: String): BackupInfo = withContext(Dispatchers.IO) {
        Files.createDirectories(directory)
        checkInsideRoot(directory)
        val target = directory.resolve("$reason-${UUID.randomUUID()}.zip")
        val temporary = Files.createTempFile(directory, ".backup-", ".tmp")
        try {
            val files = manifest.putArray("files")
            var expanded = 0L
            ZipOutputStream(Files.newOutputStream(temporary)).use { zip ->
                manifest.path("tables").path("generated_document").forEach { doc ->
                    val stored = doc.path("storage_path")
                    if (!stored.isNull && !stored.isMissingNode) {
                        checkSize(files.size() < 9999) // Plus one manifest entry, matching the read limit.
                        val source = root.resolve(stored.asText()).normalize()
                        if (!source.startsWith(root) || !Files.isRegularFile(source, NOFOLLOW_LINKS)) {
                            throw BadRequestException("Sauvegarde impossible : PDF manquant pour le document ${doc.path("id").asText()}.")
                        }
                        checkInsideRoot(source)
                        val size = Files.size(source)
                        expanded += size
                        checkSize(expanded <= MAX_EXPANDED && size <= MAX_FILE)
                        val entry = "documents/${UUID.fromString(doc.path("id").asText())}.pdf"
                        val digest = MessageDigest.getInstance("SHA-256")
                        zip.putNextEntry(ZipEntry(entry))
                        Files.newInputStream(source).use { input ->
                            val buffer = ByteArray(8192)
                            var copied = 0L
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                copied += n
                                checkSize(copied <= size)
                                digest.update(buffer, 0, n)
                                zip.write(buffer, 0, n)
                            }
                            checkSize(copied == size)
                        }
                        zip.closeEntry()
                        files.addObject().put("documentId", doc.path("id").asText()).put("entry", entry)
                            .put("size", size).put("sha256", digest.digest().hex())
                    } else if (doc.path("status").asText() in setOf("GENERATED", "SENT", "CREDITED")) {
                        throw BadRequestException("Sauvegarde impossible : document finalisé sans PDF (${doc.path("id").asText()}).")
                    }
                }
                val bytes = mapper.writeValueAsBytes(manifest)
                checkSize(bytes.size <= MAX_MANIFEST && expanded + bytes.size <= MAX_EXPANDED)
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(bytes)
                zip.closeEntry()
            }
            checkSize(Files.size(temporary) <= MAX_ARCHIVE)
            // A failed/incomplete ZIP never appears in the downloadable backup list.
            Files.move(temporary, target, ATOMIC_MOVE)
            info(target)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    suspend fun list(): List<BackupInfo> = withContext(Dispatchers.IO) {
        if (!Files.exists(directory)) emptyList() else {
            checkInsideRoot(directory)
            Files.list(directory).use { paths ->
                paths.filter { NAME.matches(it.fileName.toString()) && Files.isRegularFile(it, NOFOLLOW_LINKS) }
                    .map { info(it) }.toList().sortedByDescending { it.createdAt }
            }
        }
    }

    suspend fun download(name: String): Path = withContext(Dispatchers.IO) {
        if (!NAME.matches(name)) throw NotFoundException()
        val path = directory.resolve(name)
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) throw NotFoundException()
        checkInsideRoot(path)
        path
    }

    suspend fun read(bytes: ByteArray): Archive = withContext(Dispatchers.IO) {
        checkSize(bytes.size.toLong() <= MAX_ARCHIVE)
        try {
            val entries = mutableMapOf<String, ByteArray>()
            var total = 0L
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || (entry.name != "manifest.json" && !PDF_ENTRY.matches(entry.name)) || entries.containsKey(entry.name)) {
                        throw BadRequestException("Archive invalide : entrée ZIP interdite ou dupliquée.")
                    }
                    checkSize(entries.size < 10000)
                    val limit = if (entry.name == "manifest.json") MAX_MANIFEST.toLong() else MAX_FILE
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        total += n
                        checkSize(total <= MAX_EXPANDED && output.size().toLong() + n <= limit)
                        output.write(buffer, 0, n)
                    }
                    entries[entry.name] = output.toByteArray()
                    zip.closeEntry()
                }
            }
            val manifestBytes = entries.remove("manifest.json") ?: throw BadRequestException("Archive invalide : manifeste absent.")
            val manifest = reader.readTree(manifestBytes) as? ObjectNode
                ?: throw BadRequestException("Archive invalide : manifeste incorrect.")
            if (manifest.path("formatVersion").asInt() != 1 || !manifest.path("tables").isObject || !manifest.path("files").isArray) {
                throw BadRequestException("Format de sauvegarde non pris en charge.")
            }
            val documents = manifest.path("tables").path("generated_document")
            val expected = documents.filter { !it.path("storage_path").isNull && !it.path("storage_path").isMissingNode }
                .map { it.path("id").asText() }.toSet()
            val seen = mutableSetOf<String>()
            manifest.path("files").forEach { file ->
                val id = UUID.fromString(file.path("documentId").asText()).toString()
                val name = "documents/$id.pdf"
                if (!seen.add(id) || file.path("entry").asText() != name || id !in expected) {
                    throw BadRequestException("Archive invalide : association document/PDF incorrecte.")
                }
                val pdf = entries[name] ?: throw BadRequestException("Archive invalide : PDF absent.")
                if (file.path("size").asLong(-1) != pdf.size.toLong() || file.path("sha256").asText() != MessageDigest.getInstance("SHA-256").digest(pdf).hex()) {
                    throw BadRequestException("Archive invalide : intégrité du PDF incorrecte.")
                }
            }
            if (seen != expected || entries.keys != seen.map { "documents/$it.pdf" }.toSet()) {
                throw BadRequestException("Archive invalide : PDF manquants ou non référencés.")
            }
            documents.forEach { doc ->
                if (doc.path("status").asText() in setOf("GENERATED", "SENT", "CREDITED") && doc.path("id").asText() !in seen) {
                    throw BadRequestException("Archive invalide : document finalisé sans PDF.")
                }
            }
            Archive(manifest, entries)
        } catch (e: BadRequestException) {
            throw e
        } catch (e: Exception) {
            throw BadRequestException("Archive ZIP illisible ou invalide.")
        }
    }

    suspend fun stage(archive: Archive): Unit = withContext(Dispatchers.IO) {
        // Never overwrite files used by the current database. On rollback these files are
        // merely unreferenced; existing PDFs remain readable even if the process crashes.
        val parent = root.resolve("restored")
        Files.createDirectories(parent)
        checkInsideRoot(parent)
        val target = Files.createDirectory(parent.resolve(UUID.randomUUID().toString()))
        archive.manifest.path("tables").path("generated_document").forEach { row ->
            val id = row.path("id").asText()
            archive.files["documents/$id.pdf"]?.let { bytes ->
                val file = target.resolve("$id.pdf")
                Files.write(file, bytes)
                (row as ObjectNode).put("storage_path", root.relativize(file).toString())
            }
        }
    }

    private fun checkInsideRoot(path: Path) {
        if (!path.toRealPath().startsWith(root.toRealPath())) throw BadRequestException("Chemin de stockage interdit.")
    }

    private fun info(path: Path) = BackupInfo(
        fileName = path.fileName.toString(),
        createdAt = Files.getLastModifiedTime(path).toInstant().atOffset(ZoneOffset.UTC),
        sizeBytes = Files.size(path),
    )

    private fun checkSize(valid: Boolean) {
        if (!valid) throw BadRequestException("Limite de sauvegarde dépassée (ZIP 64 Mio, contenu 256 Mio, PDF 32 Mio, manifeste 16 Mio, 10 000 entrées).")
    }

    data class Archive(val manifest: ObjectNode, val files: Map<String, ByteArray>)

    companion object {
        const val MAX_ARCHIVE = 64L * 1024 * 1024
        private const val MAX_EXPANDED = 256L * 1024 * 1024
        private const val MAX_FILE = 32L * 1024 * 1024
        private const val MAX_MANIFEST = 16 * 1024 * 1024
        private val NAME = Regex("(?:backup|before-import|before-restore)-[0-9a-f-]{36}\\.zip")
        private val PDF_ENTRY = Regex("documents/[0-9a-f-]{36}\\.pdf")
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    }
}
