package fr.spiritbox.compta.services

import fr.spiritbox.compta.repositories.GeneratedDocumentRepository
import fr.spiritbox.compta.utils.PanacheCoroutine
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@ApplicationScoped
class DocumentService(
    private val generatedDocumentRepository: GeneratedDocumentRepository,
    private val panacheCoroutine: PanacheCoroutine,
    @ConfigProperty(name = "compta.documents.storage-dir", defaultValue = "documents")
    private val storageDirectory: String,
) {
    suspend fun downloadDocument(documentId: UUID): Response = panacheCoroutine.withSession {
        val document = generatedDocumentRepository.find("id", documentId).firstResult().awaitSuspending()
            ?: throw NotFoundException("Document $documentId was not found.")
        val storagePath = document.storagePath
            ?: throw NotFoundException("Document $documentId has no generated file.")
        val filePath = resolveStoragePath(storagePath)

        if (!Files.isRegularFile(filePath) || !Files.isReadable(filePath)) {
            throw NotFoundException("Document file for $documentId was not found.")
        }

        val contentType = document.contentType ?: MediaType.APPLICATION_OCTET_STREAM
        val fileName = document.fileName ?: filePath.fileName.toString()

        Response.ok(filePath.toFile(), contentType)
            .header(HttpHeaders.CONTENT_DISPOSITION, """attachment; filename="${fileName.toDownloadFileName()}"""")
            .build()
    }

    private fun resolveStoragePath(storagePath: String): Path {
        val path = Path.of(storagePath)
        if (path.isAbsolute) {
            return path.normalize()
        }

        val storageRoot = Path.of(storageDirectory).toAbsolutePath().normalize()
        val resolvedPath = storageRoot.resolve(path).normalize()
        if (!resolvedPath.startsWith(storageRoot)) {
            throw NotFoundException("Document file was not found.")
        }

        return resolvedPath
    }

    private fun String.toDownloadFileName() =
        replace(Regex("""["\r\n/\\]"""), "_")
}
