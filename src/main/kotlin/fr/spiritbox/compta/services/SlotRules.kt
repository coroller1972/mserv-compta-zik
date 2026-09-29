package fr.spiritbox.compta.services

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalTime
import java.util.UUID

/**
 * Règles d'allocation de la salle : plages horaires, grille de départ et chevauchements.
 *
 * Une seule salle : sur un même jour, deux occupations ne se chevauchent jamais, sauf deux cours
 * individuels qui démarrent ensemble lorsque l'un d'eux est un créneau partagé.
 */
object SlotRules {
    val windows: List<ClosedRange<LocalTime>> = listOf(
        LocalTime.of(11, 30)..LocalTime.of(14, 0),
        LocalTime.of(16, 30)..LocalTime.of(20, 0),
    )
    const val STEP_MINUTES = 15

    /** Durées possibles d'un groupe (les ateliers suivent workshopHours). */
    val GROUP_DURATIONS = setOf(75, 90)

    enum class Kind { COURSE, WORKSHOP, GROUP }

    data class Occupation(
        val id: UUID?,
        val kind: Kind,
        val label: String,
        val weekday: String,
        val start: LocalTime,
        val minutes: Int,
        val sharedSlot: Boolean = false,
    ) {
        val end: LocalTime get() = start.plusMinutes(minutes.toLong())

        fun describe() = "$label ($weekday $start-$end)"
    }

    fun minutes(hours: BigDecimal): Int = hours.multiply(BigDecimal(60)).setScale(0, RoundingMode.HALF_UP).toInt()

    /** Motif du refus si l'occupation ne tient pas sur la grille ou dans une plage, sinon null. */
    fun placementError(candidate: Occupation): String? {
        val start = candidate.start
        if (start.second != 0 || start.nano != 0 || (start.hour * 60 + start.minute) % STEP_MINUTES != 0) {
            return "Start time ${candidate.start} must fall on a $STEP_MINUTES-minute step."
        }
        val window = windows.firstOrNull { start >= it.start && start < it.endInclusive }
            ?: return "Start time ${candidate.start} is outside the opening windows ${windowsLabel()}."
        // Une fin après minuit reviendrait avant le début : on la traite comme hors plage.
        if (candidate.end > window.endInclusive || candidate.end < start) {
            return "${candidate.describe()} ends after ${window.endInclusive}."
        }
        return null
    }

    /** Occupations déjà en place que le candidat chevaucherait. */
    fun conflicts(candidate: Occupation, others: List<Occupation>): List<Occupation> = others
        .filter { it.weekday == candidate.weekday && it.id != candidate.id }
        .filter { other -> candidate.start < other.end && other.start < candidate.end }
        .filterNot { other -> sharesCourseSlot(candidate, other) }

    /** Tous les chevauchements d'un ensemble d'occupations (contrôle après un changement de durées). */
    fun allConflicts(occupations: List<Occupation>): List<Pair<Occupation, Occupation>> =
        occupations.flatMapIndexed { index, first ->
            conflicts(first, occupations.drop(index + 1)).map { first to it }
        }

    private fun sharesCourseSlot(first: Occupation, second: Occupation) =
        first.kind == Kind.COURSE && second.kind == Kind.COURSE &&
            first.start == second.start && (first.sharedSlot || second.sharedSlot)

    private fun windowsLabel() = windows.joinToString(", ") { "${it.start}-${it.endInclusive}" }
}
