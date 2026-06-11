package fr.spiritbox.compta.repositories

import fr.spiritbox.compta.domain.GeneratedDocumentEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import java.util.*

@ApplicationScoped
class GeneratedDocumentRepository : PanacheRepositoryBase<GeneratedDocumentEntity, UUID> {
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
