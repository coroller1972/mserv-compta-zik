package fr.spiritbox.compta.domain

import com.fasterxml.jackson.databind.JsonNode
import io.quarkus.hibernate.reactive.panache.kotlin.PanacheEntityBase
import jakarta.persistence.*
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "audit_event")
open class AuditEventEntity : PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    open var id: UUID? = null
    @Column(name = "entity_type", nullable = false, length = 80)
    open var entityType: String = ""
    @Column(name = "entity_id")
    open var entityId: UUID? = null
    @Column(name = "action", nullable = false, length = 80)
    open var action: String = ""
    @Column(name = "user_id", nullable = false, length = 160)
    open var userId: String = "system"
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", nullable = false, columnDefinition = "jsonb")
    open lateinit var details: JsonNode
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    open var createdAt: Instant? = null
}
