package com.tankecho.quotaview.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

// ---------- 统一数据模型 ----------

/** 一条额度窗口（进度条竞赛的一个参赛者） */
data class QuotaWindow(
    val label: String,          // "5h 窗口" / "周窗口" / "MCP 工具"
    val usedPercent: Int,       // 已用百分比 0-100
    val resetAt: Long,          // epoch seconds，重置时间
    val windowSeconds: Long,    // 窗口总时长（5h=18000, 7d=604800），未知=0
    val kind: Kind = Kind.TOKENS,
    val selectionKey: String = "primary", // UI 选择使用稳定 key，不依赖展示文案
) {
    enum class Kind { TOKENS, TOOL_CALLS }

    /** 时间流逝百分比（基于窗口起点 = resetAt - windowSeconds） */
    val timeElapsedPercent: Int
        get() = if (windowSeconds <= 0) 0 else (((now() - (resetAt - windowSeconds)).toDouble() / windowSeconds) * 100)
            .roundToInt().coerceIn(0, 100)

    /** PACE = 额度消耗% / 时间流逝%，<1 健康 */
    val pace: Float?
        get() = if (windowSeconds <= 0 || timeElapsedPercent == 0) null
        else usedPercent.toFloat() / timeElapsedPercent
}

data class ProviderStatus(
    val id: String,             // "codex" / "glm"
    val name: String,           // 显示名
    val plan: String,           // "Pro" / "Max"
    val windows: List<QuotaWindow>,
    val updatedAt: Long,
    val error: String? = null,
)

// ---------- Codex (ChatGPT Plan) ----------

object CodexApi {
    // 实测 2026-08-14: GET wham/usage, OAuth Bearer + chatgpt-account-id 头
    // 注意: 需要浏览器 UA，否则被 CF 403
    fun fetch(token: String, accountId: String): ProviderStatus {
        val conn = URL("https://chatgpt.com/backend-api/wham/usage").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("chatgpt-account-id", accountId)
            conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15")
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code != 200) return ProviderStatus(
                "codex", "Codex", "?", emptyList(), now(), "HTTP $code")
            val body = conn.inputStream.bufferedReader().readText()
            parse(body)
        } catch (e: Exception) {
            ProviderStatus("codex", "Codex", "?", emptyList(), now(), e.message ?: "network error")
        } finally {
            conn.disconnect()
        }
    }

    fun parse(body: String): ProviderStatus {
        val root = JSONObject(body)
        val plan = root.optString("plan_type", "plan").ifBlank { "plan" }
        val windows = mutableListOf<QuotaWindow>()

        val rl = root.optJSONObject("rate_limit") ?: JSONObject()
        val pw = rl.optJSONObject("primary_window")
        if (pw != null) {
            val secs = pw.optLong("limit_window_seconds", 0)
            windows.add(QuotaWindow(
                label = windowLabel(secs, "主窗口"),
                usedPercent = pw.optInt("used_percent", 0),
                resetAt = pw.optLong("reset_at", 0),
                windowSeconds = secs,
                selectionKey = windowKey(secs),
            ))
        }
        val sw = rl.optJSONObject("secondary_window")
        if (sw != null) {
            val secs = sw.optLong("limit_window_seconds", 0)
            windows.add(QuotaWindow(
                label = windowLabel(secs, "副窗口"),
                usedPercent = sw.optInt("used_percent", 0),
                resetAt = sw.optLong("reset_at", 0),
                windowSeconds = secs,
                selectionKey = windowKey(secs),
            ))
        }
        // additional_rate_limits: 副额度（Spark 等）
        val adds = root.optJSONArray("additional_rate_limits")
        if (adds != null) {
            for (i in 0 until adds.length()) {
                val a = adds.getJSONObject(i)
                val rl2 = a.optJSONObject("rate_limit")?.optJSONObject("primary_window") ?: continue
                val secs = rl2.optLong("limit_window_seconds", 0)
                windows.add(QuotaWindow(
                    label = a.optString("limit_name", "附加额度"),
                    usedPercent = rl2.optInt("used_percent", 0),
                    resetAt = rl2.optLong("reset_at", 0),
                    windowSeconds = secs,
                    selectionKey = "additional",
                ))
            }
        }
        return ProviderStatus("codex", "Codex", plan.uppercase(), windows, now())
    }

    private fun windowKey(seconds: Long): String = if (seconds >= 6 * 86400L) "week" else "primary"

    private fun windowLabel(seconds: Long, fallback: String): String = when {
        seconds in 17_000L..19_000L -> "5h 窗口"
        seconds >= 6 * 86400L -> "周窗口"
        else -> fallback
    }
}

// ---------- GLM Coding Plan ----------

