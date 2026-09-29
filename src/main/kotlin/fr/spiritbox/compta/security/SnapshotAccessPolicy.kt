package fr.spiritbox.compta.security

import fr.spiritbox.compta.openapi.model.AccountingYearSnapshot
import fr.spiritbox.compta.openapi.model.Settings
import fr.spiritbox.compta.openapi.model.SettingsView
import fr.spiritbox.compta.openapi.model.SettingsUpdate
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.BadRequestException

/** Returns only authorized data, with minimal names needed by attendance and group screens. */
@ApplicationScoped
class SnapshotAccessPolicy(private val user: AuthenticatedUser) {
    fun settingsView(settings: Settings): SettingsView {
        val financials = user.canAny("CONFIG_FINANCIALS", "BILLING_READ")
        return SettingsView(
            year = settings.year, version = settings.version, status = settings.status,
            reviewedAt = settings.reviewedAt, closedAt = settings.closedAt,
            teacherHourlyRate = settings.teacherHourlyRate.takeIf { financials },
            groupMembershipFee = settings.groupMembershipFee.takeIf { financials },
            individualCourseHours = settings.individualCourseHours, workshopHours = settings.workshopHours,
            schoolHolidayWeeks = settings.schoolHolidayWeeks, terms = settings.terms,
        )
    }

    fun filter(snapshot: AccountingYearSnapshot): AccountingYearSnapshot {
        val people = user.canAny("MUSICIENS_READ", "MUSICIENS_ARCHIVED", "PRESENCE_READ", "GROUPS_READ", "BILLING_READ")
        val musicians = snapshot.musicians.filter {
            people && if (it.active) user.canAny("MUSICIENS_READ", "PRESENCE_READ", "GROUPS_READ", "BILLING_READ") else user.can("MUSICIENS_ARCHIVED")
        }.map { musician ->
            val contactPermission = if (musician.active) "MUSICIENS_READ" else "MUSICIENS_ARCHIVED"
            musician.copy(email = musician.email.takeIf { user.can(contactPermission) })
        }
        val visibleIds = musicians.mapNotNull { it.id }.toSet()
        val courses = snapshot.individualCourses.filter {
            user.canAny("MUSICIENS_READ", "PRESENCE_READ", "BILLING_READ") && it.musicianId in visibleIds
        }
        val bands = snapshot.bands.filter { user.canAny("GROUPS_READ", "MUSICIENS_READ", "PRESENCE_READ", "BILLING_READ") }
            .map { it.copy(memberIds = it.memberIds.filter(visibleIds::contains)) }
        val targets = courses.mapNotNull { it.id }.toSet() + bands.mapNotNull { it.id }.toSet()
        return snapshot.copy(
            teachers = snapshot.teachers.takeIf { user.canAny("CONFIG_TEACHER", "MUSICIENS_READ", "PRESENCE_READ", "GROUPS_READ", "BILLING_READ") }.orEmpty(),
            musicians = musicians, individualCourses = courses, bands = bands,
            attendance = snapshot.attendance.filter { user.can("PRESENCE_READ") && it.entityId in targets },
            expenses = snapshot.expenses.takeIf { user.can("EXPENSES_READ") }.orEmpty(),
        )
    }

    fun authorizeUpdate(update: SettingsUpdate) {
        if (update.teacherHourlyRate != null || update.groupMembershipFee != null ||
            update.individualCourseHours != null || update.workshopHours != null) user.requirePermission("CONFIG_FINANCIALS")
        if (update.terms != null) user.requirePermission("CONFIG_TERMS")
        if (update.schoolHolidayWeeks != null) user.requirePermission("CONFIG_HOLIDAYS")
        if (update.status != null || update.reviewedAt != null || update.closedAt != null) {
            throw BadRequestException("Use the accounting year status endpoint for lifecycle changes.")
        }
    }
}
