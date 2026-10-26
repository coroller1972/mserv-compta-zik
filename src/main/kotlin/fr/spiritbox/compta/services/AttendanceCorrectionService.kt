package fr.spiritbox.compta.services

import fr.spiritbox.compta.Mapper
import fr.spiritbox.compta.domain.AttendanceEntityType
import fr.spiritbox.compta.domain.AttendanceStatus
import fr.spiritbox.compta.domain.GeneratedDocumentStatus
import fr.spiritbox.compta.domain.TeacherBillingAdjustmentEntity
import fr.spiritbox.compta.openapi.model.AttendanceCorrectionRequest
import fr.spiritbox.compta.openapi.model.AttendanceCorrectionResult
import fr.spiritbox.compta.openapi.model.TeacherBillingAdjustment
import fr.spiritbox.compta.repositories.AttendanceRepository
import fr.spiritbox.compta.repositories.BandRepository
import fr.spiritbox.compta.repositories.IndividualCourseRepository
import fr.spiritbox.compta.repositories.TeacherBillingAdjustmentRepository
import fr.spiritbox.compta.repositories.TeacherInvoiceAttendanceAllocationRepository
import fr.spiritbox.compta.security.AuthenticatedUser
import fr.spiritbox.compta.services.BillingCalculator.toMoney
import fr.spiritbox.compta.utils.PanacheCoroutine
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.ClientErrorException
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.core.Response
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Correction de présences déjà rémunérées par une demande prestataire envoyée.
 *
 * Les présences restent la seule source de vérité : elles passent au statut demandé (absent ou annulé),
 * sont libérées de la demande d'origine, et le montant versé pour chacune — repris de l'instantané de la
 * demande — est régularisé en négatif sur la prochaine situation du professeur. La facture de l'élève se
 * corrige ensuite normalement (avoir + remplacement), recalculée sur les présences corrigées.
 */
