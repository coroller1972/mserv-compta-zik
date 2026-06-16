package fr.spiritbox.compta.resources

import fr.spiritbox.compta.openapi.api.ExpensesApi
import fr.spiritbox.compta.openapi.model.Expense
import fr.spiritbox.compta.services.ExpenseService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

@ApplicationScoped
@Tag(name = "Expenses")
class ExpenseResource(
    private val expenseService: ExpenseService,
) : ExpensesApi {
    override suspend fun listYearExpenses(year: Int): Response =
        Response.ok(expenseService.listYearExpenses(year)).build()

    override suspend fun createExpense(year: Int, expense: Expense): Response =
        Response.status(Response.Status.CREATED)
            .entity(expenseService.createExpense(year, expense))
            .build()

    override suspend fun updateExpense(id: UUID, expense: Expense): Response =
        Response.ok(expenseService.updateExpense(id, expense)).build()

    override suspend fun deleteExpense(id: UUID): Response =
        expenseService.deleteExpense(id).let { Response.noContent().build() }
}
