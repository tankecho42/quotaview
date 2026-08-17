package com.tankecho.quotaview.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
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
    val id: String,             // "codex" / "glm" / "kimi" / ...
    val name: String,           // 显示名
    val plan: String,           // "Pro" / "Max"
    val windows: List<QuotaWindow>,
    val updatedAt: Long,
    val error: String? = null,
    val balances: List<BalanceMetric> = emptyList(),
    val budgets: List<BudgetWindow> = emptyList(),
    val detailMessage: String? = null,
    val detailMessageIsError: Boolean = false,
) {
    val hasData: Boolean get() = windows.isNotEmpty() || balances.isNotEmpty() || budgets.isNotEmpty()
}

/** 无法换算成百分比的账户余额；与订阅额度窗口分开展示，避免伪造进度。 */
data class BalanceMetric(
    val label: String,
    val amount: Double,
    val currency: String,
    val detail: String? = null,
)

/** 用户自定义预算与官方账单支出。usedPercent 不封顶，可真实显示超预算比例。 */
data class BudgetWindow(
    val label: String,
    val spent: Double,
    val limit: Double,
    val currency: String,
    val periodDays: Int,
) {
    val usedPercent: Int
        get() = if (limit <= 0) 0 else ((spent / limit) * 100)
            .roundToInt().coerceIn(0, 999_999)
}

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

// ---------- Kimi Code ----------

object KimiApi {
    // Kimi CLI 官方实现使用 GET https://api.kimi.com/coding/v1/usages。
    fun fetch(key: String): ProviderStatus {
        val conn = URL("https://api.kimi.com/coding/v1/usages").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code != 200) return ProviderStatus("kimi", "Kimi Code", "?", emptyList(), now(), "HTTP $code")
            parse(conn.inputStream.bufferedReader().readText())
        } catch (e: Exception) {
            ProviderStatus("kimi", "Kimi Code", "?", emptyList(), now(), e.message ?: "network error")
        } finally {
            conn.disconnect()
        }
    }

    fun parse(body: String): ProviderStatus {
        val root = JSONObject(body)
        val windows = mutableListOf<QuotaWindow>()

        root.optJSONObject("usage")?.let { usage ->
            usageWindow(usage, usage, JSONObject(), "周窗口", "week", 7 * 86400L)?.let(windows::add)
        }

        val limits = root.optJSONArray("limits")
        if (limits != null) {
            for (i in 0 until limits.length()) {
                val item = limits.optJSONObject(i) ?: continue
                val detail = item.optJSONObject("detail") ?: item
                val window = item.optJSONObject("window") ?: JSONObject()
                val seconds = durationSeconds(window, item, detail)
                val key = when {
                    seconds in 17_000L..19_000L -> "primary"
                    seconds >= 6 * 86400L -> "week"
                    else -> "limit_${i + 1}"
                }
                val fallback = when (key) {
                    "primary" -> "5h 窗口"
                    "week" -> "周窗口"
                    else -> "额度 ${i + 1}"
                }
                usageWindow(detail, item, window, fallback, key, seconds)?.let(windows::add)
            }
        }

        // 某些响应会同时在 usage 和 limits 返回周额度，只保留信息更早、稳定 key 的一条。
        val unique = windows.distinctBy { it.selectionKey }
        val plan = root.optString("plan", root.optString("plan_type", "Coding")).ifBlank { "Coding" }
        return ProviderStatus("kimi", "Kimi Code", plan.uppercase(), unique, now(),
            if (unique.isEmpty()) "no usage data" else null)
    }

    private fun usageWindow(
        detail: JSONObject,
        item: JSONObject,
        window: JSONObject,
        fallbackLabel: String,
        key: String,
        fallbackSeconds: Long,
    ): QuotaWindow? {
        val limit = detail.number("limit")
        var used = detail.number("used")
        if (used == null && limit != null) detail.number("remaining")?.let { used = limit - it }
        if (used == null && limit == null) return null
        val pct = if (limit != null && limit > 0) ((used ?: 0.0) / limit * 100).roundToInt().coerceIn(0, 100) else 0
        val seconds = durationSeconds(window, item, detail).takeIf { it > 0 } ?: fallbackSeconds
        val label = when {
            seconds in 17_000L..19_000L -> "5h 窗口"
            seconds >= 6 * 86400L -> "周窗口"
            else -> detail.optString("name", detail.optString("title", fallbackLabel)).ifBlank { fallbackLabel }
        }
        return QuotaWindow(label, pct, resetAt(detail, item), seconds, selectionKey = key)
    }

    private fun durationSeconds(vararg objects: JSONObject): Long {
        objects.forEach { obj ->
            val duration = obj.number("duration") ?: return@forEach
            val unit = obj.optString("timeUnit", obj.optString("time_unit", "")).uppercase()
            return when {
                "MINUTE" in unit -> (duration * 60).toLong()
                "HOUR" in unit -> (duration * 3600).toLong()
                "DAY" in unit -> (duration * 86400).toLong()
                else -> duration.toLong()
            }
        }
        return 0
    }

    private fun resetAt(vararg objects: JSONObject): Long {
        objects.forEach { obj ->
            for (key in listOf("reset_at", "resetAt", "reset_time", "resetTime")) {
                parseEpoch(obj.opt(key))?.let { return it }
            }
            for (key in listOf("reset_in", "resetIn", "ttl")) {
                obj.number(key)?.let { return now() + it.toLong() }
            }
        }
        return 0
    }
}

