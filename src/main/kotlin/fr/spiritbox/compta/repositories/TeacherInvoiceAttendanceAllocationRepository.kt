package fr.spiritbox.compta.repositories

import fr.spiritbox.compta.domain.AttendanceEntityType
import fr.spiritbox.compta.domain.TeacherInvoiceAttendanceAllocationEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class TeacherInvoiceAttendanceAllocationRepository : PanacheRepositoryBase<TeacherInvoiceAttendanceAllocationEntity, UUID> {
    fun listActiveByAttendanceIds(ids: Collection<UUID>) = find(
        "select a from TeacherInvoiceAttendanceAllocationEntity a join fetch a.document where a.releasedAt is null and a.attendance.id in ?1", ids
    ).list()
    /** Allocations actives avec la demande et son professeur (correction après facturation). */
    fun listActiveWithTeacherByAttendanceIds(ids: Collection<UUID>) = find(
        "select a from TeacherInvoiceAttendanceAllocationEntity a join fetch a.document d left join fetch d.teacher where a.releasedAt is null and a.attendance.id in ?1", ids
    ).list()
    fun findActiveByAttendanceId(id: UUID) = find(
        "select a from TeacherInvoiceAttendanceAllocationEntity a join fetch a.document where a.releasedAt is null and a.attendance.id = ?1", id
    ).firstResult()
    fun listActiveByDocument(id: UUID) = find(
        "select a from TeacherInvoiceAttendanceAllocationEntity a join fetch a.attendance where a.releasedAt is null and a.document.id = ?1", id
    ).list()
    fun countActiveByTarget(type: AttendanceEntityType, targetId: UUID) = count(
        "releasedAt is null and attendance.entityType = ?1 and attendance.entityId = ?2", type, targetId
    )
}
