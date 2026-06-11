package fr.spiritbox.compta.resources

import fr.spiritbox.compta.openapi.api.AccountingYearsApi
import fr.spiritbox.compta.openapi.model.Settings
import fr.spiritbox.compta.services.AccountingYearService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.openapi.annotations.tags.Tag
import java.util.*

@ApplicationScoped
@Tag(name = "Accounting years")
class AccountingYearResource(
    private val accountingYearService: AccountingYearService
) : AccountingYearsApi {
    override suspend fun getAccountingYearSnapshot(year: Int): Response =
        Response.ok(accountingYearService.getSnapshot(year)).build()

    override suspend fun getTermBilling(year: Int, termId: UUID): Response {
        return Response.ok(accountingYearService.getTermBilling(year, termId)).build()
    }

    override suspend fun listTermAttendance(year: Int, termId: UUID): Response {
        return Response.ok(accountingYearService.listTermAttendance(year, termId)).build()
    }

    override suspend fun prepareStudentInvoices(year: Int, termId: UUID): Response {
        return Response.accepted(accountingYearService.prepareStudentInvoices(year, termId)).build()
    }

    override suspend fun prepareStudentInvoiceSummary(year: Int, termId: UUID): Response {
        return Response.accepted(accountingYearService.prepareStudentInvoiceSummary(year, termId)).build()
    }

    override suspend fun prepareTeacherInvoiceRequests(
        year: Int,
        termId: UUID
    ): Response {
        return Response.accepted(accountingYearService.prepareTeacherInvoiceRequests(year, termId)).build()
    }

    override suspend fun updateSettings(
        year: Int,
        settings: Settings
    ): Response {
        return Response.ok(accountingYearService.updateSettings(year, settings)).build()
    }


}
