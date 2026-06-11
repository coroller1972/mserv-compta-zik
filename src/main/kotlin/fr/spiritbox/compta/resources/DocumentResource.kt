package fr.spiritbox.compta.resources

import fr.spiritbox.compta.openapi.api.DocumentsApi
import fr.spiritbox.compta.services.DocumentService
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.*

@Tag(name = "Documents")
class DocumentResource(
    private val documentService: DocumentService,
) : DocumentsApi {

    override suspend fun downloadDocument(documentId: UUID): Response =
        documentService.downloadDocument(documentId)

}
