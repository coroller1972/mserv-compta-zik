package fr.spiritbox.compta.security

import io.smallrye.mutiny.Uni
import jakarta.ws.rs.core.Response
import org.jose4j.jwk.JsonWebKey
import org.jose4j.jwk.JsonWebKeySet
import org.jose4j.jws.JsonWebSignature
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CompletableFuture
import javax.crypto.spec.SecretKeySpec

class IdentityJwtVerifierTest {
    private class Identity : IdentitySessionClient {
        var jwks = TestJwt.jwks()
        var calls = 0
        var status = 200
        var pending: CompletableFuture<Response>? = null

        override fun publicKeys(): Uni<Response> {
            calls++
            return pending?.let { Uni.createFrom().completionStage(it) }
                ?: Uni.createFrom().item(Response.status(status).entity(jwks).build())
        }

        override fun session(authorization: String): Uni<Response> = error("Verification must not call the session endpoint")
    }

    private val identity = Identity()
    private val verifier = IdentityJwtVerifier(identity, TestJwt.ISSUER, TestJwt.AUDIENCE)
    private fun verify(token: String) = verifier.subject(token).await().atMost(Duration.ofSeconds(5))

    @Test
    fun `public key verifies an identity token and is cached`() {
        repeat(3) { assertEquals("security-test", verify(TestJwt.token())) }
        assertEquals(1, identity.calls)
    }

    @Test
    fun `invalid signature claims and required metadata fail closed`() {
        val now = Instant.now().epochSecond
        val invalidClaims = listOf(
            mapOf("iss" to "another-issuer"), mapOf("aud" to "another-api"),
            mapOf("exp" to now - 1), mapOf("nbf" to now + 60), mapOf("iat" to now + 60),
            mapOf("iat" to now - 1, "exp" to now - 1), mapOf("sub" to ""),
        ) + listOf("iss", "sub", "aud", "exp", "iat", "nbf", "jti", "sid", "av", "roles", "permissions").map { mapOf(it to null) }
        invalidClaims.forEach { claims -> assertNull(verify(TestJwt.token(claims)), claims.toString()) }
        val forgedKey = TestJwt.newKey().apply { keyId = TestJwt.signingKey.keyId }
        assertNull(verify(TestJwt.token(key = forgedKey)))
        val token = TestJwt.token().split('.')
        val tamperedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"forged"}""".toByteArray())
        assertNull(verify("${token[0]}.$tamperedPayload.${token[2]}"))
    }

    @Test
    fun `HS256 unsigned tokens malformed tokens and token supplied keys are rejected before fetching keys`() {
        val hs256 = JsonWebSignature().apply {
            algorithmHeaderValue = "HS256"
            key = SecretKeySpec(ByteArray(32) { 7 }, "HmacSHA256")
            payload = "{}"
        }.compactSerialization
        for (token in listOf(hs256, "eyJhbGciOiJub25lIn0.e30.", "invalid", "x".repeat(16385))) {
            assertNull(verify(token))
        }
        for (name in listOf("jku", "jwk", "x5u", "x5c", "crit", "b64")) {
            assertNull(verify(TestJwt.token(headers = mapOf(name to "https://untrusted.invalid/key"))))
        }
        assertNull(verify(TestJwt.token(headers = mapOf("typ" to "other"))))
        assertNull(verify(TestJwt.token(headers = mapOf("kid" to ""))))
        assertEquals(0, identity.calls)
    }

    @Test
    fun `rotation refreshes an unknown kid and retains previously published keys`() {
        assertEquals("security-test", verify(TestJwt.token()))
        val next = TestJwt.newKey()
        identity.jwks = TestJwt.jwks(TestJwt.signingKey, next)
        assertEquals("security-test", verify(TestJwt.token(key = next)))
        assertEquals("security-test", verify(TestJwt.token()))
        repeat(5) { assertNull(verify(TestJwt.token(key = TestJwt.newKey()))) }
        assertEquals(2, identity.calls)
    }

    @Test
    fun `JWKS failures remain infrastructure errors and can be retried`() {
        identity.status = 503
        assertThrows(IllegalStateException::class.java) { verify(TestJwt.token()) }
        identity.status = 200
        assertEquals("security-test", verify(TestJwt.token()))
    }

    @Test
    fun `private ambiguous and malformed key sets are rejected`() {
        val invalid = listOf(
            "not-json", """{"keys":[]}""",
            TestJwt.jwks(TestJwt.signingKey, TestJwt.signingKey),
            JsonWebKeySet(listOf(TestJwt.signingKey)).toJson(JsonWebKey.OutputControlLevel.INCLUDE_PRIVATE),
            TestJwt.jwks().replace("P-256", "P-384"),
            TestJwt.jwks().replace("ES256", "HS256"),
        )
        invalid.forEach { json ->
            identity.jwks = json
            assertThrows(IllegalStateException::class.java) { verify(TestJwt.token()) }
        }
    }

    @Test
    fun `concurrent requests share a single JWKS download`() {
        val response = CompletableFuture<Response>()
        identity.pending = response
        val requests = (1..10).map { verifier.subject(TestJwt.token()).subscribeAsCompletionStage() }
        assertEquals(1, identity.calls)
        response.complete(Response.ok(TestJwt.jwks()).build())
        requests.forEach { assertEquals("security-test", it.toCompletableFuture().get()) }
    }
}
