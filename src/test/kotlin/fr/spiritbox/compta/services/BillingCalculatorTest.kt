package fr.spiritbox.compta.services

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class BillingCalculatorTest {
    private val hourlyRate = BigDecimal("54.00")
    private val individualCourseHours = BigDecimal("0.50")
    private val workshopHours = BigDecimal("1.25")
    private val groupMembershipFee = BigDecimal("30.00")

    @Test
    fun `subsidy follows the validated accounting formula`() {
        assertMoney(
            "740.00",
            BillingCalculator.subsidy(
                teacherDue = BigDecimal("1000.00"),
                studentBilling = BigDecimal("400.00"),
                groupFees = BigDecimal("60.00"),
            ),
        )
    }

    @Test
    fun `subsidy handles zero negative and rounded results`() {
        assertMoney("0.00", BillingCalculator.subsidy(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO))
        assertMoney("-80.00", BillingCalculator.subsidy(BigDecimal("100.00"), BigDecimal("300.00"), BigDecimal("30.00")))
        assertMoney("66.67", BillingCalculator.subsidy(BigDecimal("100.00"), BigDecimal("66.67"), BigDecimal.ZERO))
    }

    @Test
    fun `an individual course is billed only for present sessions`() {
        val amounts = BillingCalculator.studentAmounts(
            individualCourseCount = 3,
            hourlyRate = hourlyRate,
            individualCourseHours = individualCourseHours,
            groupMembershipCount = 0,
            applyGroupMembershipFee = true,
            groupMembershipFee = groupMembershipFee,
        )

        assertMoney("81.00", amounts.individualCourseAmount)
        assertMoney("0.00", amounts.groupMembershipAmount)
        assertMoney("81.00", amounts.totalAmount)
    }

    @Test
    fun `several group memberships produce one annual fee in the first term`() {
        val firstTerm = BillingCalculator.studentAmounts(
            individualCourseCount = 0,
            hourlyRate = hourlyRate,
            individualCourseHours = individualCourseHours,
            groupMembershipCount = 3,
            applyGroupMembershipFee = true,
            groupMembershipFee = groupMembershipFee,
        )
        val laterTerm = BillingCalculator.studentAmounts(
            individualCourseCount = 0,
            hourlyRate = hourlyRate,
            individualCourseHours = individualCourseHours,
            groupMembershipCount = 3,
            applyGroupMembershipFee = false,
            groupMembershipFee = groupMembershipFee,
        )

        assertMoney("30.00", firstTerm.groupMembershipAmount)
        assertMoney("0.00", laterTerm.groupMembershipAmount)
    }

    @Test
    fun `individual lessons and workshops are accumulated for a teacher`() {
        val amounts = BillingCalculator.teacherAmounts(
            individualCourseCount = 2,
            workshopCount = 1,
            hourlyRate = hourlyRate,
            individualCourseHours = individualCourseHours,
            workshopHours = workshopHours,
        )

        assertHours("1.00", amounts.individualHours)
        assertHours("1.25", amounts.workshopHours)
        assertHours("2.25", amounts.totalHours)
        assertMoney("121.50", amounts.totalAmount)
    }

    @Test
    fun `an absent session does not add billing`() {
        val amounts = BillingCalculator.studentAmounts(
            individualCourseCount = 0,
            hourlyRate = hourlyRate,
            individualCourseHours = individualCourseHours,
            groupMembershipCount = 0,
            applyGroupMembershipFee = false,
            groupMembershipFee = groupMembershipFee,
        )

        assertMoney("0.00", amounts.totalAmount)
    }

    private fun assertMoney(expected: String, actual: BigDecimal) =
        assertEquals(BigDecimal(expected), actual)

    private fun assertHours(expected: String, actual: BigDecimal) =
        assertEquals(BigDecimal(expected), actual)
}
