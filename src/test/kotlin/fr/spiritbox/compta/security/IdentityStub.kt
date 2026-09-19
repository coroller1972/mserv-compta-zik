package fr.spiritbox.compta.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import java.net.InetSocketAddress
import org.jose4j.jws.JsonWebSignature
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** HTTP double for the external identity boundary; never installed in application code. */
class IdentityStub : QuarkusTestResourceLifecycleManager {
    private lateinit var server: HttpServer

    override fun start(): Map<String, String> {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/auth/.well-known/jwks.json") { exchange ->
            val bytes = TestJwt.jwks().toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.responseHeaders.set("Cache-Control", "public, max-age=300")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.createContext("/api/auth/session") { exchange ->
            sessionCalls.incrementAndGet()
            val authorization = exchange.requestHeaders.getFirst("Authorization").orEmpty()
            val parts = authorization.removePrefix("Bearer ").split('.')
            val payload = runCatching {
                require(authorization.startsWith("Bearer ") && parts.size == 3)
                val signature = JsonWebSignature().apply {
                    compactSerialization = authorization.removePrefix("Bearer ")
                    key = TestJwt.signingKey.publicKey
                }
                require(signature.verifySignature())
                mapper.readTree(Base64.getUrlDecoder().decode(parts[1]))
            }.getOrNull()
            val subject = payload?.path("sub")?.asText().orEmpty()
            val failure = failures[subject]
            val status = if (payload == null || payload.path("exp").asLong() <= Instant.now().epochSecond) 401 else failure?.first ?: 200
            val body = if (status == 200) mapOf(
                "user" to mapOf("id" to (sessionSubjects[subject] ?: subject), "displayName" to "Security test"),
                "roles" to (livePermissions[subject]?.let { emptyList<String>() } ?: payload!!.path("roles")),
                "permissions" to (livePermissions[subject] ?: payload!!.path("permissions")),
            ) else mapOf("code" to (failure?.second ?: "TOKEN_EXPIRED"))
            val bytes = mapper.writeValueAsBytes(body)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        return mapOf(
            "quarkus.rest-client.identity.url" to "http://127.0.0.1:${server.address.port}/api/auth",
            "auth.jwt.issuer" to TestJwt.ISSUER,
            "auth.jwt.audience" to TestJwt.AUDIENCE,
        )
    }

    override fun stop() { server.stop(0) }

    companion object {
        val failures = ConcurrentHashMap<String, Pair<Int, String>>()
        val livePermissions = ConcurrentHashMap<String, List<String>>()
        val sessionSubjects = ConcurrentHashMap<String, String>()
        val sessionCalls = AtomicInteger()
        private val mapper = ObjectMapper()

        fun token(permissions: List<String> = emptyList(), admin: Boolean = false, subject: String = "security-test"): String {
            return TestJwt.token(mapOf(
                "sub" to subject,
                "roles" to if (admin) listOf("ADMINISTRATOR") else emptyList<String>(), "permissions" to permissions,
            ))
        }
    }
}