object GlmApi {
    // 实测 2026-08-14: GET /api/monitor/usage/quota/limit, Bearer GLM_API_KEY
    // nextResetTime 是 epoch 毫秒（Codex 是秒），unit 枚举: 3=5h, 6=week(实测校准)
    fun fetch(key: String): ProviderStatus {
        val conn = URL("https://open.bigmodel.cn/api/monitor/usage/quota/limit").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("accept", "application/json")
            val code = conn.responseCode
            if (code != 200) return ProviderStatus("glm", "GLM Coding Plan", "?", emptyList(), now(), "HTTP $code")
            parse(conn.inputStream.bufferedReader().readText())
        } catch (e: Exception) {
            ProviderStatus("glm", "GLM Coding Plan", "?", emptyList(), now(), e.message ?: "network error")
        } finally {
            conn.disconnect()
        }
    }

    private val UNIT_HOURS = mapOf(
        1 to 1L,      // hour? (unverified)
        2 to 24L,     // day?
        3 to 5L,      // 5h 滚动窗 (实测 2026-08-14)
        4 to 24L,     // day
        5 to 24L,
        6 to 168L,    // week (实测 2026-08-14)
    )

    fun parse(body: String): ProviderStatus {
        val root = JSONObject(body)
        val data = root.optJSONObject("data") ?: return ProviderStatus("glm", "GLM Coding Plan", "?", emptyList(), now(), "no data")
        val level = when (data.optString("level")) {
            "lite" -> "Lite"
            "pro" -> "Pro"
            "max" -> "Max"
            else -> data.optString("level").ifBlank { "?" }
        }
        val windows = mutableListOf<QuotaWindow>()
        val limits = data.optJSONArray("limits") ?: return ProviderStatus("glm", "GLM Coding Plan", level, emptyList(), now())
        // TOKENS_LIMIT: 短窗在前(主), 长窗在后(辅)
        val tokenLimits = mutableListOf<JSONObject>()
        var toolLimit: JSONObject? = null
        for (i in 0 until limits.length()) {
            val lim = limits.getJSONObject(i)
            when (lim.optString("type")) {
                "TOKENS_LIMIT" -> tokenLimits.add(lim)
                "TIME_LIMIT" -> toolLimit = lim
            }
        }
        tokenLimits.sortBy { it.optLong("nextResetTime", 0) }
        tokenLimits.forEachIndexed { idx, lim ->
            val unit = lim.optInt("unit", 0)
            val hours = UNIT_HOURS[unit] ?: 0L
            windows.add(QuotaWindow(
                label = when {
                    hours == 5L -> "5h 窗口"
                    hours == 168L -> "周窗口"
                    else -> "窗口${idx + 1}"
                },
                usedPercent = lim.optInt("percentage", 0),
                resetAt = lim.optLong("nextResetTime", 0) / 1000,  // ms → s
                windowSeconds = hours * 3600,
                selectionKey = when (hours) {
                    168L -> "week"
                    else -> "primary"
                },
            ))
        }
        toolLimit?.let { tl ->
            // 实测语义: usage=总额度, currentValue=已用, remaining=剩余, percentage=已用%
            val quota = tl.optInt("usage", 0)
            val used = tl.optInt("currentValue", 0)
            val remaining = tl.optInt("remaining", 0)
            val pct = tl.optInt("percentage", 0)
            val usedPct = when {
                pct > 0 -> pct
                quota > 0 -> used * 100 / quota
                remaining > 0 -> 0
                else -> 0
            }
            windows.add(QuotaWindow(
                label = "MCP 工具" + if (quota > 0) " · 余${remaining}" else "",
                usedPercent = usedPct,
                resetAt = tl.optLong("nextResetTime", 0) / 1000,
                windowSeconds = 0,  // 月度，未知时长 → PACE 不算
                kind = QuotaWindow.Kind.TOOL_CALLS,
                selectionKey = "mcp",
            ))
        }
        return ProviderStatus("glm", "GLM Coding Plan", level, windows, now())
    }
}

// ---------- Cost simulator (定价表可配置) ----------

object CostSimulator {
    // USD per 1M tokens. 独立三档: input / cache-read / output
    // GPT-5.x 官方 API 价 (2026-08), GLM 价格占位待核实 — 用户可在设置里改
    data class Rates(val input: Double, val cacheRead: Double, val output: Double)

    val DEFAULT_RATES = mapOf(
        "codex" to Rates(1.25, 0.125, 10.0),
        "glm" to Rates(0.6, 0.075, 2.2),
    )

    fun costUSD(tokens: TokenBreakdown, rates: Rates): Double =
        tokens.input / 1_000_000.0 * rates.input +
        tokens.cacheRead / 1_000_000.0 * rates.cacheRead +
        tokens.output / 1_000_000.0 * rates.output

    fun fmtUSD(v: Double): String =
        if (v >= 100) "$%.0f".format(v) else if (v >= 1) "$%.1f".format(v) else "$%.2f".format(v)
}

data class TokenBreakdown(
    val input: Long, val cacheRead: Long, val output: Long,
) {
    val total: Long get() = input + cacheRead + output
    fun fmtTokens(v: Long): String = when {
        v >= 1_000_000_000L -> "%.1fB".format(v / 1_000_000_000.0)
        v >= 1_000_000L -> "%.1fM".format(v / 1_000_000.0)
        v >= 1_000L -> "%.1fK".format(v / 1_000.0)
        else -> v.toString()
    }
}

fun now(): Long = System.currentTimeMillis() / 1000
