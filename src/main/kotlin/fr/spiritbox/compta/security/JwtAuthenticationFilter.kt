package fr.spiritbox.compta.security

import com.fasterxml.jackson.databind.JsonNode
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jboss.resteasy.reactive.server.ServerRequestFilter
import java.util.UUID

/** Validates every business request against the live identity session, without caching revocations. */
@ApplicationScoped
class JwtAuthenticationFilter(
    @RestClient private val identity: IdentitySessionClient,
    private val authenticatedUser: AuthenticatedUser,
) {
    @ServerRequestFilter(priority = Priorities.AUTHENTICATION, nonBlocking = true)
    fun filter(request: ContainerRequestContext): Uni<Response?> {
        val path = request.uriInfo.path.trim('/')
        if (request.method == "OPTIONS" || path == "openapi" || path.startsWith("q/") || path.startsWith("swagger-ui")) {
            return Uni.createFrom().nullItem()
        }
        val authorization = request.getHeaderString(HttpHeaders.AUTHORIZATION)
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.removePrefix("Bearer ").isBlank()) {
            return Uni.createFrom().item(problem(401, "TOKEN_EXPIRED"))
        }
        return identity.session(authorization).map<Response?> { response ->
            response.use {
                if (it.status != 200) {
                    val status = it.status.takeIf { value -> value == 401 || value == 403 } ?: 503
                    val code = if (status == 503) "IDENTITY_UNAVAILABLE" else {
                        val supplied = it.readEntity(JsonNode::class.java).path("code").asText()
                        supplied.takeIf { value -> value in setOf("TOKEN_EXPIRED", "CREDENTIAL_EXPIRED", "PASSWORD_CHANGE_REQUIRED", "MFA_ENROLLMENT_REQUIRED", "FORBIDDEN") }
                            ?: if (status == 401) "TOKEN_EXPIRED" else "FORBIDDEN"
                    }
                    return@map problem(status, code)
                }
                val session = it.readEntity(JsonNode::class.java)
                val user = session.path("user")
                val id = user.path("id").asText()
                if (id.isBlank() || !session.path("roles").isArray || !session.path("permissions").isArray) {
                    return@map problem(503, "IDENTITY_UNAVAILABLE")
                }
                authenticatedUser.userId = id
                authenticatedUser.displayName = user.path("displayName").asText(id)
                authenticatedUser.roles = session.path("roles").map { role -> role.asText() }.toSet()
                authenticatedUser.permissions = session.path("permissions").map { permission -> permission.asText() }.toSet()
                val required = endpointPermissions.firstOrNull { permission -> permission.matches(request.method, path) }
                if (required == null || !required.isAllowed(authenticatedUser)) problem(403, "FORBIDDEN") else null
            }
        }.onFailure().recoverWithItem { _: Throwable -> problem(503, "IDENTITY_UNAVAILABLE") }
    }

    private fun problem(status: Int, code: String): Response = Response.status(status)
        .type("application/problem+json")
        .entity(mapOf(
            "type" to "https://api.compta-zik.local/problems/${code.lowercase().replace('_', '-')}",
            "title" to code, "status" to status, "code" to code,
            "correlationId" to UUID.randomUUID().toString(),
        )).build()
}

