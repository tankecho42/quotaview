package com.tankecho.quotaview.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min

data class SpendInterval(val fromAt: Long, val toAt: Long, val amount: Double)

/** 余额采样横跨窗口边界时按时间比例分摊，避免把长时间离线后的全部支出算进 24H。 */
object BudgetMath {
    fun spentInWindow(events: List<SpendInterval>, nowMillis: Long, windowMillis: Long): Double {
        if (windowMillis <= 0) return 0.0
        val cutoff = nowMillis - windowMillis
        return events.sumOf { event ->
            if (event.amount <= 0 || event.toAt < cutoff || event.fromAt > nowMillis) return@sumOf 0.0
            val start = min(event.fromAt, event.toAt)
            val end = max(event.fromAt, event.toAt)
            val duration = end - start
            if (duration <= 0) {
                if (end in cutoff..nowMillis) event.amount else 0.0
            } else {
                val overlap = (min(end, nowMillis) - max(start, cutoff)).coerceAtLeast(0)
                event.amount * overlap.toDouble() / duration
            }
        }
    }
}

/**
 * DeepSeek 未提供按 API key 查询 24H/7D/30D 支出的官方接口。
 * 这里仅保存余额下降产生的本机观测区间；充值上涨更新基线但不会冲减支出。
 */
object DeepSeekBudgetTracker {
    private const val PREF_HISTORY = "deepseek_budget_history_v1"
    private const val MAX_WINDOW_MS = 30L * 24 * 60 * 60 * 1000
    private const val EPSILON = 0.0000001

    @Synchronized
    fun recordAndApply(
        prefs: SharedPreferences,
        apiKey: String,
        status: ProviderStatus,
        nowMillis: Long = System.currentTimeMillis(),
    ): ProviderStatus {
        if (status.error != null || status.balances.isEmpty()) return status

        val fingerprint = fingerprint(apiKey)
        val stored = runCatching { JSONObject(prefs.getString(PREF_HISTORY, "{}") ?: "{}") }
            .getOrElse { JSONObject() }
        val root = if (stored.optString("fingerprint") == fingerprint) stored else JSONObject()
        root.put("fingerprint", fingerprint)
        val currencies = root.optJSONObject("currencies") ?: JSONObject().also { root.put("currencies", it) }
        val budgets = mutableListOf<BudgetWindow>()

        status.balances.forEach { balance ->
            val currency = balance.currency.ifBlank { "CNY" }
            val state = currencies.optJSONObject(currency) ?: JSONObject().also { currencies.put(currency, it) }
            val previousBalance = state.numberOrNull("last_balance")
            val previousAt = state.optLong("last_at", 0L)
            val observedSince = state.optLong("observed_since", nowMillis).takeIf { it > 0 } ?: nowMillis
            val events = readEvents(state.optJSONArray("events"))

            if (previousBalance != null && previousAt > 0 && nowMillis >= previousAt) {
                val spent = previousBalance - balance.amount
                if (spent > EPSILON) events.add(SpendInterval(previousAt, nowMillis, spent))
            }

            val cutoff = nowMillis - MAX_WINDOW_MS
            val retained = events.filter { it.toAt >= cutoff }.takeLast(10_000)
            state.put("last_balance", balance.amount)
            state.put("last_at", nowMillis)
            state.put("observed_since", observedSince)
            state.put("events", writeEvents(retained))

            budgetSpecs(prefs).forEach { spec ->
                if (spec.limit > 0) {
                    budgets.add(BudgetWindow(
                        label = spec.label,
                        spent = BudgetMath.spentInWindow(retained, nowMillis, spec.periodMillis),
                        limit = spec.limit,
                        currency = currency,
                        periodSeconds = spec.periodMillis / 1000,
                        observedSince = observedSince / 1000,
                    ))
                }
            }
        }

        prefs.edit().putString(PREF_HISTORY, root.toString()).apply()
        return status.copy(budgets = budgets)
    }

    private data class BudgetSpec(val label: String, val limit: Double, val periodMillis: Long)

    private fun budgetSpecs(prefs: SharedPreferences): List<BudgetSpec> = listOf(
        BudgetSpec("24H 预算", prefs.amount("deepseek_budget_24h"), 24L * 60 * 60 * 1000),
        BudgetSpec("7D 预算", prefs.amount("deepseek_budget_7d"), 7L * 24 * 60 * 60 * 1000),
        BudgetSpec("30D 预算", prefs.amount("deepseek_budget_30d"), MAX_WINDOW_MS),
    )

    private fun SharedPreferences.amount(key: String): Double =
        getString(key, "")?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: 0.0

    private fun readEvents(array: JSONArray?): MutableList<SpendInterval> {
        if (array == null) return mutableListOf()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val amount = item.numberOrNull("amount") ?: continue
                add(SpendInterval(item.optLong("from", 0), item.optLong("to", 0), amount))
            }
        }.toMutableList()
    }

    private fun writeEvents(events: List<SpendInterval>): JSONArray = JSONArray().apply {
        events.forEach { event ->
            put(JSONObject().put("from", event.fromAt).put("to", event.toAt).put("amount", event.amount))
        }
    }

    private fun JSONObject.numberOrNull(key: String): Double? = when (val raw = opt(key)) {
        is Number -> raw.toDouble().takeIf { it.isFinite() }
        is String -> raw.toDoubleOrNull()?.takeIf { it.isFinite() }
        else -> null
    }

    private fun fingerprint(apiKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(apiKey.toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
