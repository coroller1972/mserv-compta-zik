package fr.spiritbox.compta.resources

import fr.spiritbox.compta.openapi.api.ExpensesApi
import fr.spiritbox.compta.openapi.model.Expense
import fr.spiritbox.compta.services.ExpenseService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.PUT
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.UUID

@ApplicationScoped
@Path("/")
@Tag(name = "Expenses")
class ExpenseResource(
    private val expenseService: ExpenseService,
) : ExpensesApi {
    @DELETE
    @Path("/expenses/{id}")
    override suspend fun deleteExpense(@PathParam("id") id: UUID): Response =
        expenseService.deleteExpense(id).let { Response.noContent().build() }

    @PUT
    @Path("/expenses/{id}")
    @Consumes("application/json")
    @Produces("application/json")
    override suspend fun updateExpense(
        @PathParam("id") id: UUID,
        expense: Expense
    ): Response =
        Response.ok(expenseService.updateExpense(id, expense)).build()
}