// ---------- Claude subscription ----------

object ClaudeApi {
    // Claude Code OAuth usage endpoint；普通 sk-ant-api API key 不具备订阅额度权限。
    fun fetch(oauthToken: String): ProviderStatus {
        val conn = URL("https://api.anthropic.com/api/oauth/usage").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("Authorization", "Bearer $oauthToken")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("anthropic-beta", "oauth-2025-04-20")
            conn.setRequestProperty("User-Agent", "claude-code/2.1.0")
            val code = conn.responseCode
            if (code != 200) return ProviderStatus("claude", "Claude", "?", emptyList(), now(), "HTTP $code")
            parse(conn.inputStream.bufferedReader().readText())
        } catch (e: Exception) {
            ProviderStatus("claude", "Claude", "?", emptyList(), now(), e.message ?: "network error")
        } finally {
            conn.disconnect()
        }
    }

    fun parse(body: String): ProviderStatus {
        val root = JSONObject(body)
        val windows = mutableListOf<QuotaWindow>()
        listOf(
            ClaudeWindow("five_hour", "5h 窗口", "primary", 5 * 3600L),
            ClaudeWindow("seven_day", "周窗口", "week", 7 * 86400L),
            ClaudeWindow("seven_day_opus", "Opus 周窗口", "opus", 7 * 86400L),
            ClaudeWindow("seven_day_sonnet", "Sonnet 周窗口", "sonnet", 7 * 86400L),
        ).forEach { spec ->
            val obj = root.optJSONObject(spec.jsonKey) ?: return@forEach
            val raw = obj.number("utilization") ?: return@forEach
            val pct = (if (raw <= 1.0) raw * 100 else raw).roundToInt().coerceIn(0, 100)
            windows.add(QuotaWindow(
                spec.label, pct, parseEpoch(obj.opt("resets_at")) ?: 0,
                spec.seconds, selectionKey = spec.selectionKey,
            ))
        }
        val balances = mutableListOf<BalanceMetric>()
        root.optJSONObject("extra_usage")?.let { extra ->
            if (extra.optBoolean("is_enabled")) {
                val used = extra.number("used_credits")
                val limit = extra.number("monthly_limit")
                if (used != null) balances.add(BalanceMetric(
                    "额外用量", used, extra.optString("currency", "USD"),
                    limit?.let { "月上限 ${formatAmount(it)}" },
                ))
            }
        }
        return ProviderStatus("claude", "Claude", "CLAUDE.AI", windows, now(),
            if (windows.isEmpty() && balances.isEmpty()) "no usage data" else null, balances)
    }

    private data class ClaudeWindow(
        val jsonKey: String, val label: String, val selectionKey: String, val seconds: Long,
    )
}

// ---------- MiniMax Coding Plan ----------

