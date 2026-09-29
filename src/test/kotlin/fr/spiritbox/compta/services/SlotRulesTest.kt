package fr.spiritbox.compta.services

import fr.spiritbox.compta.services.SlotRules.Kind
import fr.spiritbox.compta.services.SlotRules.Occupation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalTime
import java.util.UUID

class SlotRulesTest {
    private fun at(kind: Kind, start: String, minutes: Int, weekday: String = "Mardi", sharedSlot: Boolean = false) =
        Occupation(UUID.randomUUID(), kind, "$kind $start", weekday, LocalTime.parse(start), minutes, sharedSlot)

    private fun course(start: String, shared: Boolean = false, weekday: String = "Mardi") = at(Kind.COURSE, start, 30, weekday, shared)
    private fun workshop(start: String) = at(Kind.WORKSHOP, start, 75)
    private fun group(start: String) = at(Kind.GROUP, start, 90)

    @Test
    fun `durations come from the year settings in hours`() {
        assertEquals(30, SlotRules.minutes(BigDecimal("0.50")))
        assertEquals(75, SlotRules.minutes(BigDecimal("1.25")))
        assertEquals(90, SlotRules.minutes(BigDecimal("1.50")))
    }

    @Test
    fun `placements must start on the 30-minute grid inside an opening window`() {
        assertNull(SlotRules.placementError(course("11:30")))
        assertNull(SlotRules.placementError(course("13:30")))
        assertNull(SlotRules.placementError(group("18:30")), "a group may end exactly at 20:00")
        assertNull(SlotRules.placementError(workshop("16:30")))

        assertNull(SlotRules.placementError(course("17:15")), "quarter hours are allowed")
        assertNotNull(SlotRules.placementError(course("17:10")), "off the 15-minute grid")
        assertNotNull(SlotRules.placementError(course("14:30")), "lunch break between the windows")
        assertNotNull(SlotRules.placementError(course("20:00")), "evening window ends at 20:00")
        assertNotNull(SlotRules.placementError(group("13:00")), "would end at 14:30, after the midday window")
        assertNotNull(SlotRules.placementError(workshop("19:00")), "would end at 20:15")
    }

    @Test
    fun `two groups of 1h15 fill the midday window back to back`() {
        val first = at(Kind.GROUP, "11:30", 75)
        val second = at(Kind.GROUP, "12:45", 75)
        assertNull(SlotRules.placementError(first))
        assertNull(SlotRules.placementError(second), "12:45-14:00 ends with the window")
        assertEquals(emptyList<Occupation>(), SlotRules.conflicts(second, listOf(first)))
        assertNotNull(SlotRules.placementError(at(Kind.GROUP, "12:45", 90)), "a 1h30 group would end at 14:15")
    }

    @Test
    fun `nothing overlaps in the room on the same day`() {
        val existing = listOf(course("17:00"), group("17:30"))

        assertEquals(existing, SlotRules.conflicts(group("16:30"), existing), "16:30-18:00 covers both")
        assertEquals(listOf(existing[0]), SlotRules.conflicts(course("17:00"), existing))
        assertEquals(listOf(existing[1]), SlotRules.conflicts(course("18:30"), existing))
        assertEquals(emptyList<Occupation>(), SlotRules.conflicts(course("16:30"), existing), "ends when the next one starts")
        assertEquals(emptyList<Occupation>(), SlotRules.conflicts(course("19:00"), existing), "starts when the group ends")
        assertEquals(emptyList<Occupation>(), SlotRules.conflicts(course("17:30", weekday = "Jeudi"), existing), "another day")
    }

    @Test
    fun `only two courses starting together can share a slot`() {
        val plain = course("17:00")
        assertEquals(emptyList<Occupation>(), SlotRules.conflicts(course("17:00", shared = true), listOf(plain)))
        assertEquals(emptyList<Occupation>(), SlotRules.conflicts(plain, listOf(course("17:00", shared = true))))
        assertEquals(listOf(plain), SlotRules.conflicts(course("17:00"), listOf(plain)), "neither is shared")
        assertEquals(1, SlotRules.conflicts(at(Kind.GROUP, "17:00", 90, sharedSlot = true), listOf(plain)).size, "groups never share")
    }

    @Test
    fun `an occupation never conflicts with itself when it is edited`() {
        val placed = group("17:30")
        assertEquals(emptyList<Occupation>(), SlotRules.conflicts(placed.copy(start = LocalTime.of(18, 0)), listOf(placed)))
    }

    @Test
    fun `all conflicts are listed once per pair`() {
        val occupations = listOf(course("17:00"), group("17:30"), workshop("18:30"))
        // 17:00-17:30, 17:30-19:00 et 18:30-19:45 : seuls le groupe et l'atelier se chevauchent.
        assertEquals(1, SlotRules.allConflicts(occupations).size)
    }
}
