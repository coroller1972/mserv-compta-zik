package fr.spiritbox.compta.domain

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.UpdateTimestamp
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "accounting_year_settings",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_accounting_year_settings_year", columnNames = ["year"]),
    ],
)
open class AccountingYearSettingsEntity : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    open var id: UUID? = null

    @Version
    @Column(name = "version", nullable = false)
    open var version: Long = 0

    @Column(name = "year", nullable = false)
    open var year: Int = 0

    @Column(name = "teacher_hourly_rate", nullable = false, precision = 10, scale = 2)
    open var teacherHourlyRate: BigDecimal = BigDecimal.ZERO

    @Column(name = "group_membership_fee", nullable = false, precision = 10, scale = 2)
    open var groupMembershipFee: BigDecimal = BigDecimal.ZERO

    @Column(name = "individual_course_hours", nullable = false, precision = 5, scale = 2)
    open var individualCourseHours: BigDecimal = BigDecimal("0.50")

    @Column(name = "workshop_hours", nullable = false, precision = 5, scale = 2)
    open var workshopHours: BigDecimal = BigDecimal("1.25")

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "school_holiday_weeks", nullable = false, columnDefinition = "jsonb")
    open var schoolHolidayWeeks: Map<String, List<Int>> = mapOf("weeks" to emptyList())

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    open var status: AccountingYearStatus = AccountingYearStatus.OPEN

    @Column(name = "reviewed_at")
    open var reviewedAt: Instant? = null

    @Column(name = "closed_at")
    open var closedAt: Instant? = null

    @OneToMany(mappedBy = "settings", cascade = [CascadeType.ALL], orphanRemoval = true)
    open var terms: MutableList<TermEntity> = mutableListOf()

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    open var createdAt: Instant? = null

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    open var updatedAt: Instant? = null
}
