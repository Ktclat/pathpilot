package dev.pathpilot.app.auth

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One browser login attempt. The owner must close this listener when its coroutine is cancelled. */
class CodexLoopbackListener private constructor(
    private val server: ServerSocket,
    private val lifetimeDeadlineNanos: Long,
) : Closeable {
    val redirectUri: String = "http://127.0.0.1:${server.localPort}/auth/callback"
    private val closed = AtomicBoolean(false)
    private val awaiting = AtomicBoolean(false)
    private val activeSocket = AtomicReference<Socket?>(null)

    /**
     * Blocks on IO. The core challenge's matchesCallback predicate checks origin/path/state;
     * rejected requests do not consume the attempt. Code/error exchange remains the core's job.
     */
    fun awaitCallback(expiresAtEpochSeconds: Long, acceptsCallback: (String) -> Boolean): String {
        check(awaiting.compareAndSet(false, true)) { "A callback wait is already active" }
        try {
            ensureOpen()
            val nowMillis = System.currentTimeMillis()
            val nowSeconds = nowMillis / 1_000
            if (expiresAtEpochSeconds <= nowSeconds) throw timeout()
            val challengeMillis = minOf(expiresAtEpochSeconds, nowSeconds + MAX_LIFETIME_SECONDS) * 1_000 - nowMillis
            val deadline = minOf(lifetimeDeadlineNanos,
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(challengeMillis))
            while (true) {
                ensureOpen()
                server.soTimeout = remainingMillis(deadline)
                val connection = server.accept()
                activeSocket.set(connection)
                try {
                    connection.use { socket ->
                        ensureOpen()
                        val head = try {
                            readHead(socket, minOf(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(5)))
                        } catch (_: SocketTimeoutException) {
                            ensureOpen()
                            remainingMillis(deadline)
                            respond(socket, 408, "Request Timeout", "请重新打开当前登录页面。")
                            return@use
                        } catch (_: InvalidRequest) {
                            respond(socket, 400, "Bad Request", "此请求无效。")
                            return@use
                        } catch (_: IOException) {
                            // A browser may abandon a preconnected socket before sending HTTP.
                            ensureOpen()
                            return@use
                        } ?: return@use
                        val parts = head.substringBefore("\r\n").split(' ')
                        if (parts.size != 3 || parts[2] !in setOf("HTTP/1.0", "HTTP/1.1")) {
                            respond(socket, 400, "Bad Request", "此请求无效。")
                            return@use
                        }
                        if (parts[0] != "GET") {
                            respond(socket, 405, "Method Not Allowed", "此地址只接受登录回调。")
                            return@use
                        }
                        val target = try { URI(parts[1]) } catch (_: URISyntaxException) { null }
                        if (target == null || target.isAbsolute || target.rawAuthority != null || target.rawFragment != null ||
                            target.rawPath != "/auth/callback") {
                            respond(socket, 404, "Not Found", "此地址不可用。")
                            return@use
                        }
                        val callbackUrl = redirectUri + (target.rawQuery?.let { "?$it" } ?: "")
                        if (!acceptsCallback(callbackUrl)) {
                            respond(socket, 400, "Bad Request", "请使用当前登录页面。")
                            return@use
                        }
                        remainingMillis(deadline)
                        respond(socket, 200, "OK", "请返回 PathPilot 完成登录。")
                        ensureOpen()
                        return callbackUrl
                    }
                } finally {
                    activeSocket.compareAndSet(connection, null)
                }
            }
        } finally {
            close()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { activeSocket.getAndSet(null)?.close() } finally { server.close() }
    }

    private fun ensureOpen() {
        if (closed.get()) throw SocketException("Codex callback listener is closed")
    }

    private fun readHead(socket: Socket, deadline: Long): String? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(1_024)
        while (output.size() < MAX_HEADER_BYTES) {
            socket.soTimeout = remainingMillis(deadline)
            val count = socket.getInputStream().read(buffer, 0, minOf(buffer.size, MAX_HEADER_BYTES - output.size()))
            if (count < 0) return null
            output.write(buffer, 0, count)
            val head = output.toString(Charsets.US_ASCII.name())
            if (head.contains("\r\n\r\n")) return head.substringBefore("\r\n\r\n")
        }
        throw InvalidRequest()
    }

    private fun respond(socket: Socket, status: Int, reason: String, message: String) {
        val body = "<!doctype html><html><head><meta charset=\"utf-8\"><title>PathPilot</title></head><body><p>$message</p></body></html>"
            .toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 $status $reason\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\n\r\n"
        try {
            socket.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); write(body); flush() }
        } catch (_: IOException) {
            // Rendering the acknowledgment is optional; the validated callback has already arrived.
            ensureOpen()
        }
    }

    private class InvalidRequest : IOException()

    companion object {
        private const val MAX_LIFETIME_SECONDS = 15L * 60
        private const val MAX_HEADER_BYTES = 16 * 1_024

        fun open(): CodexLoopbackListener = bind(listOf(1455, 1457))

        /** Internal port/lifetime seam for socket tests; production always uses open(). */
        internal fun bind(ports: List<Int>, lifetimeMillis: Long = MAX_LIFETIME_SECONDS * 1_000): CodexLoopbackListener {
            require(ports.isNotEmpty() && lifetimeMillis in 1..MAX_LIFETIME_SECONDS * 1_000)
            var lastError: BindException? = null
            for (port in ports) {
                val socket = ServerSocket()
                try {
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port), 4)
                    return CodexLoopbackListener(socket, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(lifetimeMillis))
                } catch (error: BindException) {
                    lastError = error
                    socket.close()
                } catch (error: Exception) {
                    socket.close()
                    throw error
                }
            }
            throw BindException("Codex callback ports are in use").apply { initCause(lastError) }
        }

        private fun remainingMillis(deadline: Long): Int {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw timeout()
            return TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        private fun timeout() = SocketTimeoutException("Codex browser login timed out")
    }
}
