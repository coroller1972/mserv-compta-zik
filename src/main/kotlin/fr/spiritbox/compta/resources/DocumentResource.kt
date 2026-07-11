package fr.spiritbox.compta.resources

import fr.spiritbox.compta.openapi.api.DocumentsApi
import fr.spiritbox.compta.services.DocumentService
import fr.spiritbox.compta.openapi.model.DocumentCorrectionRequest
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.*

@Tag(name = "Documents")
class DocumentResource(
    private val documentService: DocumentService,
) : DocumentsApi {

    override suspend fun downloadDocument(documentId: UUID): Response =
        documentService.downloadDocument(documentId)

    override suspend fun markDocumentSent(documentId: UUID): Response =
        Response.ok(documentService.markSent(documentId)).build()

    override suspend fun cancelDocument(documentId: UUID, documentCorrectionRequest: DocumentCorrectionRequest): Response =
        Response.ok(documentService.cancel(documentId, documentCorrectionRequest)).build()

    override suspend fun correctDocument(documentId: UUID, documentCorrectionRequest: DocumentCorrectionRequest): Response =
        Response.ok(documentService.correct(documentId, documentCorrectionRequest)).build()

}
