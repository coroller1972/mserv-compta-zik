package fr.spiritbox.compta.services

import com.fasterxml.jackson.databind.ObjectMapper
import fr.spiritbox.compta.domain.AuditEventEntity
import fr.spiritbox.compta.repositories.AuditEventRepository
import fr.spiritbox.compta.security.AuthenticatedUser
import io.smallrye.mutiny.coroutines.awaitSuspending
import jakarta.enterprise.context.ApplicationScoped
import java.util.UUID
import fr.spiritbox.compta.openapi.model.AuditEvent
import java.time.ZoneOffset

@ApplicationScoped
class AuditService(
    private val repository: AuditEventRepository,
    private val authenticatedUser: AuthenticatedUser,
    private val objectMapper: ObjectMapper,
    private val panacheCoroutine: fr.spiritbox.compta.utils.PanacheCoroutine,
) {
    suspend fun list(limit: Int): List<AuditEvent> = panacheCoroutine.withSession {
        val safeLimit = limit.coerceIn(1, 200)
        repository.find("order by createdAt desc").page(0, safeLimit).list().awaitSuspending().map { event ->
            AuditEvent(
                id = requireNotNull(event.id),
                entityType = event.entityType,
                entityId = event.entityId,
                action = event.action,
                userId = event.userId,
                details = objectMapper.convertValue(event.details, Map::class.java) as Map<String, Any>,
                createdAt = requireNotNull(event.createdAt).atOffset(ZoneOffset.UTC),
            )
        }
    }

    suspend fun record(entityType: String, entityId: UUID?, action: String, details: Any = emptyMap<String, Any>()) {
        repository.persist(AuditEventEntity().apply {
            this.entityType = entityType
            this.entityId = entityId
            this.action = action
            userId = authenticatedUser.displayName
            this.details = objectMapper.valueToTree(details)
        }).awaitSuspending()
    }
}
