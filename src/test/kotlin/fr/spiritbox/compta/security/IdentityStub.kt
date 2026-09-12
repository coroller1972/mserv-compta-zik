package fr.spiritbox.compta.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HTTP double for the external identity boundary; never installed in application code. */
class IdentityStub : QuarkusTestResourceLifecycleManager {
    private lateinit var server: HttpServer

    override fun start(): Map<String, String> {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/auth/session") { exchange ->
            val authorization = exchange.requestHeaders.getFirst("Authorization").orEmpty()
            val parts = authorization.removePrefix("Bearer ").split('.')
            val payload = runCatching {
                require(authorization.startsWith("Bearer ") && parts.size == 3)
                require(MessageDigest.isEqual(sign("${parts[0]}.${parts[1]}").toByteArray(), parts[2].toByteArray()))
                mapper.readTree(Base64.getUrlDecoder().decode(parts[1]))
            }.getOrNull()
            val subject = payload?.path("sub")?.asText().orEmpty()
            val failure = failures[subject]
            val status = if (payload == null || payload.path("exp").asLong() <= Instant.now().epochSecond) 401 else failure?.first ?: 200
            val body = if (status == 200) mapOf(
                "user" to mapOf("id" to subject, "displayName" to "Security test"),
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
        return mapOf("quarkus.rest-client.identity.url" to "http://127.0.0.1:${server.address.port}/api/auth")
    }

    override fun stop() { server.stop(0) }

    companion object {
        val failures = ConcurrentHashMap<String, Pair<Int, String>>()
        val livePermissions = ConcurrentHashMap<String, List<String>>()
        private val mapper = ObjectMapper()
        private const val SECRET = "compta-zik-test-secret-with-at-least-32-characters"

        fun token(permissions: List<String> = emptyList(), admin: Boolean = false, subject: String = "security-test"): String {
            val header = encode("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
            val payload = encode(mapper.writeValueAsBytes(mapOf(
                "iss" to "demo-mpme-auth", "sub" to subject, "sid" to "test-session", "av" to 0,
                "exp" to Instant.now().plusSeconds(300).epochSecond,
                "roles" to if (admin) listOf("ADMINISTRATOR") else emptyList<String>(), "permissions" to permissions,
            )))
            return "$header.$payload.${sign("$header.$payload")}"
        }

        private fun encode(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        private fun sign(value: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(SECRET.toByteArray(), "HmacSHA256"))
            return encode(mac.doFinal(value.toByteArray()))
        }
    }
}
