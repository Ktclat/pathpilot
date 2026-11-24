package dev.pathpilot.app.runtime

import dev.pathpilot.core.RequestEvent
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RouterRuntimeTest {
    private val snapshot = LocalAddressSnapshot(listOf(LocalAddress("192.168.1.8", "wlan0", LocalAddressKind.WIFI)))

    @Before fun reset() { RouterRuntime.stopped() }
    @After fun finish() { RouterRuntime.stopped() }

    @Test fun repeatedStartDoesNotClaimASecondListener() {
        assertTrue(RouterRuntime.starting())
        assertFalse(RouterRuntime.starting())
        assertTrue(RouterRuntime.started(8317, snapshot))
        assertFalse(RouterRuntime.starting())
        assertTrue(RouterRuntime.state.value.running)
    }

    @Test fun stopDuringStartupRejectsLateStartedAndRestartUntilCleanupCompletes() {
        assertTrue(RouterRuntime.starting())
        assertTrue(RouterRuntime.stopping())
        assertTrue(RouterRuntime.state.value.stopping)
        assertFalse(RouterRuntime.state.value.starting)
        assertFalse(RouterRuntime.started(8317, snapshot))
        assertFalse(RouterRuntime.starting())
        assertFalse(RouterRuntime.stopping())
        RouterRuntime.stopped()
        assertTrue(RouterRuntime.starting())
        assertTrue(RouterRuntime.started(8317, snapshot))
    }

    @Test fun stoppingLiveServerRejectsLateAddressAndRequestUpdates() {
        RouterRuntime.starting()
        RouterRuntime.started(8317, snapshot)
        RouterRuntime.record(RequestEvent("model", "provider", 200, 20, "/v1/responses"))
        assertTrue(RouterRuntime.stopping())
        assertFalse(RouterRuntime.state.value.running)
        assertFalse(RouterRuntime.starting())
        RouterRuntime.addressesChanged(LocalAddressSnapshot(emptyList()))
        RouterRuntime.record(RequestEvent("late", "provider", 200, 20, "/v1/responses"))
        assertEquals(snapshot.addresses, RouterRuntime.state.value.addresses)
        assertEquals(1L, RouterRuntime.state.value.requestCount)
        RouterRuntime.stopped()
        assertFalse(RouterRuntime.state.value.stopping)
    }

    @Test fun failedStartCanBeRetriedAndAddressWarningsRemainSeparate() {
        RouterRuntime.starting()
        RouterRuntime.stopped("端口已被占用")
        assertEquals("端口已被占用", RouterRuntime.state.value.lastError)
        assertTrue(RouterRuntime.starting())
        assertNull(RouterRuntime.state.value.lastError)
        RouterRuntime.started(8318, snapshot.copy(warning = "部分网络信息无法读取"))
        assertTrue(RouterRuntime.state.value.running)
        assertEquals("部分网络信息无法读取", RouterRuntime.state.value.addressWarning)
        assertNull(RouterRuntime.state.value.lastError)
    }
}
