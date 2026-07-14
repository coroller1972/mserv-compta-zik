package fr.spiritbox.compta.domain

import com.fasterxml.jackson.databind.JsonNode
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
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
    name = "generated_document",
    indexes = [
        Index(name = "idx_generated_document_term", columnList = "term_id"),
        Index(name = "idx_generated_document_type", columnList = "type"),
        Index(name = "idx_generated_document_musician", columnList = "musician_id"),
        Index(name = "idx_generated_document_teacher", columnList = "teacher_id"),
    ],
)
open class GeneratedDocumentEntity : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    open var id: UUID? = null

    @Version
    @Column(name = "version", nullable = false)
    open var version: Long = 0

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "term_id", nullable = false)
    open lateinit var term: TermEntity

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 50)
    open var type: GeneratedDocumentType = GeneratedDocumentType.STUDENT_INVOICE

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 40)
    open var status: GeneratedDocumentStatus = GeneratedDocumentStatus.DRAFT

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "musician_id")
    open var musician: MusicianEntity? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "teacher_id")
    open var teacher: TeacherEntity? = null

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_document_id")
    open var parentDocument: GeneratedDocumentEntity? = null

    @Column(name = "correction_reason", length = 1000)
    open var correctionReason: String? = null

    @Column(name = "sent_at")
    open var sentAt: Instant? = null

    @Column(name = "cancelled_at")
    open var cancelledAt: Instant? = null

    @Column(name = "credited_at")
    open var creditedAt: Instant? = null

    @Column(name = "document_number", length = 80)
    open var documentNumber: String? = null

    @Column(name = "file_name", length = 255)
    open var fileName: String? = null

    @Column(name = "content_type", length = 120)
    open var contentType: String? = null

    @Column(name = "storage_path", length = 500)
    open var storagePath: String? = null

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    open var totalAmount: BigDecimal = BigDecimal.ZERO

    @Column(name = "period_start_week")
    open var periodStartWeek: Int? = null

    @Column(name = "period_end_week")
    open var periodEndWeek: Int? = null

    @Column(name = "installment_number")
    open var installmentNumber: Int? = null

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    open var metadata: JsonNode? = null

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    open var createdAt: Instant? = null

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    open var updatedAt: Instant? = null
}
