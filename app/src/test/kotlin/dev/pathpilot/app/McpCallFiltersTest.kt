package dev.pathpilot.app

import dev.pathpilot.core.mcp.McpCallEvent
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class McpCallFiltersTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    // This local date begins and ends at different UTC offsets because of DST.
    private val now = epoch("2026-03-08T19:00:00Z")

    @Test fun dateRangesUseLocalCalendarBoundariesIncludingDst() {
        val tomorrow = event("2026-03-09T07:00:00Z")
        val endOfToday = event("2026-03-09T06:59:59Z")
        val startOfToday = event("2026-03-08T08:00:00Z")
        val yesterday = event("2026-03-08T07:59:59Z")
        val seventh = event("2026-03-02T08:00:00Z")
        val eighth = event("2026-03-02T07:59:59Z")
        val thirtieth = event("2026-02-07T08:00:00Z")
        val thirtyFirst = event("2026-02-07T07:59:59Z")
        val events = listOf(tomorrow, endOfToday, startOfToday, yesterday, seventh, eighth, thirtieth, thirtyFirst)
        assertEquals(listOf(endOfToday, startOfToday), filterMcpCalls(events, UsageRange.TODAY, nowEpochMs = now, zone = zone))
        assertEquals(events.subList(1, 5), filterMcpCalls(events, UsageRange.LAST_7_DAYS, nowEpochMs = now, zone = zone))
        assertEquals(events.subList(1, 7), filterMcpCalls(events, UsageRange.LAST_30_DAYS, nowEpochMs = now, zone = zone))
        assertEquals(events, filterMcpCalls(events, UsageRange.ALL, nowEpochMs = now, zone = zone))
    }

    @Test fun connectionAndToolSearchCombineWithoutDroppingFailedCallsOrRenamedConnections() {
        val failure = event("2026-03-08T19:00:00Z").copy(
            connectionName = "Old Home Tools", toolName = "List_Files", success = false, errorCode = "tool_error",
        )
        val events = listOf(failure, failure.copy(toolName = "get_file"), failure.copy(connectionName = "Office"))
        assertEquals(listOf(failure), filterMcpCalls(events, UsageRange.ALL, " home ", "LIST_", now, zone))
        assertEquals(emptyList<McpCallEvent>(), filterMcpCalls(events, UsageRange.TODAY, "missing", nowEpochMs = now, zone = zone))
    }

    private fun event(at: String) = McpCallEvent("connection", "Local tools", "echo", true, 2, startedAtEpochMs = epoch(at))
    private fun epoch(value: String) = Instant.parse(value).toEpochMilli()
}
