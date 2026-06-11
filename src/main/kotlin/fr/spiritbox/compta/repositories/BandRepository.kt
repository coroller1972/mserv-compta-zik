package fr.spiritbox.compta.repositories

import fr.spiritbox.compta.domain.BandEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import java.util.*


@ApplicationScoped
class BandRepository : PanacheRepositoryBase<BandEntity, UUID> {
    fun listWithMembers() = find(
        """
        select distinct band
        from BandEntity band
        left join fetch band.teacher
        left join fetch band.members member
        left join fetch member.musician
        """.trimIndent(),
    ).list()

    fun findByIdWithMembers(id: UUID) = find(
        """
        select distinct band
        from BandEntity band
        left join fetch band.teacher
        left join fetch band.members member
        left join fetch member.musician
        where band.id = ?1
        """.trimIndent(),
        id,
    ).firstResult()
}
