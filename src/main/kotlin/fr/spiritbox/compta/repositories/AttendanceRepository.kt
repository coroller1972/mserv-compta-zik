package fr.spiritbox.compta.repositories

import fr.spiritbox.compta.domain.AttendanceEntity
import fr.spiritbox.compta.domain.AttendanceEntityType
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class AttendanceRepository : PanacheRepositoryBase<AttendanceEntity, UUID> {
    fun listByAccountingYear(year: Int) = find(
        """
        select attendance
        from AttendanceEntity attendance
        join fetch attendance.term term
        join term.settings settings
        where settings.year = ?1
        """.trimIndent(),
        year,
    ).list()

    fun listByAccountingYearAndTerm(year: Int, termId: UUID) = find(
        """
        select attendance
        from AttendanceEntity attendance
        join fetch attendance.term term
        join term.settings settings
        where settings.year = ?1
          and term.id = ?2
        """.trimIndent(),
        year,
        termId,
    ).list()

    fun findByNaturalKey(
        termId: UUID,
        week: Int,
        entityType: AttendanceEntityType,
        entityId: UUID,
    ) = find(
        """
        select attendance
        from AttendanceEntity attendance
        join fetch attendance.term term
        where term.id = ?1
          and attendance.week = ?2
          and attendance.entityType = ?3
          and attendance.entityId = ?4
        """.trimIndent(),
        termId,
        week,
        entityType,
        entityId,
    ).firstResult()
}
