package com.tankecho.quotaview.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvidersTest {

    @Test
    fun codexParseAssignsStableWindowKeys() {
        val status = CodexApi.parse(
            """
            {
              "plan_type": "pro",
              "rate_limit": {
                "primary_window": {
                  "used_percent": 28,
                  "limit_window_seconds": 18000,
                  "reset_at": 2000000000
                },
                "secondary_window": {
                  "used_percent": 42,
                  "limit_window_seconds": 604800,
                  "reset_at": 2000500000
                }
              },
              "additional_rate_limits": [{
                "limit_name": "Spark",
                "rate_limit": {"primary_window": {
                  "used_percent": 7,
                  "limit_window_seconds": 604800,
                  "reset_at": 2000500000
                }}
              }]
            }
            """.trimIndent()
        )

        assertNull(status.error)
        assertEquals("PRO", status.plan)
        assertEquals(listOf("primary", "week", "additional"), status.windows.map { it.selectionKey })
        assertEquals(listOf("5h 窗口", "周窗口", "Spark"), status.windows.map { it.label })
    }

    @Test
    fun glmParseConvertsMillisecondsAndMcpSemantics() {
        val status = GlmApi.parse(
            """
            {
              "code": 200,
              "data": {
                "level": "max",
                "limits": [
                  {"type":"TOKENS_LIMIT","unit":6,"percentage":15,"nextResetTime":2000604800000},
                  {"type":"TOKENS_LIMIT","unit":3,"percentage":6,"nextResetTime":2000018000000},
                  {"type":"TIME_LIMIT","unit":5,"usage":4000,"currentValue":1000,"remaining":3000,"percentage":25,"nextResetTime":2002600000000}
                ]
              }
            }
            """.trimIndent()
        )

        assertEquals("Max", status.plan)
        assertEquals(listOf("primary", "week", "mcp"), status.windows.map { it.selectionKey })
        assertEquals(2_000_018_000L, status.windows[0].resetAt)
        assertEquals(25, status.windows[2].usedPercent)
        assertEquals(QuotaWindow.Kind.TOOL_CALLS, status.windows[2].kind)
        assertTrue(status.windows[2].label.contains("余3000"))
    }

    @Test
    fun quotaWindowCalculatesPaceFromElapsedTime() {
        val now = now()
        val window = QuotaWindow(
            label = "test",
            usedPercent = 25,
            resetAt = now + 500,
            windowSeconds = 1000,
        )

        assertTrue(window.timeElapsedPercent in 49..51)
        assertTrue(window.pace!! in 0.49f..0.52f)
    }
}
