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

    fun listWithMembersByYear(year: Int) = find(
        """
        select distinct band
        from BandEntity band
        join fetch band.settings settings
        left join fetch band.teacher
        left join fetch band.members member
        left join fetch member.musician
        where settings.year = ?1
        """.trimIndent(),
        year,
    ).list()

    /** Groupes placés (jour et heure renseignés) un jour donné. */
    fun listPlacedByDay(year: Int, weekday: String) = find(
        """
        select band
        from BandEntity band
        join fetch band.settings settings
        where settings.year = ?1
        and band.weekday = ?2
        and band.startTime is not null
        """.trimIndent(),
        year,
        weekday,
    ).list()

    fun findByIdWithMembers(id: UUID) = find(
        """
        select distinct band
        from BandEntity band
        join fetch band.settings
        left join fetch band.teacher
        left join fetch band.members member
        left join fetch member.musician
        where band.id = ?1
        """.trimIndent(),
        id,
    ).firstResult()
}
