package fr.spiritbox.compta.repositories

import fr.spiritbox.compta.domain.ExpenseEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class ExpenseRepository : PanacheRepositoryBase<ExpenseEntity, UUID> {
    fun listByYear(year: Int) = find(
        """
        from ExpenseEntity expense
        where expense.year = ?1
        order by expense.date desc, expense.label asc
        """.trimIndent(),
        year,
    ).list()
}
