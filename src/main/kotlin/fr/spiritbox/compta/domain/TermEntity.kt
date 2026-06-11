package fr.spiritbox.compta.domain

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.util.UUID

@Entity
@Table(
    name = "term",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_term_settings_name", columnNames = ["settings_id", "name"]),
    ],
    indexes = [
        Index(name = "idx_term_settings", columnList = "settings_id"),
    ],
)
open class TermEntity : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    open var id: UUID? = null

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "settings_id", nullable = false)
    open lateinit var settings: AccountingYearSettingsEntity

    @Column(name = "name", nullable = false, length = 80)
    open var name: String = ""

    @Column(name = "start_week", nullable = false)
    open var startWeek: Int = 1

    @Column(name = "end_week", nullable = false)
    open var endWeek: Int = 1

    @Column(name = "display_order", nullable = false)
    open var displayOrder: Int = 0
}
