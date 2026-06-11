package fr.spiritbox.compta.repositories

import fr.spiritbox.compta.domain.AccountingYearSettingsEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class AccountingYearSettingsRepository : PanacheRepositoryBase<AccountingYearSettingsEntity, UUID> {
    fun findByYearWithTerms(year: Int) = find(
        """
        select distinct settings
        from AccountingYearSettingsEntity settings
        left join fetch settings.terms
        where settings.year = ?1
        """.trimIndent(),
        year,
    ).firstResult()
}