@ApplicationScoped
class AttendanceCorrectionService(
    private val attendanceRepository: AttendanceRepository,
    private val allocationRepository: TeacherInvoiceAttendanceAllocationRepository,
    private val adjustmentRepository: TeacherBillingAdjustmentRepository,
    private val individualCourseRepository: IndividualCourseRepository,
    private val bandRepository: BandRepository,
    private val lifecycleService: AccountingYearLifecycleService,
    private val auditService: AuditService,
    private val authenticatedUser: AuthenticatedUser,
    private val panacheCoroutine: PanacheCoroutine,
    private val mapper: Mapper,
) {
    suspend fun correctAfterBilling(request: AttendanceCorrectionRequest): AttendanceCorrectionResult = panacheCoroutine.withTransaction {
        // La route exige BILLING_PRINT ; modifier des présences exige aussi PRESENCE_WRITE.
        authenticatedUser.requirePermission("PRESENCE_WRITE")
        val target = AttendanceStatus.valueOf(request.status.name)
        if (target == AttendanceStatus.PRESENT) throw BadRequestException("A post-billing correction sets attendance to ABSENT or CANCELLED.")
        val reason = request.reason.trim()
        if (reason.length !in 3..1000) throw BadRequestException("A correction reason between 3 and 1000 characters is required.")
        val versions = request.attendance.associate { it.id to it.version }
        if (versions.size != request.attendance.size) throw BadRequestException("Each attendance can be listed only once.")

        val attendance = attendanceRepository.listByIdsWithSettings(versions.keys).awaitSuspending()
        val missing = versions.keys - attendance.mapNotNull { it.id }.toSet()
        if (missing.isNotEmpty()) throw NotFoundException("Attendance was not found: ${missing.joinToString()}.")
        attendance.forEach { entity ->
            lifecycleService.requireAttendanceMutable(entity.term.settings)
            lifecycleService.requireDocumentsMutable(entity.term.settings)
            OptimisticLocking.requireVersion(versions[entity.id], entity.version, "Attendance")
            if (entity.status != AttendanceStatus.PRESENT) throw BadRequestException("Only a present session can be corrected after billing (week ${entity.week}).")
        }

        val allocations = allocationRepository.listActiveWithTeacherByAttendanceIds(versions.keys).awaitSuspending()
            .associateBy { requireNotNull(it.attendance.id) }
        attendance.forEach { entity ->
            val allocation = allocations[entity.id]
                ?: throw BadRequestException("Week ${entity.week} is not billed to a teacher yet: edit the attendance directly.")
            if (allocation.document.status != GeneratedDocumentStatus.SENT) {
                throw ClientErrorException(
                    "Teacher request ${allocation.document.documentNumber ?: allocation.document.id} is not sent: cancel it to edit its attendance instead.",
                    Response.Status.CONFLICT,
                )
            }
        }

        val labels = sessionLabels(attendance.first().term.settings.year)
        val now = Instant.now()
        // Une régularisation par demande d'origine, du montant exact versé pour ces présences.
        val adjustments = attendance.groupBy { requireNotNull(allocations[it.id]).document }.map { (document, corrected) ->
            val paid = paidAmounts(document.metadata)
            val amount = corrected.fold(BigDecimal.ZERO) { sum, entity ->
                val settings = entity.term.settings
                sum.add(paid[entity.id] ?: hoursFor(entity.entityType, settings.individualCourseHours, settings.workshopHours).multiply(settings.teacherHourlyRate))
            }.toMoney().negate()
            val weeks = corrected.map { it.week }.sorted().joinToString(", ") { "S$it" }
            val sessions = corrected.mapNotNull { labels[it.entityId] }.distinct().joinToString(", ")
            TeacherBillingAdjustmentEntity().apply {
                teacher = requireNotNull(document.teacher) { "A teacher request must have a teacher." }
                sourceDocument = document
                this.amount = amount
                this.reason = listOf("Présences corrigées", sessions, weeks).filter { it.isNotBlank() }.joinToString(" · ") + " : $reason"
            }
        }

        if (request.dryRun != true) {
            attendance.forEach { entity ->
                entity.status = target
                entity.present = false
                requireNotNull(allocations[entity.id]).releasedAt = now
                auditService.record(
                    "ATTENDANCE", entity.id, "CORRECTED_AFTER_BILLING",
                    mapOf("week" to entity.week, "status" to target.name, "reason" to reason, "document" to allocations[entity.id]?.document?.documentNumber),
                )
            }
            adjustments.forEach { adjustmentRepository.persist(it).awaitSuspending() }
            attendanceRepository.flush()
        }

        AttendanceCorrectionResult(
            dryRun = request.dryRun == true,
            attendance = attendance.map { entity ->
                mapper.run { entity.toDto() }.let { dto ->
                    if (request.dryRun == true) dto else dto.copy(version = entity.version + 1, billingLocked = false)
                }
            },
            adjustments = adjustments.map { it.toDto() },
        )
    }

    /** Montant versé par présence, d'après l'instantané figé à la validation de la demande. */
    private fun paidAmounts(metadata: com.fasterxml.jackson.databind.JsonNode?): Map<UUID, BigDecimal> =
        metadata?.path("attendance")?.mapNotNull { line ->
            val id = line.path("id").asText().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val amount = line.path("amount").takeIf { it.isNumber || it.isTextual } ?: return@mapNotNull null
            UUID.fromString(id) to BigDecimal(amount.asText())
        }?.toMap().orEmpty()

    private fun hoursFor(type: AttendanceEntityType, individual: BigDecimal, workshop: BigDecimal) =
        if (type == AttendanceEntityType.INDIVIDUAL_COURSE) individual else workshop

    /** Nom affiché sur la régularisation : élève du cours, ou nom de l'atelier. */
    private suspend fun sessionLabels(year: Int): Map<UUID, String> {
        val courses = individualCourseRepository.listWithRelationsByYear(year).awaitSuspending()
            .associate { requireNotNull(it.id) to "${it.musician.firstName} ${it.musician.lastName}".trim() }
        val bands = bandRepository.listWithMembersByYear(year).awaitSuspending().associate { requireNotNull(it.id) to it.name }
        return courses + bands
    }

    private fun TeacherBillingAdjustmentEntity.toDto() = TeacherBillingAdjustment(
        id = id ?: UUID(0, 0),
        teacherId = requireNotNull(teacher.id),
        sourceDocumentId = requireNotNull(sourceDocument.id),
        sourceDocumentNumber = sourceDocument.documentNumber,
        appliedDocumentId = appliedDocument?.id,
        amount = amount,
        reason = reason,
    )
}
