package fr.spiritbox.compta.services

import fr.spiritbox.compta.Mapper
import fr.spiritbox.compta.domain.ExpenseCategory
import fr.spiritbox.compta.domain.ExpenseEntity
import fr.spiritbox.compta.openapi.model.Expense
import fr.spiritbox.compta.repositories.ExpenseRepository
import fr.spiritbox.compta.utils.PanacheCoroutine
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.NotFoundException
import java.math.BigDecimal
import java.util.UUID

@ApplicationScoped
class ExpenseService(
    private val expenseRepository: ExpenseRepository,
    private val panacheCoroutine: PanacheCoroutine,
    private val mapper: Mapper,
) {
    suspend fun listYearExpenses(year: Int): List<Expense> = panacheCoroutine.withSession {
        expenseRepository.listByYear(year)
            .awaitSuspending()
            .map { mapper.run { it.toDto() } }
    }

    suspend fun createExpense(year: Int, expense: Expense): Expense = panacheCoroutine.withTransaction {
        if (expense.id != null) {
            throw BadRequestException("Expense id must not be provided when creating an expense.")
        }
        val entity = ExpenseEntity()
        applyPayload(entity, expense.copy(year = year))
        expenseRepository.persist(entity).awaitSuspending()

        mapper.run { entity.toDto() }
    }

    suspend fun updateExpense(id: UUID, expense: Expense): Expense = panacheCoroutine.withTransaction {
        if (expense.id != null && expense.id != id) {
            throw BadRequestException("Path expense id $id does not match payload id ${expense.id}.")
        }
        val entity = getExpense(id)
        applyPayload(entity, expense)

        mapper.run { entity.toDto() }
    }

    suspend fun deleteExpense(id: UUID): Unit = panacheCoroutine.withTransaction {
        val deleted = expenseRepository.delete("id", id).awaitSuspending()
        if (deleted == 0L) {
            throw NotFoundException("Expense $id was not found.")
        }
    }

    private fun applyPayload(entity: ExpenseEntity, expense: Expense) {
        if (expense.amount < BigDecimal.ZERO) {
            throw BadRequestException("Expense amount must be positive or zero.")
        }
        entity.year = expense.year
        entity.date = expense.date
        entity.category = ExpenseCategory.valueOf(expense.category.name)
        entity.label = expense.label.trim()
        entity.amount = expense.amount
        entity.notes = expense.notes?.trim()?.takeIf { it.isNotEmpty() }
    }

    private suspend fun getExpense(id: UUID): ExpenseEntity =
        expenseRepository.find("id", id).firstResult().awaitSuspending()
            ?: throw NotFoundException("Expense $id was not found.")
}
