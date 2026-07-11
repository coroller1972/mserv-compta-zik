package fr.spiritbox.compta.resources

import fr.spiritbox.compta.openapi.api.AuditApi
import fr.spiritbox.compta.services.AuditService
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.core.Response

@ApplicationScoped
class AuditResource(private val auditService: AuditService) : AuditApi {
    override suspend fun listAuditEvents(limit: Int): Response =
        Response.ok(auditService.list(limit)).build()
}
