package fr.spiritbox.compta.domain

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
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

    @Column(name = "year", nullable = false)
    open var year: Int = 0

    @Column(name = "teacher_hourly_rate", nullable = false, precision = 10, scale = 2)
    open var teacherHourlyRate: BigDecimal = BigDecimal.ZERO

    @Column(name = "group_membership_fee", nullable = false, precision = 10, scale = 2)
    open var groupMembershipFee: BigDecimal = BigDecimal.ZERO

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "school_holiday_weeks", nullable = false, columnDefinition = "jsonb")
    open var schoolHolidayWeeks: Map<String, List<Int>> = mapOf("weeks" to emptyList())

    @OneToMany(mappedBy = "settings", cascade = [CascadeType.ALL], orphanRemoval = true)
    open var terms: MutableList<TermEntity> = mutableListOf()

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    open var createdAt: Instant? = null

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    open var updatedAt: Instant? = null
}
