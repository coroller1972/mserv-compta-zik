package fr.spiritbox.compta.services

import fr.spiritbox.compta.Mapper
import fr.spiritbox.compta.domain.AttendanceEntity
import fr.spiritbox.compta.domain.AttendanceEntityType
import fr.spiritbox.compta.domain.AttendanceStatus
import fr.spiritbox.compta.domain.BandType
import fr.spiritbox.compta.openapi.model.Attendance
import fr.spiritbox.compta.repositories.AttendanceRepository
import fr.spiritbox.compta.repositories.BandRepository
import fr.spiritbox.compta.repositories.IndividualCourseRepository
import fr.spiritbox.compta.repositories.TermRepository
import fr.spiritbox.compta.utils.PanacheCoroutine
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.BadRequestException
import java.util.*

@ApplicationScoped
class AttendanceService(
    private val attendanceRepository: AttendanceRepository,
    private val termRepository: TermRepository,
    private val individualCourseRepository: IndividualCourseRepository,
    private val bandRepository: BandRepository,
    private val panacheCoroutine: PanacheCoroutine,
    private val mapper: Mapper,
    private val lifecycleService: AccountingYearLifecycleService,
    private val auditService: AuditService,
) {

    suspend fun upsertAttendance(attendance: Attendance): Attendance = panacheCoroutine.withTransaction {
        val term = getTerm(attendance.termId)
        lifecycleService.requireAttendanceMutable(term.settings)
        val entityType = AttendanceEntityType.valueOf(attendance.entityType.name)
        if (attendance.week !in term.startWeek..term.endWeek) {
            throw BadRequestException("Week ${attendance.week} is outside term ${attendance.termId} (${term.startWeek}-${term.endWeek}).")
        }
        val weekday = validateTarget(entityType, attendance.entityId, term.settings.year)
        val sessionDate = attendance.sessionDate ?: SessionDateCalculator.scheduledDate(term.settings.year, attendance.week, weekday)
        if (SessionDateCalculator.accountingWeek(term.settings.year, sessionDate) != attendance.week) {
            throw BadRequestException("Session date $sessionDate does not belong to accounting week ${attendance.week}.")
        }
        val existing = attendanceRepository.findByNaturalKey(
            termId = attendance.termId,
            week = attendance.week,
            entityType = entityType,
            entityId = attendance.entityId,
        ).awaitSuspending()
        if (existing != null) OptimisticLocking.requireVersion(attendance.version, existing.version, "Attendance")
        val entity = existing ?: AttendanceEntity().apply {
            this.term = term
            week = attendance.week
            this.entityType = entityType
            entityId = attendance.entityId
            this.sessionDate = sessionDate
            attendanceRepository.persist(this).awaitSuspending()
        }

        val status = attendance.status?.let { AttendanceStatus.valueOf(it.name) }
            ?: if (attendance.present) AttendanceStatus.PRESENT else AttendanceStatus.ABSENT
        entity.status = status
        entity.present = status == AttendanceStatus.PRESENT
        entity.sessionDate = sessionDate

        auditService.record("ATTENDANCE", entity.id, if (existing == null) "CREATED" else "UPDATED", mapOf("week" to entity.week, "sessionDate" to entity.sessionDate, "status" to entity.status.name))
        attendanceRepository.flush()

        mapper.run { entity.toDto() }.copy(version = if (existing == null) entity.version else entity.version + 1)
    }

    private suspend fun getTerm(termId: UUID) = termRepository.findByIdWithSettings(termId)
        .awaitSuspending()
        ?: throw NotFoundException("Term $termId was not found.")

    private suspend fun validateTarget(entityType: AttendanceEntityType, entityId: UUID, year: Int): String? =
        when (entityType) {
            AttendanceEntityType.INDIVIDUAL_COURSE -> {
                val course = individualCourseRepository.findByIdWithRelations(entityId).awaitSuspending()
                    ?: throw NotFoundException("Individual course $entityId was not found.")
                if (course.settings.year != year) {
                    throw BadRequestException("Individual course $entityId does not belong to accounting year $year.")
                }
                course.weekday
            }
            AttendanceEntityType.WORKSHOP -> {
                val band = bandRepository.findByIdWithMembers(entityId).awaitSuspending()
                    ?: throw NotFoundException("Workshop $entityId was not found.")
                if (band.type != BandType.WORKSHOP) {
                    throw BadRequestException("Band $entityId is not a workshop.")
                }
                if (band.settings.year != year) {
                    throw BadRequestException("Workshop $entityId does not belong to accounting year $year.")
                }
                band.weekday
            }
        }

}
