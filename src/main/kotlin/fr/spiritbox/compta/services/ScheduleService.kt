package fr.spiritbox.compta.services

import fr.spiritbox.compta.domain.AccountingYearSettingsEntity
import fr.spiritbox.compta.domain.BandEntity
import fr.spiritbox.compta.domain.BandType
import fr.spiritbox.compta.domain.IndividualCourseEntity
import fr.spiritbox.compta.repositories.BandRepository
import fr.spiritbox.compta.repositories.IndividualCourseRepository
import fr.spiritbox.compta.services.SlotRules.Kind
import fr.spiritbox.compta.services.SlotRules.Occupation
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.ClientErrorException
import jakarta.ws.rs.core.Response
import java.time.DateTimeException
import java.time.LocalTime
import java.util.UUID

/** Applique [SlotRules] aux cours actifs et aux groupes placés d'une année. */
@ApplicationScoped
class ScheduleService(
    private val individualCourseRepository: IndividualCourseRepository,
    private val bandRepository: BandRepository,
) {
    fun courseOccupation(
        settings: AccountingYearSettingsEntity,
        id: UUID?,
        label: String,
        weekday: String,
        startTime: LocalTime,
        sharedSlot: Boolean,
    ) = Occupation(id, Kind.COURSE, label, weekday, startTime, SlotRules.minutes(settings.individualCourseHours), sharedSlot)

    fun bandOccupation(
        settings: AccountingYearSettingsEntity,
        id: UUID?,
        name: String,
        type: BandType,
        weekday: String,
        startTime: LocalTime,
        durationMinutes: Int?,
    ) = Occupation(
        id,
        if (type == BandType.WORKSHOP) Kind.WORKSHOP else Kind.GROUP,
        name,
        weekday,
        startTime,
        if (type == BandType.WORKSHOP) SlotRules.minutes(settings.workshopHours) else durationMinutes ?: DEFAULT_GROUP_MINUTES,
    )

    fun parseStartTime(value: String): LocalTime = try {
        LocalTime.parse(value)
    } catch (exception: DateTimeException) {
        throw BadRequestException("startTime must use ISO local time format, for example 17:00.", exception)
    }

    /** Refuse (400) une occupation hors grille, hors plage ou qui chevauche une occupation existante. */
    suspend fun requireAvailable(settings: AccountingYearSettingsEntity, candidate: Occupation) {
        SlotRules.placementError(candidate)?.let { throw BadRequestException(it) }
        val conflicts = SlotRules.conflicts(candidate, occupationsOfDay(settings, candidate.weekday))
        if (conflicts.isNotEmpty()) {
            throw BadRequestException("Slot ${candidate.describe()} overlaps ${conflicts.joinToString { it.describe() }}.")
        }
    }

    /** Refuse (409) des durées qui feraient se chevaucher des occupations déjà placées. */
    suspend fun requireNoOverlaps(settings: AccountingYearSettingsEntity) {
        val year = settings.year
        val occupations = individualCourseRepository.listWithRelationsByYear(year).awaitSuspending()
            .filter { it.active && it.musician.active }
            .map { it.toOccupation(settings) } +
            bandRepository.listWithMembersByYear(year).awaitSuspending().mapNotNull { it.toOccupation(settings) }
        val placementErrors = occupations.mapNotNull { SlotRules.placementError(it) }
        val conflicts = SlotRules.allConflicts(occupations)
        if (placementErrors.isNotEmpty() || conflicts.isNotEmpty()) {
            val details = placementErrors + conflicts.map { (first, second) -> "${first.describe()} overlaps ${second.describe()}" }
            throw ClientErrorException(
                "These durations do not fit the current schedule: ${details.take(3).joinToString("; ")}.",
                Response.Status.CONFLICT,
            )
        }
    }

    private suspend fun occupationsOfDay(settings: AccountingYearSettingsEntity, weekday: String): List<Occupation> =
        individualCourseRepository.listOccupyingByDay(settings.year, weekday).awaitSuspending().map { it.toOccupation(settings) } +
            bandRepository.listPlacedByDay(settings.year, weekday).awaitSuspending().mapNotNull { it.toOccupation(settings) }

    private fun IndividualCourseEntity.toOccupation(settings: AccountingYearSettingsEntity) =
        courseOccupation(settings, id, "${musician.firstName} ${musician.lastName}".trim(), weekday, startTime, sharedSlot)

    private fun BandEntity.toOccupation(settings: AccountingYearSettingsEntity): Occupation? {
        val day = weekday ?: return null
        val start = startTime ?: return null
        return bandOccupation(settings, id, name, type, day, start, durationMinutes)
    }
}
