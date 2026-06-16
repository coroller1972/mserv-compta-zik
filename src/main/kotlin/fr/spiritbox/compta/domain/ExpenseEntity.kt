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
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(
    name = "expense",
    indexes = [
        Index(name = "idx_expense_year", columnList = "year"),
        Index(name = "idx_expense_date", columnList = "expense_date"),
        Index(name = "idx_expense_category", columnList = "category"),
    ],
)
open class ExpenseEntity : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    open var id: UUID? = null

    @Column(name = "year", nullable = false)
    open var year: Int = 0

    @Column(name = "expense_date", nullable = false)
    open var date: LocalDate = LocalDate.now()

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 60)
    open var category: ExpenseCategory = ExpenseCategory.EQUIPMENT

    @Column(name = "label", nullable = false, length = 180)
    open var label: String = ""

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    open var amount: BigDecimal = BigDecimal.ZERO

    @Column(name = "notes", length = 500)
    open var notes: String? = null

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    open var createdAt: Instant? = null

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    open var updatedAt: Instant? = null
}
