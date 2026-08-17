package com.tankecho.quotaview.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

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

    @Test
    fun kimiParseSupportsSummaryAndFiveHourLimit() {
        val status = KimiApi.parse(
            """
            {
              "plan":"pro",
              "usage":{"used":300,"limit":1000,"resetAt":"2033-05-18T03:33:20Z"},
              "limits":[{
                "window":{"duration":300,"timeUnit":"MINUTE"},
                "detail":{"remaining":80,"limit":100,"resetIn":3600}
              }]
            }
            """.trimIndent()
        )

        assertNull(status.error)
        assertEquals(listOf("week", "primary"), status.windows.map { it.selectionKey })
        assertEquals(listOf(30, 20), status.windows.map { it.usedPercent })
        assertEquals(18_000L, status.windows[1].windowSeconds)
    }

    @Test
    fun claudeParseNormalizesFractionAndPercentUtilization() {
        val status = ClaudeApi.parse(
            """
            {
              "five_hour":{"utilization":0.42,"resets_at":"2033-05-18T03:33:20Z"},
              "seven_day":{"utilization":55,"resets_at":"2033-05-22T03:33:20Z"},
              "extra_usage":{"is_enabled":true,"used_credits":2.5,"monthly_limit":20,"currency":"USD"}
            }
            """.trimIndent()
        )

        assertEquals(listOf(42, 55), status.windows.map { it.usedPercent })
        assertEquals(listOf("primary", "week"), status.windows.map { it.selectionKey })
        assertEquals(1, status.balances.size)
    }

    @Test
    fun miniMaxParseUsesAuthoritativeRemainingPercent() {
        val status = MiniMaxApi.parse(
            """
            {
              "base_resp":{"status_code":0},
              "model_remains":[{
                "model_name":"general",
                "current_interval_total_count":1000,
                "current_interval_usage_count":700,
                "current_interval_remaining_percent":70,
                "remains_time":3600000,
                "current_weekly_total_count":5000,
                "current_weekly_usage_count":4000,
                "current_weekly_remaining_percent":80,
                "weekly_remains_time":86400000
              }]
            }
            """.trimIndent()
        )

        assertEquals(listOf(30, 20), status.windows.map { it.usedPercent })
        assertEquals(listOf("primary", "week"), status.windows.map { it.selectionKey })
    }

    @Test
    fun deepSeekParsePreservesBalanceBreakdown() {
        val status = DeepSeekApi.parse(
            """
            {
              "is_available":true,
              "balance_infos":[{
                "currency":"CNY",
                "total_balance":"110.00",
                "granted_balance":"10.00",
                "topped_up_balance":"100.00"
              }]
            }
            """.trimIndent()
        )

        assertNull(status.error)
        assertTrue(status.windows.isEmpty())
        assertEquals(110.0, status.balances.single().amount, 0.0001)
        assertTrue(status.balances.single().detail!!.contains("赠送"))
    }

    @Test
    fun deepSeekDailyCostsAggregateOfficialCalendarDays() {
        val currentMonth = """
            {
              "code":0,
              "data":{"biz_code":0,"biz_data":[{
                "currency":"CNY",
                "days":[
                  {"date":"2026-08-17","data":[{"model":"deepseek-chat","usage":[
                    {"type":"PROMPT_CACHE_MISS_TOKEN","amount":"1.25"},
                    {"type":"RESPONSE_TOKEN","amount":"1.75"},
                    {"type":"REQUEST","amount":"999"}
                  ]}]},
                  {"date":"2026-08-12","data":[{"model":"deepseek-chat","usage":[
                    {"type":"PROMPT_CACHE_HIT_TOKEN","amount":"4"}
                  ]}]},
                  {"date":"2026-08-01","data":[{"model":"deepseek-chat","usage":[
                    {"type":"RESPONSE_TOKEN","amount":"8"}
                  ]}]}
                ]
              }]}
            }
        """.trimIndent()
        val previousMonth = """
            {
              "code":0,
              "data":{"biz_code":0,"biz_data":[{
                "currency":"CNY",
                "days":[
                  {"date":"2026-07-30","data":[{"model":"deepseek-chat","usage":[
                    {"type":"RESPONSE_TOKEN","amount":"16"}
                  ]}]},
                  {"date":"2026-07-19","data":[{"model":"deepseek-chat","usage":[
                    {"type":"RESPONSE_TOKEN","amount":"32"}
                  ]}]},
                  {"date":"2026-07-18","data":[{"model":"deepseek-chat","usage":[
                    {"type":"RESPONSE_TOKEN","amount":"64"}
                  ]}]}
                ]
              }]}
            }
        """.trimIndent()

        val costs = DeepSeekDailyCostApi.parse(
            listOf(previousMonth, currentMonth),
            LocalDate.of(2026, 8, 17),
            "CNY",
        )

        assertEquals(3.0, costs.today, 0.0001)
        assertEquals(7.0, costs.last7Days, 0.0001)
        assertEquals(63.0, costs.last30Days, 0.0001)
        assertEquals(listOf(YearMonth.of(2026, 7), YearMonth.of(2026, 8)),
            DeepSeekDailyCostApi.requiredMonths(LocalDate.of(2026, 8, 17)))
    }

    @Test
    fun budgetPercentCanExceedOneHundred() {
        val budget = BudgetWindow(
            label = "今日预算",
            spent = 13.5,
            limit = 10.0,
            currency = "CNY",
            periodDays = 1,
        )

        assertEquals(135, budget.usedPercent)
    }

    @Test
    fun deepSeekBudgetsBecomeStableFloatingWindowOptions() {
        val status = ProviderStatus(
            id = "deepseek",
            name = "DeepSeek",
            plan = "API",
            windows = emptyList(),
            updatedAt = 0,
            budgets = listOf(
                BudgetWindow("今日预算", 13.5, 10.0, "CNY", 1),
                BudgetWindow("近 7 日预算", 42.0, 100.0, "CNY", 7),
                BudgetWindow("近 30 日预算", 120.0, 300.0, "CNY", 30),
            ),
        )

        val windows = status.meterWindows()
        assertEquals(listOf("budget_today", "budget_7d", "budget_30d"), windows.map { it.selectionKey })
        assertEquals(listOf(135, 42, 40), windows.map { it.usedPercent })
        assertEquals(listOf(0, 0, 0), windows.map { it.timeElapsedPercent })
    }

    @Test
    fun deepSeekDailyCostsRejectExpiredPlatformSessionEnvelope() {
        val error = runCatching {
            DeepSeekDailyCostApi.parse(
                listOf("""{"code":40003,"msg":"Authorization Failed","data":null}"""),
                LocalDate.of(2026, 8, 17),
            )
        }.exceptionOrNull()

        assertTrue(error?.message?.contains("userToken") == true)
    }
}
