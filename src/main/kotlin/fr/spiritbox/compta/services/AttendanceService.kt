package fr.spiritbox.compta.services

import fr.spiritbox.compta.Mapper
import fr.spiritbox.compta.domain.AttendanceEntity
import fr.spiritbox.compta.domain.AttendanceEntityType
import fr.spiritbox.compta.openapi.model.Attendance
import fr.spiritbox.compta.repositories.AttendanceRepository
import fr.spiritbox.compta.repositories.TermRepository
import fr.spiritbox.compta.utils.PanacheCoroutine
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.NotFoundException
import java.util.*

@ApplicationScoped
class AttendanceService(
    private val attendanceRepository: AttendanceRepository,
    private val termRepository: TermRepository,
    private val panacheCoroutine: PanacheCoroutine,
    private val mapper: Mapper,
) {

    suspend fun upsertAttendance(attendance: Attendance): Attendance = panacheCoroutine.withTransaction {
        val entityType = AttendanceEntityType.valueOf(attendance.entityType.name)
        val entity = attendanceRepository.findByNaturalKey(
            termId = attendance.termId,
            week = attendance.week,
            entityType = entityType,
            entityId = attendance.entityId,
        ).awaitSuspending() ?: AttendanceEntity().apply {
            term = getTerm(attendance.termId)
            week = attendance.week
            this.entityType = entityType
            entityId = attendance.entityId
            attendanceRepository.persist(this).awaitSuspending()
        }

        entity.present = attendance.present

        mapper.run { entity.toDto() }
    }

    private suspend fun getTerm(termId: UUID) = termRepository.find("id", termId).firstResult()
        .awaitSuspending()
        ?: throw NotFoundException("Term $termId was not found.")

}
