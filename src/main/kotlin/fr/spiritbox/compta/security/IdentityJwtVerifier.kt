package fr.spiritbox.compta.security

import io.quarkus.runtime.Startup
import io.smallrye.jwt.algorithm.SignatureAlgorithm
import io.smallrye.jwt.auth.principal.DefaultJWTParser
import io.smallrye.jwt.auth.principal.JWTAuthContextInfo
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.rest.client.inject.RestClient
import org.jose4j.jwk.EllipticCurveJsonWebKey
import org.jose4j.jwk.JsonWebKeySet
import org.jose4j.jws.JsonWebSignature
import java.time.Instant

/** Verifies ES256 with Identity's public JWKS, fetched only from the configured REST client. */
@Startup
@ApplicationScoped
class IdentityJwtVerifier(
    @RestClient private val identity: IdentitySessionClient,
    @ConfigProperty(name = "auth.jwt.issuer") private val issuer: String,
    @ConfigProperty(name = "auth.jwt.audience") private val audience: String,
) {
    private data class KeyCache(val parsers: Map<String, DefaultJWTParser>, val expiresAt: Long)

    private var cache: KeyCache? = null
    private var refresh: Uni<KeyCache>? = null
    private var nextForcedRefreshAt = 0L

    init {
        require(issuer.isNotBlank() && audience.isNotBlank()) { "JWT_ISSUER_AND_AUDIENCE_REQUIRED" }
    }

    /** Returns only a verified subject; live session data remains the source of permissions. */
    fun subject(token: String): Uni<String?> {
        val kid = keyId(token) ?: return Uni.createFrom().nullItem()
        return keys(kid).map { keys ->
            val parser = keys.parsers[kid] ?: return@map null
            try {
                val jwt = parser.parse(token)
                if (jwt.issuedAtTime > Instant.now().epochSecond || jwt.expirationTime <= jwt.issuedAtTime) return@map null
                jwt.subject?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                // Do not log token contents or expose parsing/signature errors.
                null
            }
        }
    }

    private fun keyId(token: String): String? {
        if (token.length !in 1..16384) return null
        return try {
            val header = JsonWebSignature().apply { compactSerialization = token }
            if (header.algorithmHeaderValue != "ES256" || header.getHeader("typ") != "JWT") return null
            if (FORBIDDEN_HEADERS.any { header.headers.getObjectHeaderValue(it) != null }) return null
            header.keyIdHeaderValue?.takeIf { it.isNotBlank() && it.length <= 128 }
        } catch (_: Exception) {
            null
        }
    }

    /** Coalesces concurrent downloads and limits refreshes triggered by unknown key IDs. */
    @Synchronized
    private fun keys(kid: String): Uni<KeyCache> {
        val now = System.nanoTime()
        val current = cache
        if (current != null && now < current.expiresAt && kid in current.parsers) return Uni.createFrom().item(current)
        refresh?.let { return it }
        if (current != null && now < current.expiresAt) {
            if (now < nextForcedRefreshAt) return Uni.createFrom().item(current)
            nextForcedRefreshAt = now + FORCED_REFRESH_NANOS
        }
        val pending = identity.publicKeys().map { response ->
            response.use {
                check(it.status == 200) { "IDENTITY_JWKS_UNAVAILABLE" }
                parseKeys(it.readEntity(String::class.java))
            }
        }.invoke { loaded ->
            synchronized(this) { cache = loaded }
        }.onTermination().invoke { _, _, _ ->
            synchronized(this) { refresh = null }
        }.memoize().indefinitely()
        refresh = pending
        return pending
    }

    private fun parseKeys(json: String): KeyCache {
        try {
            require(json.length <= 65536)
            val keys = JsonWebKeySet(json).jsonWebKeys
            require(keys.isNotEmpty() && keys.map { it.keyId }.distinct().size == keys.size)
            val parsers = keys.associate { key ->
                require(key is EllipticCurveJsonWebKey && key.curveName == "P-256" && key.privateKey == null)
                require(key.algorithm == "ES256" && key.use == "sig")
                require(!key.keyId.isNullOrBlank() && key.keyId.length <= 128)
                key.keyId to DefaultJWTParser(JWTAuthContextInfo().apply {
                    publicVerificationKey = key.publicKey
                    issuedBy = issuer
                    expectedAudience = setOf(audience)
                    signatureAlgorithm = setOf(SignatureAlgorithm.ES256)
                    isRelaxVerificationKeyValidation = false
                    clockSkew = 0
                    requiredClaims = setOf("iss", "sub", "aud", "exp", "iat", "nbf", "jti", "sid", "av", "roles", "permissions")
                })
            }
            return KeyCache(parsers, System.nanoTime() + CACHE_NANOS)
        } catch (_: Exception) {
            throw IllegalStateException("IDENTITY_JWKS_INVALID")
        }
    }

    private companion object {
        const val CACHE_NANOS = 300_000_000_000L
        const val FORCED_REFRESH_NANOS = 30_000_000_000L
        val FORBIDDEN_HEADERS = setOf("jku", "jwk", "x5u", "x5c", "crit", "b64")
    }
}
