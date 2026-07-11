package fr.spiritbox.compta.services

import jakarta.ws.rs.BadRequestException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

object SessionDateCalculator {
    fun scheduledDate(year: Int, week: Int, weekday: String?): LocalDate {
        requireWeek(week)
        val firstDay = LocalDate.of(year, 1, 1)
        if (week == 1) return firstDay
        val firstMonday = firstDay.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
        return firstMonday.plusWeeks((week - 2).toLong()).plusDays(weekdayOffset(weekday).toLong())
    }

    fun accountingWeek(year: Int, date: LocalDate): Int {
        if (date.year != year) throw BadRequestException("Session date $date does not belong to accounting year $year.")
        val firstDay = LocalDate.of(year, 1, 1)
        val firstMonday = firstDay.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
        if (date.isBefore(firstMonday)) return 1
        return 2 + (ChronoUnit.DAYS.between(firstMonday, date) / 7).toInt()
    }

    private fun weekdayOffset(weekday: String?) = when (weekday) {
        "Lundi" -> 0
        "Mardi" -> 1
        "Mercredi" -> 2
        "Jeudi" -> 3
        "Vendredi" -> 4
        "Samedi" -> 5
        "Dimanche" -> 6
        else -> 0
    }

    private fun requireWeek(week: Int) {
        if (week !in 1..53) throw BadRequestException("Week must be between 1 and 53.")
    }
}
