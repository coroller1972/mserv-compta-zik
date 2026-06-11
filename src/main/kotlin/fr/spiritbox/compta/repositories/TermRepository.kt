package fr.spiritbox.compta.repositories

import fr.spiritbox.compta.domain.TermEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import java.util.*

@ApplicationScoped
class TermRepository : PanacheRepositoryBase<TermEntity, UUID> {
    fun findByYearAndId(year: Int, termId: UUID) = find(
        """
        select term
        from TermEntity term
        join fetch term.settings settings
        where settings.year = ?1
          and term.id = ?2
        """.trimIndent(),
        year,
        termId,
    ).firstResult()
}
