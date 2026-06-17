package fr.spiritbox.compta.domain

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(
    name = "music_expense",
    indexes = [
        Index(name = "idx_music_expense_year", columnList = "year"),
        Index(name = "idx_music_expense_date", columnList = "expense_date"),
    ],
)
open class ExpenseEntity : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    open var id: UUID? = null

    @Column(name = "year", nullable = false)
    open var year: Int = 0

    @Column(name = "expense_date", nullable = false)
    open lateinit var date: LocalDate

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 60)
    open var category: ExpenseCategory = ExpenseCategory.OTHER

    @Column(name = "label", nullable = false, length = 180)
    open var label: String = ""

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    open var amount: BigDecimal = BigDecimal.ZERO

    @Column(name = "notes", length = 1000)
    open var notes: String? = null
}
