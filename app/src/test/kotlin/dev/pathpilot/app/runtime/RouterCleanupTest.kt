package dev.pathpilot.app.runtime

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class RouterCleanupTest {
    @Test fun cancelledOwnerClosesListenerAfterReturningFromUiDispatcherBeforeReportingStopped() = runBlocking {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { ui ->
                ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
                    val address = InetSocketAddress(InetAddress.getLoopbackAddress(), listener.localPort)
                    val listening = CompletableDeferred<Unit>()
                    val events = mutableListOf<String>()
                    val owner = launch(io) {
                        try {
                            listening.complete(Unit)
                            awaitCancellation()
                        } finally {
                            releaseRouterResources(
                                markStopping = { withContext(ui) { events += "stopping" } },
                                stopServer = { listener.close(); events += "listener closed" },
                                releaseWifiLock = { events += "wifi released" },
                                releaseWakeLock = { events += "wake released" },
                                onReleased = { error -> withContext(ui) {
                                    assertNull(error)
                                    assertTrue(listener.isClosed)
                                    events += "stopped"
                                } },
                            )
                        }
                    }
                    withTimeout(5_000) { listening.await(); owner.cancelAndJoin() }
                    assertEquals(listOf("stopping", "listener closed", "wifi released", "wake released", "stopped"), events)
                    assertThrows(IOException::class.java) { Socket().use { it.connect(address, 1_000) } }
                    Unit
                }
            }
        }
    }

    @Test fun releaseFailureStillReleasesRemainingLocksAndReportsFailure() = runBlocking {
        val failure = IllegalStateException("listener close failed")
        val events = mutableListOf<String>()
        var reported: Exception? = null
        releaseRouterResources(
            markStopping = { events += "stopping" },
            stopServer = { throw failure },
            releaseWifiLock = { events += "wifi released" },
            releaseWakeLock = { events += "wake released" },
            onReleased = { reported = it; events += "completed" },
        )
        assertSame(failure, reported)
        assertEquals(listOf("stopping", "wifi released", "wake released", "completed"), events)
    }
}
