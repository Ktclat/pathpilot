package dev.pathpilot.app.auth

import java.io.IOException
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class CodexLoopbackListenerTest {
    private val worker = Executors.newSingleThreadExecutor()
    private val listeners = mutableListOf<CodexLoopbackListener>()
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    @After fun closeResources() {
        listeners.forEach { it.close() }
        worker.shutdownNow()
        assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
    }

    @Test fun `callback retains encoded query and browser page contains no credential material`() {
        val listener = listener()
        val result = await(listener)
        val target = "/auth/callback?code=test-secret%2Bcode%2Fvalue&state=expected"
        val response = request(listener, target)
        assertTrue(response.startsWith("HTTP/1.1 200"))
        assertFalse(response.contains("test-secret"))
        assertFalse(response.contains("expected"))
        assertEquals("127.0.0.1", URI(listener.redirectUri).host)
        assertEquals(listener.redirectUri + target.substringAfter("/auth/callback"), result.get(2, TimeUnit.SECONDS))
        assertPortClosed(listener)
    }

    @Test fun `invalid state method and path do not consume the browser login attempt`() {
        val listener = listener()
        val result = await(listener)
        assertTrue(request(listener, "/auth/callback?code=wrong&state=other").startsWith("HTTP/1.1 400"))
        assertFalse(result.isDone)
        assertTrue(request(listener, "/favicon.ico").startsWith("HTTP/1.1 404"))
        assertTrue(request(listener, "/auth/callback?state=expected", "POST").startsWith("HTTP/1.1 405"))
        assertTrue(request(listener, "http://elsewhere.invalid/auth/callback?state=expected").startsWith("HTTP/1.1 404"))
        val target = "/auth/callback?error=access_denied&state=expected"
        assertTrue(request(listener, target).startsWith("HTTP/1.1 200"))
        assertEquals(listener.redirectUri + "?error=access_denied&state=expected", result.get(2, TimeUnit.SECONDS))
    }

    @Test fun `occupied primary port uses the fallback without disturbing the current owner`() {
        ServerSocket(0, 1, loopback).use { occupied ->
            val listener = CodexLoopbackListener.bind(listOf(occupied.localPort, 0)).also(listeners::add)
            assertNotEquals(occupied.localPort, URI(listener.redirectUri).port)
            assertFalse(occupied.isClosed)
            val result = await(listener)
            assertTrue(request(listener, "/auth/callback?code=test&state=expected").startsWith("HTTP/1.1 200"))
            result.get(2, TimeUnit.SECONDS)
            assertFalse(occupied.isClosed)
        }
    }

    @Test fun `two occupied ports fail instead of choosing an unexpected public listener`() {
        ServerSocket(0, 1, loopback).use { first ->
            ServerSocket(0, 1, loopback).use { second ->
                assertThrows(BindException::class.java) { CodexLoopbackListener.bind(listOf(first.localPort, second.localPort)) }
                assertFalse(first.isClosed)
                assertFalse(second.isClosed)
            }
        }
    }

    @Test fun `close releases an accept wait and is safe to repeat`() {
        val listener = listener()
        val result = await(listener)
        listener.close()
        listener.close()
        val error = assertThrows(ExecutionException::class.java) { result.get(2, TimeUnit.SECONDS) }
        assertTrue(error.cause is SocketException)
        assertPortClosed(listener)
    }

    @Test fun `close releases a partial request and its waiting call`() {
        val listener = listener()
        val result = await(listener)
        Socket().use { socket ->
            socket.connect(InetSocketAddress(loopback, URI(listener.redirectUri).port), 1_000)
            socket.soTimeout = 1_000
            socket.getOutputStream().apply { write("GET /auth/callback HTTP/1.1\r\nHost:".toByteArray()); flush() }
            listener.close()
            val error = assertThrows(ExecutionException::class.java) { result.get(2, TimeUnit.SECONDS) }
            assertTrue(error.cause is SocketException)
            val read = runCatching { socket.getInputStream().read() }
            assertTrue(read.getOrNull() == -1 || read.exceptionOrNull() is SocketException)
        }
        assertPortClosed(listener)
    }

    @Test fun `lifetime and already expired challenge both close the listening port`() {
        val listener = CodexLoopbackListener.bind(listOf(0), lifetimeMillis = 30).also(listeners::add)
        val result = await(listener)
        val error = assertThrows(ExecutionException::class.java) { result.get(2, TimeUnit.SECONDS) }
        assertTrue(error.cause is SocketTimeoutException)
        assertPortClosed(listener)

        val expired = listener()
        assertThrows(SocketTimeoutException::class.java) {
            expired.awaitCallback(System.currentTimeMillis() / 1_000 - 1) { true }
        }
        assertPortClosed(expired)
    }

    private fun listener() = CodexLoopbackListener.bind(listOf(0)).also(listeners::add)

    private fun await(listener: CodexLoopbackListener): Future<String> = worker.submit<String> {
        listener.awaitCallback(System.currentTimeMillis() / 1_000 + 900) { it.endsWith("state=expected") }
    }

    private fun request(listener: CodexLoopbackListener, target: String, method: String = "GET"): String = Socket().use { socket ->
        val port = URI(listener.redirectUri).port
        socket.connect(InetSocketAddress(loopback, port), 1_000)
        socket.soTimeout = 2_000
        socket.getOutputStream().apply {
            write("$method $target HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            flush()
        }
        socket.getInputStream().bufferedReader(Charsets.UTF_8).readText()
    }

    private fun assertPortClosed(listener: CodexLoopbackListener) {
        assertThrows(IOException::class.java) {
            Socket().use { it.connect(InetSocketAddress(loopback, URI(listener.redirectUri).port), 1_000) }
        }
    }
}
