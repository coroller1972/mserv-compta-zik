package fr.spiritbox.compta.security

import jakarta.annotation.Priority
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerRequestFilter
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.ext.Provider
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Provider
@Priority(Priorities.AUTHENTICATION)
class JwtAuthenticationFilter(
    @ConfigProperty(name = "auth.jwt.issuer") private val issuer: String,
    @ConfigProperty(name = "auth.jwt.secret") private val secret: String,
    private val authenticatedUser: AuthenticatedUser,
) : ContainerRequestFilter {
    override fun filter(requestContext: ContainerRequestContext) {
        if (isPublic(requestContext)) return

        val token =
            requestContext
                .getHeaderString(HttpHeaders.AUTHORIZATION)
                ?.removePrefix("Bearer ")
                ?.takeIf { it.isNotBlank() }

        val claims = token?.let(::verifiedClaims)
        if (claims == null) {
            requestContext.abortWith(problem(401, "TOKEN_EXPIRED"))
            return
        }

        requestContext.setProperty("auth.userId", claims.subject)
        authenticatedUser.userId = claims.subject
        authenticatedUser.displayName = claims.displayName ?: claims.subject
        requestContext.setProperty("auth.roles", claims.roles)
        requestContext.setProperty("auth.permissions", claims.permissions)

        val required = endpointPermissions.firstOrNull { it.matches(requestContext.method, requestContext.uriInfo.path.trim('/')) }
        if (required != null && !required.isAllowed(claims)) {
            requestContext.abortWith(problem(403, "FORBIDDEN"))
        }
    }

    private fun isPublic(requestContext: ContainerRequestContext): Boolean {
        if (requestContext.method == "OPTIONS") return true
        val path = requestContext.uriInfo.path.trim('/')
        return path == "openapi" || path.startsWith("q/") || path.startsWith("swagger-ui")
    }

    private fun verifiedClaims(token: String): JwtClaims? {
        val parts = token.split(".")
        if (parts.size != 3) return null
        val expectedSignature = sign("${parts[0]}.${parts[1]}")
        if (!constantTimeEquals(expectedSignature, parts[2])) return null

        val payload =
            runCatching {
                String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
            }.getOrNull() ?: return null

        val tokenIssuer = stringClaim(payload, "iss") ?: return null
        if (tokenIssuer != issuer) return null

        val expiresAt = longClaim(payload, "exp") ?: return null
        if (Instant.now().epochSecond >= expiresAt) return null

        return JwtClaims(
            subject = stringClaim(payload, "sub") ?: return null,
            displayName = stringClaim(payload, "name"),
            roles = stringArrayClaim(payload, "roles"),
            permissions = stringArrayClaim(payload, "permissions"),
        )
    }

    private fun stringClaim(
        payload: String,
        name: String,
    ): String? = """"$name"\s*:\s*"([^"]+)"""".toRegex().find(payload)?.groupValues?.get(1)

    private fun longClaim(
        payload: String,
        name: String,
    ): Long? = """"$name"\s*:\s*(\d+)""".toRegex().find(payload)?.groupValues?.get(1)?.toLongOrNull()

    private fun stringArrayClaim(
        payload: String,
        name: String,
    ): List<String> {
        val raw = """"$name"\s*:\s*\[(.*?)]""".toRegex().find(payload)?.groupValues?.get(1).orEmpty()
        return """"([^"]+)"""".toRegex().findAll(raw).map { it.groupValues[1] }.toList()
    }

    private fun sign(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun constantTimeEquals(
        expected: String,
        actual: String,
    ): Boolean = MessageDigest.isEqual(expected.toByteArray(StandardCharsets.UTF_8), actual.toByteArray(StandardCharsets.UTF_8))

    private fun problem(
        status: Int,
        code: String,
    ): Response =
        Response
            .status(status)
            .type("application/problem+json")
            .entity(
                Problem(
                    type = "https://api.compta-zik.local/problems/${code.lowercase().replace("_", "-")}",
                    title = code,
                    status = status,
                    code = code,
                ),
            ).build()
}

private val endpointPermissions =
    listOf(
        EndpointPermission("GET", Regex("""audit-events$"""), listOf("CONFIG_AUDIT")),
        EndpointPermission("GET", Regex("""data/export$"""), listOf("IMPORT_EXPORT")),
        EndpointPermission("POST", Regex("""data/import$"""), listOf("IMPORT_EXPORT")),
        EndpointPermission("POST", Regex("""data/import/analyze$"""), listOf("IMPORT_EXPORT")),
        EndpointPermission(
            "GET",
            Regex("""accounting-years/\d+/snapshot$"""),
            listOf("PRESENCE_READ", "MUSICIENS_READ", "MUSICIENS_ARCHIVED", "GROUPS_READ", "EXPENSES_READ", "BILLING_READ"),
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
        EndpointPermission("POST", Regex("""accounting-years/\d+/terms/[^/]+/student-invoices/finalize$"""), listOf("BILLING_PRINT")),
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

    fun isAllowed(claims: JwtClaims): Boolean =
        "ADMINISTRATOR" in claims.roles || claims.permissions.any(anyOf::contains)
}

private data class JwtClaims(
    val subject: String,
    val displayName: String?,
    val roles: List<String>,
    val permissions: List<String>,
)

private data class Problem(
    val type: String,
    val title: String,
    val status: Int,
    val code: String,
    val correlationId: String = UUID.randomUUID().toString(),
)
