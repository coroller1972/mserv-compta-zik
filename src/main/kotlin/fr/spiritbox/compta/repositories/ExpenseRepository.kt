package fr.spiritbox.compta.repositories

import fr.spiritbox.compta.domain.ExpenseEntity
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheRepositoryBase
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID

@ApplicationScoped
class ExpenseRepository : PanacheRepositoryBase<ExpenseEntity, UUID> {
}
