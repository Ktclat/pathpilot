package dev.pathpilot.app

import dev.pathpilot.core.mcp.McpCallEvent
import java.time.Instant
import java.time.ZoneId

internal fun filterMcpCalls(
    events: List<McpCallEvent>,
    range: UsageRange,
    connection: String = "",
    tool: String = "",
    nowEpochMs: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): List<McpCallEvent> {
    val today = Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()
    val days = when (range) {
        UsageRange.TODAY -> 0L
        UsageRange.LAST_7_DAYS -> 6L
        UsageRange.LAST_30_DAYS -> 29L
        UsageRange.ALL -> null
    }
    val from = days?.let { today.minusDays(it).atStartOfDay(zone).toInstant().toEpochMilli() }
    val before = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val connectionName = connection.trim()
    val toolName = tool.trim()
    return events.filter {
        (from == null || it.startedAtEpochMs in from until before) &&
            it.connectionName.contains(connectionName, ignoreCase = true) &&
            it.toolName.contains(toolName, ignoreCase = true)
    }
}
