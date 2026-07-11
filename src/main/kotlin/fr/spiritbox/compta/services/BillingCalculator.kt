package fr.spiritbox.compta.services

import java.math.BigDecimal
import java.math.RoundingMode

object BillingCalculator {
    private const val MONEY_SCALE = 2
    private const val HOUR_SCALE = 2
    private val STUDENT_BILLING_SUBSIDY_SHARE = BigDecimal("0.50")

    data class StudentAmounts(
        val individualCourseAmount: BigDecimal,
        val groupMembershipAmount: BigDecimal,
        val totalAmount: BigDecimal,
    )

    data class TeacherAmounts(
        val individualHours: BigDecimal,
        val workshopHours: BigDecimal,
        val totalHours: BigDecimal,
        val totalAmount: BigDecimal,
    )

    fun studentAmounts(
        individualCourseCount: Int,
        hourlyRate: BigDecimal,
        individualCourseHours: BigDecimal,
        groupMembershipCount: Int,
        applyGroupMembershipFee: Boolean,
        groupMembershipFee: BigDecimal,
    ): StudentAmounts {
        require(individualCourseCount >= 0) { "Individual course count must not be negative." }
        require(groupMembershipCount >= 0) { "Group membership count must not be negative." }

        val individualCourseAmount = hourlyRate
            .multiply(individualCourseHours)
            .multiply(BigDecimal(individualCourseCount))
            .toMoney()
        val groupMembershipAmount = if (applyGroupMembershipFee && groupMembershipCount > 0) {
            groupMembershipFee.toMoney()
        } else {
            BigDecimal.ZERO.toMoney()
        }
        return StudentAmounts(
            individualCourseAmount = individualCourseAmount,
            groupMembershipAmount = groupMembershipAmount,
            totalAmount = individualCourseAmount.add(groupMembershipAmount).toMoney(),
        )
    }

    fun teacherAmounts(
        individualCourseCount: Int,
        workshopCount: Int,
        hourlyRate: BigDecimal,
        individualCourseHours: BigDecimal,
        workshopHours: BigDecimal,
    ): TeacherAmounts {
        require(individualCourseCount >= 0) { "Individual course count must not be negative." }
        require(workshopCount >= 0) { "Workshop count must not be negative." }

        val billedIndividualHours = individualCourseHours.multiply(BigDecimal(individualCourseCount)).toHours()
        val billedWorkshopHours = workshopHours.multiply(BigDecimal(workshopCount)).toHours()
        val totalHours = billedIndividualHours.add(billedWorkshopHours).toHours()
        return TeacherAmounts(
            individualHours = billedIndividualHours,
            workshopHours = billedWorkshopHours,
            totalHours = totalHours,
            totalAmount = totalHours.multiply(hourlyRate).toMoney(),
        )
    }

    fun subsidy(
        teacherDue: BigDecimal,
        studentBilling: BigDecimal,
        groupFees: BigDecimal,
    ): BigDecimal = teacherDue
        .subtract(studentBilling.multiply(STUDENT_BILLING_SUBSIDY_SHARE))
        .subtract(groupFees)
        .toMoney()

    fun BigDecimal.toMoney(): BigDecimal = setScale(MONEY_SCALE, RoundingMode.HALF_UP)

    fun BigDecimal.toHours(): BigDecimal = setScale(HOUR_SCALE, RoundingMode.HALF_UP)
}
