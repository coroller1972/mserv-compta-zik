package fr.spiritbox.compta.services

import fr.spiritbox.compta.domain.AccountingYearSettingsEntity
import fr.spiritbox.compta.domain.AccountingYearStatus
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.ClientErrorException
import jakarta.ws.rs.core.Response

@ApplicationScoped
class AccountingYearLifecycleService {
    fun requireStructureMutable(settings: AccountingYearSettingsEntity) {
        if (settings.status != AccountingYearStatus.OPEN) {
            locked(settings, "structural configuration")
        }
    }

    fun requireAttendanceMutable(settings: AccountingYearSettingsEntity) {
        if (settings.status == AccountingYearStatus.CLOSED) {
            locked(settings, "attendance")
        }
    }

    fun requireExpensesMutable(settings: AccountingYearSettingsEntity) {
        if (settings.status == AccountingYearStatus.CLOSED) {
            locked(settings, "expenses")
        }
    }

    fun requireDocumentsMutable(settings: AccountingYearSettingsEntity) {
        if (settings.status == AccountingYearStatus.CLOSED) {
            locked(settings, "documents")
        }
    }

    fun validateTransition(current: AccountingYearStatus, target: AccountingYearStatus) {
        if (current == target) return
        val allowed = when (current) {
            AccountingYearStatus.OPEN -> target == AccountingYearStatus.REVIEWED
            AccountingYearStatus.REVIEWED -> target == AccountingYearStatus.OPEN || target == AccountingYearStatus.CLOSED
            AccountingYearStatus.CLOSED -> false
        }
        if (!allowed) {
            throw ClientErrorException(
                "Accounting year transition $current -> $target is not allowed.",
                Response.Status.CONFLICT,
            )
        }
    }

    private fun locked(settings: AccountingYearSettingsEntity, resource: String): Nothing {
        throw ClientErrorException(
            "Accounting year ${settings.year} is ${settings.status}; $resource is locked.",
            Response.Status.CONFLICT,
        )
    }
}
