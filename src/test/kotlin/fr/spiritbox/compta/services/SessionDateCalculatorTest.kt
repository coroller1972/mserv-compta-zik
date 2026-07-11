package fr.spiritbox.compta.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDate

class SessionDateCalculatorTest {
    @Test
    fun `calculates actual scheduled weekday with accounting convention`() {
        assertEquals(LocalDate.of(2099, 1, 6), SessionDateCalculator.scheduledDate(2099, 2, "Mardi"))
        assertEquals(LocalDate.of(2099, 1, 8), SessionDateCalculator.scheduledDate(2099, 2, "Jeudi"))
        assertEquals(2, SessionDateCalculator.accountingWeek(2099, LocalDate.of(2099, 1, 10)))
    }

    @Test
    fun `week one starts on january first`() {
        assertEquals(LocalDate.of(2026, 1, 1), SessionDateCalculator.scheduledDate(2026, 1, "Vendredi"))
    }

    @Test
    fun `rejects date outside accounting year`() {
        assertThrows(jakarta.ws.rs.BadRequestException::class.java) {
            SessionDateCalculator.accountingWeek(2026, LocalDate.of(2027, 1, 1))
        }
    }
}
