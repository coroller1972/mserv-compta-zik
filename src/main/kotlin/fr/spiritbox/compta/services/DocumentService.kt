package fr.spiritbox.compta.services

import com.fasterxml.jackson.databind.ObjectMapper
import fr.spiritbox.compta.Mapper
import fr.spiritbox.compta.domain.GeneratedDocumentEntity
import fr.spiritbox.compta.domain.GeneratedDocumentStatus
import fr.spiritbox.compta.domain.GeneratedDocumentType
import fr.spiritbox.compta.openapi.model.DocumentCorrectionRequest
import fr.spiritbox.compta.openapi.model.DocumentCorrectionResult
import fr.spiritbox.compta.openapi.model.GeneratedDocument
import fr.spiritbox.compta.openapi.model.TeacherBillingAdjustment
import fr.spiritbox.compta.openapi.model.TeacherBillingAdjustmentRequest
import fr.spiritbox.compta.repositories.GeneratedDocumentRepository
import fr.spiritbox.compta.repositories.TeacherInvoiceAttendanceAllocationRepository
import fr.spiritbox.compta.repositories.TeacherBillingAdjustmentRepository
import fr.spiritbox.compta.domain.TeacherBillingAdjustmentEntity
import fr.spiritbox.compta.utils.PanacheCoroutine
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.ClientErrorException
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
    private val allocationRepository: TeacherInvoiceAttendanceAllocationRepository,
    private val adjustmentRepository: TeacherBillingAdjustmentRepository,
    private val panacheCoroutine: PanacheCoroutine,
    private val lifecycleService: AccountingYearLifecycleService,
    private val mapper: Mapper,
    private val htmlPdfService: HtmlPdfService,
    private val documentTemplateService: DocumentTemplateService,
    private val objectMapper: ObjectMapper,
    private val auditService: AuditService,
    @ConfigProperty(name = "compta.documents.storage-dir", defaultValue = "documents")
    private val storageDirectory: String,
) {
    suspend fun markSent(documentId: UUID): GeneratedDocument = panacheCoroutine.withTransaction {
        val document = getDocument(documentId)
        lifecycleService.requireDocumentsMutable(document.term.settings)
        requireStatus(document, GeneratedDocumentStatus.GENERATED, "mark as sent")
        document.status = GeneratedDocumentStatus.SENT
        document.sentAt = java.time.Instant.now()
        auditService.record("DOCUMENT", document.id, "SENT", mapOf("number" to document.documentNumber))
        generatedDocumentRepository.flush()
        mapper.run { document.toDto() }.copy(version = document.version + 1)
    }

    suspend fun cancel(documentId: UUID, request: DocumentCorrectionRequest): GeneratedDocument = panacheCoroutine.withTransaction {
        val document = getDocument(documentId)
        lifecycleService.requireDocumentsMutable(document.term.settings)
        requireReason(request.reason)
        requireStatus(document, GeneratedDocumentStatus.GENERATED, "cancel")
        document.status = GeneratedDocumentStatus.CANCELLED
        document.correctionReason = request.reason.trim()
        document.cancelledAt = java.time.Instant.now()
        if (document.type == GeneratedDocumentType.TEACHER_INVOICE_REQUEST && document.installmentNumber != null) {
            val now = java.time.Instant.now()
            allocationRepository.listActiveByDocument(documentId).awaitSuspending().forEach { it.releasedAt = now }
            adjustmentRepository.listAppliedByDocument(documentId).awaitSuspending().forEach { it.appliedDocument = null; it.appliedAt = null }
        }
        auditService.record("DOCUMENT", document.id, "CANCELLED", mapOf("reason" to request.reason.trim()))
        generatedDocumentRepository.flush()
        mapper.run { document.toDto() }.copy(version = document.version + 1)
    }

    suspend fun correct(documentId: UUID, request: DocumentCorrectionRequest): DocumentCorrectionResult = panacheCoroutine.withTransaction {
        val original = getDocument(documentId)
        lifecycleService.requireDocumentsMutable(original.term.settings)
        val reason = requireReason(request.reason)
        if (original.type == GeneratedDocumentType.TEACHER_INVOICE_REQUEST && original.installmentNumber != null) {
            conflict("An installment request cannot be rewritten. Cancel a GENERATED request or report an adjustment after sending it.")
        }
        if (original.status !in setOf(GeneratedDocumentStatus.GENERATED, GeneratedDocumentStatus.SENT)) {
            conflict("Document $documentId cannot be corrected from status ${original.status}.")
        }
        val now = java.time.Instant.now()
        val creditNote = if (original.status == GeneratedDocumentStatus.SENT) {
            original.status = GeneratedDocumentStatus.CREDITED
            original.creditedAt = now
            createCreditNote(original, reason)
        } else {
            original.status = GeneratedDocumentStatus.CANCELLED
            original.cancelledAt = now
            null
        }
        original.correctionReason = reason
        val revision = generatedDocumentRepository.count("parentDocument.id", original.id).awaitSuspending() + 1
        val replacement = GeneratedDocumentEntity().apply {
            term = original.term
            type = original.type
            status = GeneratedDocumentStatus.DRAFT
            musician = original.musician
            teacher = original.teacher
            parentDocument = original
            correctionReason = reason
            documentNumber = "${original.documentNumber ?: original.id}-R$revision"
            totalAmount = original.totalAmount
        }
        generatedDocumentRepository.persist(replacement).awaitSuspending()
        auditService.record("DOCUMENT", original.id, "CORRECTED", mapOf("reason" to reason, "replacementId" to replacement.id, "creditNoteId" to creditNote?.id))
        generatedDocumentRepository.flush()
        DocumentCorrectionResult(
            original = mapper.run { original.toDto() }.copy(version = original.version + 1),
            creditNote = creditNote?.let { mapper.run { it.toDto() } },
            replacement = mapper.run { replacement.toDto() },
        )
    }

    private suspend fun createCreditNote(original: GeneratedDocumentEntity, reason: String): GeneratedDocumentEntity {
        val originalNumber = original.documentNumber ?: original.id.toString()
        val creditNumber = "AV-$originalNumber"
        val fileName = "$creditNumber.pdf".replace(Regex("[^A-Za-z0-9._-]"), "-")
        val credit = GeneratedDocumentEntity().apply {
            term = original.term
            type = GeneratedDocumentType.CREDIT_NOTE
            status = GeneratedDocumentStatus.GENERATED
            musician = original.musician
            teacher = original.teacher
            parentDocument = original
            correctionReason = reason
            documentNumber = creditNumber
            this.fileName = fileName
            contentType = "application/pdf"
            totalAmount = original.totalAmount
            metadata = objectMapper.valueToTree(mapOf("originalDocumentId" to original.id, "reason" to reason, "amount" to original.totalAmount))
            storagePath = htmlPdfService.writeHtmlPdf(
                "${original.term.settings.year}/${original.term.id}/$fileName",
                documentTemplateService.creditNote(creditNumber, originalNumber, reason, original.totalAmount),
            )
        }
        generatedDocumentRepository.persist(credit).awaitSuspending()
        return credit
    }

    private suspend fun getDocument(id: UUID) = generatedDocumentRepository.findByIdWithRelations(id).awaitSuspending()
        ?: throw NotFoundException("Document $id was not found.")

    private fun requireReason(reason: String): String = reason.trim().also {
        if (it.length !in 3..1000) throw jakarta.ws.rs.BadRequestException("A correction reason between 3 and 1000 characters is required.")
    }

    private fun requireStatus(document: GeneratedDocumentEntity, expected: GeneratedDocumentStatus, action: String) {
        if (document.status != expected) conflict("Document ${document.id} cannot $action from status ${document.status}.")
    }

    private fun conflict(message: String): Nothing = throw ClientErrorException(message, Response.Status.CONFLICT)

    suspend fun createAdjustment(documentId: UUID, request: TeacherBillingAdjustmentRequest): TeacherBillingAdjustment = panacheCoroutine.withTransaction {
        val source = getDocument(documentId)
        lifecycleService.requireDocumentsMutable(source.term.settings)
        if (source.type != GeneratedDocumentType.TEACHER_INVOICE_REQUEST || source.teacher == null || source.status !in setOf(GeneratedDocumentStatus.GENERATED, GeneratedDocumentStatus.SENT)) {
            throw jakarta.ws.rs.BadRequestException("The source must be a generated or sent teacher invoice request.")
        }
        val amount = request.amount.setScale(2, java.math.RoundingMode.HALF_UP)
        if (amount.compareTo(java.math.BigDecimal.ZERO) == 0) throw jakarta.ws.rs.BadRequestException("Adjustment amount cannot be zero.")
        val entity = TeacherBillingAdjustmentEntity().apply {
            teacher = requireNotNull(source.teacher)
            sourceDocument = source
            this.amount = amount
            reason = requireReason(request.reason)
        }
        adjustmentRepository.persist(entity).awaitSuspending()
        entity.toDto()
    }

    suspend fun deleteAdjustment(id: UUID): Unit = panacheCoroutine.withTransaction {
        val entity = adjustmentRepository.findByIdWithRelations(id).awaitSuspending() ?: throw NotFoundException("Adjustment $id was not found.")
        lifecycleService.requireDocumentsMutable(entity.sourceDocument.term.settings)
        if (entity.appliedDocument != null) conflict("An adjustment already included in a validated request is immutable.")
        adjustmentRepository.delete(entity).awaitSuspending()
    }

    private fun TeacherBillingAdjustmentEntity.toDto() = TeacherBillingAdjustment(
        id = requireNotNull(id), teacherId = requireNotNull(teacher.id), sourceDocumentId = requireNotNull(sourceDocument.id),
        sourceDocumentNumber = sourceDocument.documentNumber, appliedDocumentId = appliedDocument?.id, amount = amount, reason = reason,
    )

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
