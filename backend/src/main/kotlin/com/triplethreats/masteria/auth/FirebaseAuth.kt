package com.triplethreats.masteria.auth

import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.triplethreats.masteria.ApiException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.net.URI
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit

/** The parts of a verified Firebase ID token the backend uses. */
data class FirebaseIdentity(
    val uid: String,
    val email: String?,
    val emailVerified: Boolean,
    val name: String?,
    /** "password", "google.com", ... */
    val signInProvider: String?,
)

fun interface IdTokenVerifier {
    /** Returns the identity, or throws [ApiException] 401 when the token is invalid or expired. */
    suspend fun verify(idToken: String): FirebaseIdentity
}

/**
 * Verifies Firebase Authentication ID tokens as documented by Firebase ("Verify ID tokens using a
 * third-party JWT library"): RS256, signed by one of Google's rotating securetoken keys, issuer
 * `https://securetoken.google.com/<projectId>`, audience `<projectId>`, non-empty subject, not expired.
 * Only the public project id is needed, so no service-account secret has to live on the server.
 */
class FirebaseTokenVerifier(
    private val projectId: String,
    private val keys: JwkProvider = JwkProviderBuilder(URI(JWK_URL).toURL())
        .cached(10, 6, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .timeouts(5_000, 5_000)
        .build(),
) : IdTokenVerifier {
    private val log = LoggerFactory.getLogger(FirebaseTokenVerifier::class.java)

    override suspend fun verify(idToken: String): FirebaseIdentity = withContext(Dispatchers.IO) {
        val decoded = runCatching { JWT.decode(idToken.trim()) }.getOrNull() ?: invalid()
        if (decoded.algorithm != "RS256") invalid()
        val kid = decoded.keyId ?: invalid()
        val key = try {
            keys.get(kid).publicKey as RSAPublicKey
        } catch (e: Exception) {
            log.warn("Firebase signing key {} unavailable: {}", kid, e.javaClass.simpleName)
            throw ApiException(HttpStatusCode.Unauthorized, "Your sign-in has expired. Please sign in again.")
        }
        val jwt = try {
            JWT.require(Algorithm.RSA256(key, null))
                .withIssuer("https://securetoken.google.com/$projectId")
                .withAudience(projectId)
                .acceptLeeway(60)
                .build()
                .verify(idToken.trim())
        } catch (e: JWTVerificationException) {
            invalid()
        }
        val uid = jwt.subject?.takeIf { it.isNotBlank() && it.length <= 128 } ?: invalid()
        val authTime = jwt.getClaim("auth_time").asLong()
        if (authTime != null && authTime > System.currentTimeMillis() / 1000 + 60) invalid()
        @Suppress("UNCHECKED_CAST")
        val firebase = jwt.getClaim("firebase").asMap() as? Map<String, Any?>
        FirebaseIdentity(
            uid = uid,
            email = jwt.getClaim("email").asString(),
            emailVerified = jwt.getClaim("email_verified").asBoolean() ?: false,
            name = jwt.getClaim("name").asString(),
            signInProvider = firebase?.get("sign_in_provider") as? String,
        )
    }

    private fun invalid(): Nothing = throw ApiException(HttpStatusCode.Unauthorized, "Your sign-in has expired. Please sign in again.")

    companion object {
        const val JWK_URL = "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com"
    }
}
