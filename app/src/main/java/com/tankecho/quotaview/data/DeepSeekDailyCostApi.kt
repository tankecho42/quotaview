package com.tankecho.quotaview.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

data class DeepSeekBudgetLimits(
    val today: Double,
    val last7Days: Double,
    val last30Days: Double,
) {
    val isConfigured: Boolean get() = today > 0 || last7Days > 0 || last30Days > 0

    companion object {
        fun parse(today: String?, last7Days: String?, last30Days: String?) = DeepSeekBudgetLimits(
            today = today.positiveAmount(),
            last7Days = last7Days.positiveAmount(),
            last30Days = last30Days.positiveAmount(),
        )

        private fun String?.positiveAmount(): Double =
            this?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: 0.0
    }
}

data class DeepSeekDailyCosts(
    val currency: String,
    val today: Double,
    val last7Days: Double,
    val last30Days: Double,
) {
    fun budgetWindows(limits: DeepSeekBudgetLimits): List<BudgetWindow> = buildList {
        if (limits.today > 0) add(BudgetWindow("今日预算", today, limits.today, currency, 1))
        if (limits.last7Days > 0) add(BudgetWindow("近 7 日预算", last7Days, limits.last7Days, currency, 7))
        if (limits.last30Days > 0) add(BudgetWindow("近 30 日预算", last30Days, limits.last30Days, currency, 30))
    }
}

/** DeepSeek Platform 控制台的官方日账单接口；需要网页登录态 userToken，API key 无权访问。 */
object DeepSeekDailyCostApi {
    private const val COST_URL = "https://platform.deepseek.com/api/v0/usage/cost"
    private val billableTypes = setOf(
        "PROMPT_CACHE_HIT_TOKEN",
        "PROMPT_CACHE_MISS_TOKEN",
        "RESPONSE_TOKEN",
    )

    suspend fun fetch(
        rawPlatformToken: String,
        preferredCurrency: String? = null,
        today: LocalDate = LocalDate.now(ZoneOffset.UTC),
    ): DeepSeekDailyCosts = coroutineScope {
        val token = normalizePlatformToken(rawPlatformToken)
            ?: throw IllegalArgumentException("Platform userToken 格式无效")
        val payloads = requiredMonths(today).map { month ->
            async(Dispatchers.IO) { requestMonth(token, month) }
        }.awaitAll()
        parse(payloads, today, preferredCurrency)
    }

    fun requiredMonths(today: LocalDate): List<YearMonth> {
        val start = YearMonth.from(today.minusDays(29))
        val end = YearMonth.from(today)
        return buildList {
            var cursor = start
            while (!cursor.isAfter(end)) {
                add(cursor)
                cursor = cursor.plusMonths(1)
            }
        }
    }

    fun parse(
        payloads: List<String>,
        today: LocalDate,
        preferredCurrency: String? = null,
    ): DeepSeekDailyCosts {
        val costsByCurrency = linkedMapOf<String, MutableMap<LocalDate, Double>>()
        payloads.forEach { payload ->
            val root = JSONObject(payload)
            validateEnvelope(root)
            val items = root.optJSONObject("data")?.optJSONArray("biz_data") ?: return@forEach
            for (i in 0 until items.length()) {
                val currencyItem = items.optJSONObject(i) ?: continue
                val currency = currencyItem.optString("currency").ifBlank {
                    preferredCurrency?.takeIf { it.isNotBlank() } ?: "CNY"
                }
                val daily = costsByCurrency.getOrPut(currency) { linkedMapOf() }
                val days = currencyItem.optJSONArray("days") ?: continue
                for (dayIndex in 0 until days.length()) {
                    val dayItem = days.optJSONObject(dayIndex) ?: continue
                    val date = runCatching { LocalDate.parse(dayItem.optString("date")) }.getOrNull() ?: continue
                    val models = dayItem.optJSONArray("data") ?: continue
                    var dayCost = 0.0
                    for (modelIndex in 0 until models.length()) {
                        val usage = models.optJSONObject(modelIndex)?.optJSONArray("usage") ?: continue
                        for (usageIndex in 0 until usage.length()) {
                            val metric = usage.optJSONObject(usageIndex) ?: continue
                            if (metric.optString("type").uppercase() !in billableTypes) continue
                            dayCost += metric.numberOrZero("amount")
                        }
                    }
                    daily[date] = daily.getOrDefault(date, 0.0) + dayCost
                }
            }
        }

        val selectedCurrency = costsByCurrency.keys.firstOrNull {
            it.equals(preferredCurrency, ignoreCase = true)
        } ?: costsByCurrency.keys.firstOrNull()
            ?: preferredCurrency?.takeIf { it.isNotBlank() }
            ?: "CNY"
        val daily = costsByCurrency[selectedCurrency].orEmpty()

        fun sum(days: Long): Double {
            val start = today.minusDays(days - 1)
            return daily.entries.sumOf { (date, cost) ->
                if (date in start..today) cost else 0.0
            }
        }

        return DeepSeekDailyCosts(
            currency = selectedCurrency,
            today = daily[today] ?: 0.0,
            last7Days = sum(7),
            last30Days = sum(30),
        )
    }

    fun normalizePlatformToken(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val candidate = runCatching {
            when (val value = JSONTokener(trimmed).nextValue()) {
                is String -> value
                is JSONObject -> listOf("value", "token", "access_token", "accessToken", "userToken")
                    .firstNotNullOfOrNull { key -> value.optString(key).takeIf { it.isNotBlank() } }
                else -> trimmed
            }
        }.getOrNull()?.trim()?.removeSurrounding("'") ?: trimmed.removeSurrounding("'")
        return candidate.takeIf { it.length >= 20 && it.none(Char::isWhitespace) }
    }

    private fun requestMonth(token: String, month: YearMonth): String {
        val query = "month=${URLEncoder.encode(month.monthValue.toString(), "UTF-8")}" +
            "&year=${URLEncoder.encode(month.year.toString(), "UTF-8")}"
        val conn = URL("$COST_URL?$query").openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 401 || code == 403) throw IllegalStateException("Platform userToken 已过期或无效")
            if (code != 200) throw IllegalStateException("日账单 HTTP $code")
            body
        } finally {
            conn.disconnect()
        }
    }

    private fun validateEnvelope(root: JSONObject) {
        val code = root.optInt("code", 0)
        val data = root.optJSONObject("data")
        val bizCode = data?.optInt("biz_code", 0) ?: 0
        if (code == 40002 || code == 40003 || bizCode == 40002 || bizCode == 40003) {
            throw IllegalStateException("Platform userToken 已过期或无效")
        }
        if (code != 0) throw IllegalStateException(root.optString("msg", "日账单错误 $code"))
        if (bizCode != 0) throw IllegalStateException(data?.optString("biz_msg", "日账单错误 $bizCode"))
        if (data == null) throw IllegalStateException("日账单响应缺少 data")
    }

    private fun JSONObject.numberOrZero(key: String): Double = when (val raw = opt(key)) {
        is Number -> raw.toDouble().takeIf { it.isFinite() } ?: 0.0
        is String -> raw.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0
        else -> 0.0
    }
}
