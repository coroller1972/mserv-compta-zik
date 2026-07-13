package fr.spiritbox.compta.resources

import fr.spiritbox.compta.openapi.api.AccountingYearsApi
import fr.spiritbox.compta.openapi.model.Expense
import fr.spiritbox.compta.openapi.model.AccountingYearStatusUpdate
import fr.spiritbox.compta.openapi.model.Settings
import fr.spiritbox.compta.services.AccountingYearService
import fr.spiritbox.compta.services.ExpenseService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.*

@ApplicationScoped
@Tag(name = "Accounting years")
class AccountingYearResource(
    private val accountingYearService: AccountingYearService,
    private val expenseService: ExpenseService,
) : AccountingYearsApi {
    override suspend fun updateAccountingYearStatus(year: Int, accountingYearStatusUpdate: AccountingYearStatusUpdate): Response =
        Response.ok(accountingYearService.updateStatus(year, accountingYearStatusUpdate)).build()

    override suspend fun copyAccountingYearConfiguration(year: Int, sourceYear: Int): Response =
        Response.status(Response.Status.CREATED)
            .entity(accountingYearService.copyConfiguration(year, sourceYear))
            .build()

    override suspend fun createExpense(year: Int, expense: Expense): Response =
        Response.status(Response.Status.CREATED)
            .entity(expenseService.createExpense(year, expense))
            .build()

    override suspend fun getAccountingYearSnapshot(year: Int): Response =
        Response.ok(accountingYearService.getSnapshot(year)).build()

    override suspend fun getTermBilling(year: Int, termId: UUID): Response {
        return Response.ok(accountingYearService.getTermBilling(year, termId)).build()
    }

    override suspend fun listTermAttendance(year: Int, termId: UUID): Response {
        return Response.ok(accountingYearService.listTermAttendance(year, termId)).build()
    }

    override suspend fun listTermDocuments(year: Int, termId: UUID): Response =
        Response.ok(accountingYearService.listTermDocuments(year, termId)).build()

    override suspend fun listExpenses(year: Int): Response =
        Response.ok(expenseService.listExpenses(year)).build()

    override suspend fun prepareStudentInvoices(year: Int, termId: UUID): Response {
        return Response.accepted(accountingYearService.prepareStudentInvoices(year, termId)).build()
    }

    override suspend fun finalizeStudentInvoices(year: Int, termId: UUID): Response =
        Response.ok(accountingYearService.finalizeStudentInvoices(year, termId)).build()

    override suspend fun prepareStudentInvoiceSummary(year: Int, termId: UUID): Response {
        return Response.accepted(accountingYearService.prepareStudentInvoiceSummary(year, termId)).build()
    }

    override suspend fun prepareTeacherInvoiceRequests(
        year: Int,
        termId: UUID
    ): Response {
        return Response.accepted(accountingYearService.prepareTeacherInvoiceRequests(year, termId)).build()
    }

    override suspend fun prepareTeacherInvoiceRequest(year: Int, termId: UUID, teacherId: UUID): Response =
        Response.accepted(accountingYearService.prepareTeacherInvoiceRequest(year, termId, teacherId)).build()

    override suspend fun finalizeTeacherInvoiceRequests(year: Int, termId: UUID): Response =
        Response.ok(accountingYearService.finalizeTeacherInvoiceRequests(year, termId)).build()

    override suspend fun finalizeTeacherInvoiceRequest(year: Int, termId: UUID, teacherId: UUID): Response =
        Response.ok(accountingYearService.finalizeTeacherInvoiceRequest(year, termId, teacherId)).build()

    override suspend fun updateSettings(
        year: Int,
        settings: Settings
    ): Response {
        return Response.ok(accountingYearService.updateSettings(year, settings)).build()
    }


}
