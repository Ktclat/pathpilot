package dev.pathpilot.app

import dev.pathpilot.app.auth.CodexLoopbackListener
import dev.pathpilot.core.codex.BrowserLoginChallenge
import dev.pathpilot.core.codex.CodexOAuthClient
import dev.pathpilot.core.codex.CodexTokens
import dev.pathpilot.core.codex.DeviceLoginChallenge
import dev.pathpilot.core.network.OutboundProxyConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class HttpCodexLoginSession(proxy: OutboundProxyConfig) : CodexLoginSession {
    private val client = CodexOAuthClient(proxyProvider = { proxy })
    private var listener: CodexLoopbackListener? = null
    private var challenge: BrowserLoginChallenge? = null
    private var cancelled = false

    override suspend fun beginBrowser(): String = withContext(Dispatchers.IO) {
        synchronized(this@HttpCodexLoginSession) {
            if (cancelled) throw CancellationException()
            val callback = CodexLoopbackListener.open()
            listener = callback
            client.beginBrowserLogin(callback.redirectUri).also { challenge = it }.authorizationUrl
        }
    }

    override suspend fun awaitBrowserTokens(): CodexTokens = withContext(Dispatchers.IO) {
        val (callback, authorization) = synchronized(this@HttpCodexLoginSession) {
            if (cancelled) throw CancellationException()
            requireNotNull(listener) to requireNotNull(challenge)
        }
        val url = callback.awaitCallback(authorization.expiresAtEpochSeconds, authorization::matchesCallback)
        client.completeBrowserLogin(authorization, url)
    }

    override suspend fun beginDevice() = withContext(Dispatchers.IO) { client.beginDeviceLogin() }
    override suspend fun pollDevice(challenge: DeviceLoginChallenge) = withContext(Dispatchers.IO) { client.pollDeviceLogin(challenge) }

    @Synchronized override fun cancel() {
        cancelled = true
        listener?.close()
        listener = null
        challenge = null
        client.cancel()
    }
}
