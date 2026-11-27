package dev.pathpilot.core.codex

import java.io.IOException

enum class CodexAuthStage { DEVICE_CODE, DEVICE_POLL, CODE_EXCHANGE, REFRESH, BROWSER_CALLBACK }

enum class CodexAuthError {
    NETWORK, TIMEOUT, RATE_LIMITED, UPSTREAM, UNAUTHORIZED, FORBIDDEN, DEVICE_LOGIN_UNAVAILABLE,
    EXPIRED, INVALID_RESPONSE, INVALID_CONFIGURATION, CANCELLED, STATE_MISMATCH, ACCESS_DENIED,
}

/** Display-safe diagnostics; upstream error bodies, callback URLs and credentials are never retained. */
class CodexAuthException(
    val httpStatus: Int?,
    message: String,
    val kind: CodexAuthError = CodexAuthError.UPSTREAM,
    val stage: CodexAuthStage? = null,
    val retryAfterSeconds: Long? = null,
) : IOException(message) {
    /** Only repeat the pending device poll, never an uncertain one-time code exchange. */
    val canRetryPolling: Boolean get() = stage == CodexAuthStage.DEVICE_POLL &&
        (kind in setOf(CodexAuthError.NETWORK, CodexAuthError.TIMEOUT, CodexAuthError.RATE_LIMITED) ||
            kind == CodexAuthError.UPSTREAM && httpStatus in 500..599)
}
