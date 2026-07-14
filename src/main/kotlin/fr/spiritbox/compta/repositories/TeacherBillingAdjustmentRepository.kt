package fr.spiritbox.compta.repositories

import fr.spiritbox.compta.domain.TeacherBillingAdjustmentEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class TeacherBillingAdjustmentRepository : PanacheRepositoryBase<TeacherBillingAdjustmentEntity, UUID> {
    fun listPendingByTeacher(teacherId: UUID) = find(
        "select a from TeacherBillingAdjustmentEntity a join fetch a.sourceDocument where a.teacher.id = ?1 and a.appliedDocument is null order by a.createdAt", teacherId
    ).list()
    fun listAppliedByDocument(documentId: UUID) = find(
        "select a from TeacherBillingAdjustmentEntity a join fetch a.sourceDocument where a.appliedDocument.id = ?1", documentId
    ).list()
    fun findByIdWithRelations(id: UUID) = find(
        "select a from TeacherBillingAdjustmentEntity a join fetch a.teacher join fetch a.sourceDocument left join fetch a.appliedDocument where a.id = ?1", id
    ).firstResult()
}
