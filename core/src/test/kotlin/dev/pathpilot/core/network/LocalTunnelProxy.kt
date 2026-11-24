package dev.pathpilot.core.network

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test-only byte tunnel. Every requested destination is sent to [target], so tests can
 * exercise real CONNECT/SOCKS handshakes against a TLS MockWebServer without Internet access.
 * TLS remains end-to-end between the tested client and that server; this helper never decrypts it.
 */
internal class LocalTunnelProxy(
    val mode: Mode,
    private val target: InetSocketAddress,
) : Closeable {
    enum class Mode { HTTP_CONNECT, SOCKS5 }

    data class TunnelRequest(val host: String, val port: Int, val remoteDns: Boolean)

    val requests: BlockingQueue<TunnelRequest> = LinkedBlockingQueue()
    /** Unexpected handshake/connect failures are retained for test diagnostics, never printed. */
    val failures: BlockingQueue<Throwable> = LinkedBlockingQueue()

    private val closed = AtomicBoolean(false)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val threadNumber = AtomicInteger()
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "pathpilot-test-proxy-${threadNumber.incrementAndGet()}").apply { isDaemon = true }
    }
    private val listener = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress("127.0.0.1", 0))
    }
    val port: Int get() = listener.localPort
    val address: InetSocketAddress get() = InetSocketAddress("127.0.0.1", port)

    init { executor.execute(::acceptConnections) }

    private fun acceptConnections() {
        while (!closed.get()) {
            val client = try { listener.accept() } catch (error: IOException) {
                if (!closed.get()) failures.offer(error)
                return
            }
            if (closed.get()) { client.closeQuietly(); return }
            sockets.add(client)
            try {
                executor.execute { tunnel(client) }
            } catch (_: RejectedExecutionException) {
                sockets.remove(client)
                client.closeQuietly()
            }
        }
    }

    private fun tunnel(client: Socket) {
        val upstream = Socket()
        sockets.add(upstream)
        val connectionClosed = AtomicBoolean(false)
        fun closeConnection() {
            if (connectionClosed.compareAndSet(false, true)) {
                client.closeQuietly()
                upstream.closeQuietly()
                sockets.remove(client)
                sockets.remove(upstream)
            }
        }
        try {
            client.soTimeout = 5_000
            val request = when (mode) {
                Mode.HTTP_CONNECT -> readConnect(client.getInputStream())
                Mode.SOCKS5 -> readSocks(client)
            }
            requests.put(request)
            upstream.connect(target, 3_000)
            when (mode) {
                Mode.HTTP_CONNECT -> client.getOutputStream().apply {
                    write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    flush()
                }
                Mode.SOCKS5 -> client.getOutputStream().apply {
                    // Version, success, reserved, IPv4, bound-address 0.0.0.0, bound-port 0.
                    write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
                    flush()
                }
            }
            client.soTimeout = 0
            executor.execute {
                try { upstream.getInputStream().copyTo(client.getOutputStream()) }
                catch (_: IOException) { /* Closing either end is an expected tunnel termination. */ }
                finally { closeConnection() }
            }
            try { client.getInputStream().copyTo(upstream.getOutputStream()) }
            catch (_: IOException) { /* Includes deliberate cancellation in the client under test. */ }
        } catch (error: Exception) {
            if (!closed.get() && !connectionClosed.get()) failures.offer(error)
        } finally { closeConnection() }
    }

    /** Reads one byte at a time so a pipelined TLS ClientHello is not consumed with the header. */
    private fun readConnect(input: InputStream): TunnelRequest {
        val header = StringBuilder()
        while (true) {
            val value = input.read()
            if (value == -1) throw IOException("CONNECT header ended early")
            header.append(value.toChar())
            if (header.length > MAX_HEADER_BYTES) throw IOException("CONNECT header exceeds test limit")
            if (header.endsWith("\r\n\r\n")) break
        }
        val firstLine = header.toString().substringBefore("\r\n").split(' ')
        if (firstLine.size != 3 || firstLine[0] != "CONNECT" || !firstLine[2].startsWith("HTTP/"))
            throw IOException("Expected an HTTP CONNECT request")
        val authority = firstLine[1]
        val host: String
        val portText: String
        if (authority.startsWith('[')) {
            val end = authority.indexOf(']')
            if (end < 2 || authority.getOrNull(end + 1) != ':') throw IOException("Invalid CONNECT authority")
            host = authority.substring(1, end)
            portText = authority.substring(end + 2)
        } else {
            val delimiter = authority.lastIndexOf(':')
            if (delimiter <= 0) throw IOException("CONNECT port is missing")
            host = authority.substring(0, delimiter)
            portText = authority.substring(delimiter + 1)
        }
        val requestedPort = portText.toIntOrNull()
        if (host.isBlank() || '@' in host || requestedPort == null || requestedPort !in 1..65535)
            throw IOException("Invalid CONNECT destination")
        return TunnelRequest(host, requestedPort, remoteDns = true)
    }

    private fun readSocks(client: Socket): TunnelRequest {
        val input = client.getInputStream()
        val output = client.getOutputStream()
        if (input.readByte() != 5) throw IOException("Expected SOCKS5 greeting")
        val methodCount = input.readByte()
        if (methodCount == 0) throw IOException("SOCKS5 greeting has no authentication methods")
        val methods = input.readExactly(methodCount)
        val hasNoAuthentication = methods.any { it.toInt() == 0 }
        output.write(byteArrayOf(5, if (hasNoAuthentication) 0 else 0xff.toByte()))
        output.flush()
        if (!hasNoAuthentication) throw IOException("Test proxy supports SOCKS5 without authentication only")
        if (input.readByte() != 5 || input.readByte() != 1 || input.readByte() != 0)
            throw IOException("Expected SOCKS5 CONNECT command")
        val addressType = input.readByte()
        val host = when (addressType) {
            1 -> InetAddress.getByAddress(input.readExactly(4)).hostAddress
            3 -> {
                val length = input.readByte()
                if (length == 0) throw IOException("SOCKS5 hostname is empty")
                String(input.readExactly(length), Charsets.US_ASCII)
            }
            4 -> InetAddress.getByAddress(input.readExactly(16)).hostAddress
            else -> throw IOException("Unsupported SOCKS5 address type")
        }
        val requestedPort = (input.readByte() shl 8) or input.readByte()
        if (requestedPort == 0) throw IOException("SOCKS5 destination port is zero")
        return TunnelRequest(host, requestedPort, remoteDns = addressType == 3)
    }

    private fun InputStream.readByte(): Int = read().takeIf { it >= 0 }
        ?: throw IOException("SOCKS5 handshake ended early")

    private fun InputStream.readExactly(length: Int): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = read(result, offset, length - offset)
            if (count < 0) throw IOException("SOCKS5 handshake ended early")
            offset += count
        }
        return result
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { listener.close() } catch (_: IOException) { }
        sockets.toList().forEach { it.closeQuietly() }
        executor.shutdownNow()
        try { executor.awaitTermination(2, TimeUnit.SECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        sockets.clear()
    }

    private fun Socket.closeQuietly() { try { close() } catch (_: IOException) { } }

    private companion object { const val MAX_HEADER_BYTES = 8 * 1024 }
}
