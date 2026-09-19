package fr.spiritbox.compta.security

import com.fasterxml.jackson.databind.ObjectMapper
import org.jose4j.jwk.EcJwkGenerator
import org.jose4j.jwk.EllipticCurveJsonWebKey
import org.jose4j.jwk.JsonWebKey
import org.jose4j.jwk.JsonWebKeySet
import org.jose4j.jws.JsonWebSignature
import org.jose4j.keys.EllipticCurves
import java.time.Instant
import java.util.UUID

/** Ephemeral signing material exists only in the test process. */
object TestJwt {
    const val ISSUER = "identity-auth"
    const val AUDIENCE = "identity-api"
    val signingKey = newKey()
    private val mapper = ObjectMapper()

    fun newKey(): EllipticCurveJsonWebKey = EcJwkGenerator.generateJwk(EllipticCurves.P256).apply {
        keyId = UUID.randomUUID().toString()
        algorithm = "ES256"
        use = "sig"
    }

    fun jwks(vararg keys: EllipticCurveJsonWebKey = arrayOf(signingKey)): String =
        JsonWebKeySet(keys.toList()).toJson(JsonWebKey.OutputControlLevel.PUBLIC_ONLY)

    fun token(
        claims: Map<String, Any?> = emptyMap(),
        key: EllipticCurveJsonWebKey = signingKey,
        headers: Map<String, String> = emptyMap(),
    ): String {
        val now = Instant.now().epochSecond
        val payload = mutableMapOf<String, Any>(
            "iss" to ISSUER, "aud" to AUDIENCE, "sub" to "security-test",
            "iat" to now, "nbf" to now, "exp" to now + 300, "jti" to UUID.randomUUID().toString(),
            "sid" to UUID.randomUUID().toString(), "av" to 0,
            "roles" to emptyList<String>(), "permissions" to emptyList<String>(),
        )
        claims.forEach { (name, value) -> if (value == null) payload.remove(name) else payload[name] = value }
        return JsonWebSignature().apply {
            algorithmHeaderValue = "ES256"
            keyIdHeaderValue = key.keyId
            setHeader("typ", "JWT")
            headers.forEach { (name, value) -> setHeader(name, value) }
            this.key = key.privateKey
            this.payload = mapper.writeValueAsString(payload)
        }.compactSerialization
    }
}