object MiniMaxApi {
    fun fetch(key: String, region: String): ProviderStatus {
        val host = if (region.equals("global", true)) "api.minimax.io" else "api.minimaxi.com"
        val conn = URL("https://$host/v1/api/openplatform/coding_plan/remains").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code != 200) return ProviderStatus("minimax", "MiniMax", "?", emptyList(), now(), "HTTP $code")
            parse(conn.inputStream.bufferedReader().readText())
        } catch (e: Exception) {
            ProviderStatus("minimax", "MiniMax", "?", emptyList(), now(), e.message ?: "network error")
        } finally {
            conn.disconnect()
        }
    }

    fun parse(body: String): ProviderStatus {
        val root = JSONObject(body)
        val base = root.optJSONObject("base_resp")
        if (base != null && base.optInt("status_code", 0) != 0) {
            return ProviderStatus("minimax", "MiniMax", "?", emptyList(), now(),
                base.optString("status_msg", "API error"))
        }
        val remains = root.optJSONArray("model_remains")
            ?: return ProviderStatus("minimax", "MiniMax", "?", emptyList(), now(), "no quota data")
        var selected: JSONObject? = null
        for (i in 0 until remains.length()) {
            val item = remains.optJSONObject(i) ?: continue
            if (selected == null || item.optString("model_name") == "general") selected = item
        }
        val item = selected ?: return ProviderStatus("minimax", "MiniMax", "?", emptyList(), now(), "no quota data")
        val windows = listOf(
            miniMaxWindow(item, weekly = false),
            miniMaxWindow(item, weekly = true),
        )
        return ProviderStatus("minimax", "MiniMax", "CODING", windows, now())
    }

    private fun miniMaxWindow(item: JSONObject, weekly: Boolean): QuotaWindow {
        val prefix = if (weekly) "current_weekly" else "current_interval"
        val total = item.number("${prefix}_total_count") ?: 0.0
        val remaining = item.number("${prefix}_usage_count") ?: 0.0
        val remainingPct = item.number("${prefix}_remaining_percent")
        val usedPct = when {
            remainingPct != null -> (100 - remainingPct).roundToInt().coerceIn(0, 100)
            total > 0 -> ((total - remaining) / total * 100).roundToInt().coerceIn(0, 100)
            else -> 0
        }
        val durationMs = item.number(if (weekly) "weekly_remains_time" else "remains_time") ?: 0.0
        val explicitEnd = parseEpoch(item.opt(if (weekly) "weekly_end_time" else "end_time"))
        val reset = explicitEnd ?: if (durationMs > 0) now() + (durationMs / 1000).toLong() else 0
        return QuotaWindow(
            if (weekly) "周窗口" else "5h 窗口", usedPct, reset,
            if (weekly) 7 * 86400L else 5 * 3600L,
            selectionKey = if (weekly) "week" else "primary",
        )
    }
}

// ---------- DeepSeek API balance ----------

object DeepSeekApi {
    fun fetch(key: String): ProviderStatus {
        val conn = URL("https://api.deepseek.com/user/balance").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code != 200) return ProviderStatus("deepseek", "DeepSeek", "?", emptyList(), now(), "HTTP $code")
            parse(conn.inputStream.bufferedReader().readText())
        } catch (e: Exception) {
            ProviderStatus("deepseek", "DeepSeek", "?", emptyList(), now(), e.message ?: "network error")
        } finally {
            conn.disconnect()
        }
    }

    fun parse(body: String): ProviderStatus {
        val root = JSONObject(body)
        val balances = mutableListOf<BalanceMetric>()
        val infos = root.optJSONArray("balance_infos")
        if (infos != null) {
            for (i in 0 until infos.length()) {
                val info = infos.optJSONObject(i) ?: continue
                val currency = info.optString("currency", "CNY")
                val total = info.number("total_balance") ?: continue
                val granted = info.number("granted_balance")
                val topped = info.number("topped_up_balance")
                val detail = listOfNotNull(
                    granted?.let { "赠送 ${formatAmount(it)}" },
                    topped?.let { "充值 ${formatAmount(it)}" },
                ).joinToString(" · ").ifBlank { null }
                balances.add(BalanceMetric("可用余额", total, currency, detail))
            }
        }
        return ProviderStatus("deepseek", "DeepSeek", "API 余额", emptyList(), now(),
            if (balances.isEmpty()) "no balance data" else null, balances)
    }
}

private fun JSONObject.number(key: String): Double? {
    val raw = opt(key)
    return when (raw) {
        is Number -> raw.toDouble().takeIf { it.isFinite() }
        is String -> raw.toDoubleOrNull()?.takeIf { it.isFinite() }
        else -> null
    }
}

private fun parseEpoch(raw: Any?): Long? {
    if (raw == null || raw == JSONObject.NULL) return null
    val numeric = when (raw) {
        is Number -> raw.toDouble()
        is String -> raw.toDoubleOrNull()
        else -> null
    }
    if (numeric != null && numeric.isFinite() && numeric > 0) {
        return (if (numeric > 10_000_000_000L) numeric / 1000 else numeric).toLong()
    }
    return runCatching { Instant.parse(raw.toString()).epochSecond }.getOrNull()
}

private fun formatAmount(value: Double): String = when {
    value >= 100 -> "%.0f".format(value)
    value >= 1 -> "%.2f".format(value)
    else -> "%.4f".format(value)
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
