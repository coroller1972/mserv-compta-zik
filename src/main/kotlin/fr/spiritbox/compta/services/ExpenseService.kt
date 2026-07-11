package fr.spiritbox.compta.services

import fr.spiritbox.compta.Mapper
import fr.spiritbox.compta.domain.ExpenseCategory
import fr.spiritbox.compta.domain.ExpenseEntity
import fr.spiritbox.compta.openapi.model.Expense
import fr.spiritbox.compta.repositories.ExpenseRepository
import fr.spiritbox.compta.repositories.AccountingYearSettingsRepository
import fr.spiritbox.compta.utils.PanacheCoroutine
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.NotFoundException
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

@ApplicationScoped
class ExpenseService(
    private val expenseRepository: ExpenseRepository,
    private val panacheCoroutine: PanacheCoroutine,
    private val mapper: Mapper,
    private val accountingYearSettingsRepository: AccountingYearSettingsRepository,
    private val lifecycleService: AccountingYearLifecycleService,
    private val auditService: AuditService,
) {
    suspend fun listExpenses(year: Int): List<Expense> = panacheCoroutine.withSession {
        expenseRepository.find("year = ?1 order by date desc, label", year)
            .list()
            .awaitSuspending()
            .map { mapper.run { it.toDto() } }
    }

    suspend fun createExpense(year: Int, expense: Expense): Expense = panacheCoroutine.withTransaction {
        if (expense.id != null) {
            throw BadRequestException("Expense id must not be provided when creating an expense.")
        }
        if (expense.year != year) {
            throw BadRequestException("Path year $year does not match payload year ${expense.year}.")
        }
        lifecycleService.requireExpensesMutable(getSettings(year))

        val entity = ExpenseEntity()
        applyPayload(entity, expense)
        expenseRepository.persist(entity).awaitSuspending()
        auditService.record("EXPENSE", entity.id, "CREATED", mapOf("year" to year, "amount" to entity.amount))
        expenseRepository.flush()

        mapper.run { entity.toDto() }
    }

    suspend fun updateExpense(id: UUID, expense: Expense): Expense = panacheCoroutine.withTransaction {
        if (expense.id != null && expense.id != id) {
            throw BadRequestException("Path expense id $id does not match payload id ${expense.id}.")
        }

        val entity = getExpense(id)
        OptimisticLocking.requireVersion(expense.version, entity.version, "Expense")
        lifecycleService.requireExpensesMutable(getSettings(entity.year))
        if (entity.year != expense.year) {
            throw BadRequestException("Expense year ${entity.year} cannot be changed to ${expense.year}.")
        }
        applyPayload(entity, expense)
        auditService.record("EXPENSE", entity.id, "UPDATED", mapOf("year" to entity.year, "amount" to entity.amount))
        expenseRepository.flush()

        mapper.run { entity.toDto() }.copy(version = entity.version + 1)
    }

    suspend fun deleteExpense(id: UUID): Unit = panacheCoroutine.withTransaction {
        val entity = getExpense(id)
        lifecycleService.requireExpensesMutable(getSettings(entity.year))
        auditService.record("EXPENSE", entity.id, "DELETED", mapOf("year" to entity.year, "amount" to entity.amount))
        val deleted = expenseRepository.delete("id", id).awaitSuspending()
        if (deleted == 0L) {
            throw NotFoundException("Expense $id was not found.")
        }
    }

    private fun applyPayload(entity: ExpenseEntity, expense: Expense) {
        val label = expense.label.trim()
        if (label.isBlank()) {
            throw BadRequestException("Expense label is required.")
        }
        if (expense.year !in 2000..2100) {
            throw BadRequestException("Expense year must be between 2000 and 2100.")
        }
        if (expense.amount < BigDecimal.ZERO) {
            throw BadRequestException("Expense amount must be greater than or equal to zero.")
        }

        entity.year = expense.year
        entity.date = expense.date
        entity.category = ExpenseCategory.valueOf(expense.category.name)
        entity.label = label
        entity.amount = expense.amount.setScale(2, RoundingMode.HALF_UP)
        entity.notes = expense.notes?.trim()?.takeIf { it.isNotBlank() }
    }

    private suspend fun getExpense(id: UUID): ExpenseEntity =
        expenseRepository.find("id", id).firstResult().awaitSuspending()
            ?: throw NotFoundException("Expense $id was not found.")

    private suspend fun getSettings(year: Int) = accountingYearSettingsRepository.findByYearWithTerms(year)
        .awaitSuspending()
        ?: throw NotFoundException("Accounting year $year was not found.")
}
