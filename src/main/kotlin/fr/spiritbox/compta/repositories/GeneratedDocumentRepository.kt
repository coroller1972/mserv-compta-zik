package fr.spiritbox.compta.repositories

import fr.spiritbox.compta.domain.GeneratedDocumentEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import java.util.*

@ApplicationScoped
class GeneratedDocumentRepository : PanacheRepositoryBase<GeneratedDocumentEntity, UUID> {
    fun listByTerm(termId: UUID) = find(
        """
        select document from GeneratedDocumentEntity document
        join fetch document.term term
        left join fetch document.musician
        left join fetch document.teacher
        left join fetch document.parentDocument
        where term.id = ?1 order by document.createdAt
        """.trimIndent(), termId,
    ).list()

    fun findByIdWithRelations(id: UUID) = find(
        """
        select document from GeneratedDocumentEntity document
        join fetch document.term term
        join fetch term.settings
        left join fetch document.musician
        left join fetch document.teacher
        left join fetch document.parentDocument
        where document.id = ?1
        """.trimIndent(), id,
    ).firstResult()

    fun countByYearAndStatus(year: Int, status: fr.spiritbox.compta.domain.GeneratedDocumentStatus) = count(
        "term.settings.year = ?1 and status = ?2",
        year,
        status,
    )

    fun listByTermAndType(termId: UUID, type: fr.spiritbox.compta.domain.GeneratedDocumentType) = find(
        """
        select document
        from GeneratedDocumentEntity document
        join fetch document.term term
        left join fetch document.musician
        left join fetch document.teacher
        where term.id = ?1
          and document.type = ?2
        """.trimIndent(),
        termId,
        type,
    ).list()
}
