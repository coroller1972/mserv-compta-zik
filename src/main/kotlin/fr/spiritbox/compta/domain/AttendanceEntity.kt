package fr.spiritbox.compta.domain

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
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.UpdateTimestamp
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "attendance",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_attendance_term_week_entity",
            columnNames = ["term_id", "week", "entity_type", "entity_id"],
        ),
    ],
    indexes = [
        Index(name = "idx_attendance_term", columnList = "term_id"),
        Index(name = "idx_attendance_entity", columnList = "entity_type, entity_id"),
    ],
)
open class AttendanceEntity : PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    open var id: UUID? = null

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "term_id", nullable = false)
    open lateinit var term: TermEntity

    @Column(name = "week", nullable = false)
    open var week: Int = 1

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 40)
    open var entityType: AttendanceEntityType = AttendanceEntityType.INDIVIDUAL_COURSE

    @Column(name = "entity_id", nullable = false)
    open var entityId: UUID? = null

    @Column(name = "present", nullable = false)
    open var present: Boolean = false

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    open var updatedAt: Instant? = null
}
