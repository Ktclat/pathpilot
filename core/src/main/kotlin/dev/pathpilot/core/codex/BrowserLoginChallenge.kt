package dev.pathpilot.core.codex

import java.util.concurrent.atomic.AtomicBoolean
import java.security.MessageDigest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Keep this attempt only in memory: its URL contains state and its verifier is an OAuth secret. */
class BrowserLoginChallenge internal constructor(
    val authorizationUrl: String,
    val redirectUri: String,
    val expiresAtEpochSeconds: Long,
    internal val state: String,
    internal val codeVerifier: String,
) {
    private val consumed = AtomicBoolean(false)
    internal fun consume(): Boolean = consumed.compareAndSet(false, true)

    /** Allows the loopback listener to reject unrelated callbacks without consuming this attempt. */
    fun matchesCallback(callbackUrl: String): Boolean = validatedCallback(callbackUrl) != null

    internal fun validatedCallback(callbackUrl: String): HttpUrl? {
        val callback = callbackUrl.toHttpUrlOrNull() ?: return null
        val expected = redirectUri.toHttpUrl()
        if (callback.scheme != expected.scheme || callback.host != expected.host || callback.port != expected.port ||
            callback.encodedPath != expected.encodedPath || callback.username.isNotEmpty() || callback.password.isNotEmpty() ||
            callback.fragment != null) return null
        val received = callback.queryParameterValues("state").singleOrNull() ?: return null
        // Official onboarding allows exactly this suffix; arbitrary state prefixes are rejected.
        val normalized = received.removeSuffix(".onboarding_entrypoint=life_sciences")
        return callback.takeIf {
            MessageDigest.isEqual(normalized.toByteArray(Charsets.UTF_8), state.toByteArray(Charsets.UTF_8))
        }
    }

    override fun toString(): String = "BrowserLoginChallenge(<redacted>)"
}
