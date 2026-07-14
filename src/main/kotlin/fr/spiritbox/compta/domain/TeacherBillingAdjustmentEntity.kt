package fr.spiritbox.compta.domain

import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "teacher_billing_adjustment")
open class TeacherBillingAdjustmentEntity : PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    open var id: UUID? = null

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "teacher_id", nullable = false)
    open lateinit var teacher: TeacherEntity

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_document_id", nullable = false)
    open lateinit var sourceDocument: GeneratedDocumentEntity

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "applied_document_id")
    open var appliedDocument: GeneratedDocumentEntity? = null

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    open var amount: BigDecimal = BigDecimal.ZERO

    @Column(name = "reason", nullable = false, length = 1000)
    open lateinit var reason: String

    @Column(name = "applied_at")
    open var appliedAt: Instant? = null

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    open var createdAt: Instant? = null
}