private val endpointPermissions =
    listOf(
        EndpointPermission("GET", Regex("""audit-events$"""), listOf("CONFIG_AUDIT")),
        EndpointPermission("GET", Regex("""data/export$"""), listOf("IMPORT_EXPORT")),
        EndpointPermission("GET", Regex("""data/backups(?:/[^/]+)?$"""), listOf("IMPORT_EXPORT")),
        EndpointPermission("POST", Regex("""data/(?:backups|restore(?:/analyze)?)$"""), listOf("IMPORT_EXPORT")),
        EndpointPermission("POST", Regex("""data/import$"""), listOf("IMPORT_EXPORT")),
        EndpointPermission("POST", Regex("""data/import/analyze$"""), listOf("IMPORT_EXPORT")),
        EndpointPermission(
            "GET",
            Regex("""accounting-years/\d+/snapshot$"""),
            listOf("PRESENCE_READ", "MUSICIENS_READ", "MUSICIENS_ARCHIVED", "GROUPS_READ", "EXPENSES_READ", "BILLING_READ", "CONFIG_TEACHER", "CONFIG_FINANCIALS", "CONFIG_TERMS", "CONFIG_HOLIDAYS"),
        ),
        EndpointPermission(
            "PUT",
            Regex("""accounting-years/\d+/settings$"""),
            listOf("CONFIG_FINANCIALS", "CONFIG_TERMS", "CONFIG_HOLIDAYS"),
        ),
        EndpointPermission(
            "PUT",
            Regex("""accounting-years/\d+/status$"""),
            listOf("CONFIG_FINANCIALS"),
        ),
        EndpointPermission(
            "POST",
            Regex("""accounting-years/\d+/copy-configuration-from/\d+$"""),
            listOf("CONFIG_FINANCIALS"),
        ),
        EndpointPermission("GET", Regex("""accounting-years/\d+/terms/[^/]+/attendance$"""), listOf("PRESENCE_READ")),
        EndpointPermission("GET", Regex("""accounting-years/\d+/terms/[^/]+/documents$"""), listOf("BILLING_READ")),
        EndpointPermission("GET", Regex("""accounting-years/\d+/terms/[^/]+/billing$"""), listOf("BILLING_READ")),
        EndpointPermission("POST", Regex("""accounting-years/\d+/terms/[^/]+/student-invoices$"""), listOf("BILLING_PRINT")),
        EndpointPermission("POST", Regex("""accounting-years/\d+/terms/[^/]+/student-invoices/(finalize|sent)$"""), listOf("BILLING_PRINT")),
        EndpointPermission("POST", Regex("""accounting-years/\d+/terms/[^/]+/student-invoices/summary$"""), listOf("BILLING_PRINT")),
        EndpointPermission("POST", Regex("""accounting-years/\d+/terms/[^/]+/teacher-invoice-requests$"""), listOf("BILLING_PRINT")),
        EndpointPermission("POST", Regex("""accounting-years/\d+/terms/[^/]+/teacher-invoice-requests/finalize$"""), listOf("BILLING_PRINT")),
        EndpointPermission("POST", Regex("""accounting-years/\d+/terms/[^/]+/teacher-invoice-requests/[^/]+/(prepare|finalize)$"""), listOf("BILLING_PRINT")),
        EndpointPermission("GET", Regex("""accounting-years/\d+/expenses$"""), listOf("EXPENSES_READ")),
        EndpointPermission("POST", Regex("""accounting-years/\d+/expenses$"""), listOf("EXPENSES_WRITE")),
        EndpointPermission("PUT", Regex("""attendance$"""), listOf("PRESENCE_WRITE")),
        EndpointPermission("GET", Regex("""teachers$"""), listOf("CONFIG_TEACHER")),
        EndpointPermission("POST", Regex("""teachers$"""), listOf("CONFIG_TEACHER")),
        EndpointPermission("PUT", Regex("""teachers/[^/]+$"""), listOf("CONFIG_TEACHER")),
        EndpointPermission("DELETE", Regex("""teachers/[^/]+$"""), listOf("CONFIG_TEACHER")),
        EndpointPermission("GET", Regex("""musicians$"""), listOf("MUSICIENS_READ", "MUSICIENS_ARCHIVED")),
        EndpointPermission("POST", Regex("""musicians$"""), listOf("MUSICIENS_WRITE")),
        EndpointPermission("PUT", Regex("""musicians/[^/]+$"""), listOf("MUSICIENS_WRITE")),
        EndpointPermission("DELETE", Regex("""musicians/[^/]+$"""), listOf("MUSICIENS_WRITE")),
        EndpointPermission("POST", Regex("""musicians/[^/]+/restore$"""), listOf("MUSICIENS_ARCHIVED")),
        EndpointPermission("GET", Regex("""individual-courses$"""), listOf("MUSICIENS_READ")),
        EndpointPermission("POST", Regex("""individual-courses$"""), listOf("MUSICIENS_WRITE")),
        EndpointPermission("PUT", Regex("""individual-courses/[^/]+$"""), listOf("MUSICIENS_WRITE")),
        EndpointPermission("DELETE", Regex("""individual-courses/[^/]+$"""), listOf("MUSICIENS_WRITE")),
        EndpointPermission("GET", Regex("""bands$"""), listOf("GROUPS_READ")),
        EndpointPermission("POST", Regex("""bands$"""), listOf("GROUPS_WRITE")),
        EndpointPermission("PUT", Regex("""bands/[^/]+$"""), listOf("GROUPS_WRITE")),
        EndpointPermission("DELETE", Regex("""bands/[^/]+$"""), listOf("GROUPS_WRITE")),
        EndpointPermission("PUT", Regex("""bands/[^/]+/members$"""), listOf("GROUPS_WRITE")),
        EndpointPermission("PUT", Regex("""expenses/[^/]+$"""), listOf("EXPENSES_WRITE")),
        EndpointPermission("DELETE", Regex("""expenses/[^/]+$"""), listOf("EXPENSES_DELETE")),
        EndpointPermission("GET", Regex("""documents/[^/]+$"""), listOf("BILLING_READ")),
        EndpointPermission("POST", Regex("""documents/[^/]+/(sent|cancel|correct)$"""), listOf("BILLING_PRINT")),
        EndpointPermission("POST", Regex("""documents/[^/]+/teacher-adjustments$"""), listOf("BILLING_PRINT")),
        EndpointPermission("DELETE", Regex("""teacher-adjustments/[^/]+$"""), listOf("BILLING_PRINT")),
    )

private data class EndpointPermission(
    val method: String,
    val path: Regex,
    val anyOf: List<String>,
) {
    fun matches(
        requestMethod: String,
        requestPath: String,
    ): Boolean = method == requestMethod && path.matches(requestPath)

    fun isAllowed(user: AuthenticatedUser): Boolean = user.canAny(*anyOf.toTypedArray())
}
