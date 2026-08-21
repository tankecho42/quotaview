package com.tankecho.quotaview.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt

/** 火山方舟控制面：Coding Plan、Agent Plan 与火山账户余额。 */
object VolcengineArkApi {
    private const val ARK_VERSION = "2024-01-01"
    private const val BILLING_VERSION = "2022-01-01"

    /**
     * 三个接口独立请求和容错。同一账号可能同时订阅 Coding Plan 与 Agent Plan，
     * 因此不能用其中一个接口的结果来短路另一个。
     */
    suspend fun fetch(accessKeyId: String, secretAccessKey: String, region: String): ProviderStatus = coroutineScope {
        val normalizedRegion = region.trim().ifBlank { "cn-beijing" }
        val client = VolcengineOpenApiClient(accessKeyId.trim(), secretAccessKey.trim(), normalizedRegion)
        val coding = async { client.call("ark", "GetCodingPlanUsage", ARK_VERSION, "POST", "{}") }
        val agent = async { client.call("ark", "GetAFPUsage", ARK_VERSION, "POST", "{}") }
        val balance = async { client.call("billing", "QueryBalanceAcct", BILLING_VERSION, "GET", "") }

        val errors = mutableListOf<String>()
        val codingWindows = coding.await().parse("Coding Plan", errors, ::parseCodingPlan)
        val agentWindows = agent.await().parse("Agent Plan", errors, ::parseAgentPlan)
        val balances = balance.await().parse("账户余额", errors, ::parseBalance)
        merge(codingWindows, agentWindows, balances, errors)
    }

    fun parseCodingPlan(body: String): List<QuotaWindow> {
        val result = resultObject(JSONObject(body)) ?: return emptyList()
        val usage = result.optJSONArray("QuotaUsage") ?: return emptyList()
        val windows = mutableListOf<QuotaWindow>()
        for (index in 0 until usage.length()) {
            val item = usage.optJSONObject(index) ?: continue
            val level = item.optString("Level").lowercase(Locale.US)
            val spec = when (level) {
                "session" -> Triple("Coding · 5h 窗口", "coding_session", 5 * 3600L)
                "weekly" -> Triple("Coding · 周窗口", "coding_week", 7 * 86400L)
                "monthly" -> Triple("Coding · 月窗口", "coding_month", 30 * 86400L)
                else -> Triple("Coding · ${level.ifBlank { "额度" }}", "coding_${level.ifBlank { index.toString() }}", 0L)
            }
            val percent = item.jsonNumber("Percent") ?: continue
            windows.add(QuotaWindow(
                label = spec.first,
                usedPercent = percent.roundToInt().coerceIn(0, 100),
                resetAt = epochSeconds(item.opt("ResetTimestamp")),
                windowSeconds = spec.third,
                selectionKey = spec.second,
            ))
        }
        return windows.sortedBy { codingOrder(it.selectionKey) }
    }

    fun parseAgentPlan(body: String): List<QuotaWindow> {
        val result = resultObject(JSONObject(body)) ?: return emptyList()
        val specs = listOf(
            AgentWindowSpec("AFPFiveHour", "5h", "agent_5h", 5 * 3600L),
            AgentWindowSpec("AFPDaily", "日", "agent_daily", 86400L),
            AgentWindowSpec("AFPWeekly", "周", "agent_week", 7 * 86400L),
            AgentWindowSpec("AFPMonthly", "月", "agent_month", 30 * 86400L),
        )
        return specs.mapNotNull { spec ->
            val item = result.optJSONObject(spec.jsonKey) ?: return@mapNotNull null
            val quota = item.jsonNumber("Quota") ?: return@mapNotNull null
            if (quota <= 0) return@mapNotNull null
            val used = item.jsonNumber("Used") ?: 0.0
            val subscribeAt = epochSeconds(item.opt("SubscribeTime"))
            val resetAt = epochSeconds(item.opt("ResetTime"))
            val seconds = (resetAt - subscribeAt).takeIf { subscribeAt > 0 && it > 0 } ?: spec.fallbackSeconds
            QuotaWindow(
                label = "Agent · ${spec.label}窗口 · ${formatQuota(used)}/${formatQuota(quota)} AFP",
                usedPercent = (used / quota * 100).roundToInt().coerceIn(0, 100),
                resetAt = resetAt,
                windowSeconds = seconds,
                selectionKey = spec.selectionKey,
            )
        }
    }

    fun parseBalance(body: String): List<BalanceMetric> {
        val result = resultObject(JSONObject(body)) ?: return emptyList()
        val available = result.jsonNumber("AvailableBalance") ?: return emptyList()
        val detail = buildList {
            result.jsonNumber("CashBalance")?.let { add("现金 ${formatMoney(it)}") }
            result.jsonNumber("FreezeAmount")?.takeIf { it != 0.0 }?.let { add("冻结 ${formatMoney(it)}") }
            result.jsonNumber("CreditLimit")?.takeIf { it != 0.0 }?.let { add("信控 ${formatMoney(it)}") }
            result.jsonNumber("ArrearsBalance")?.takeIf { it != 0.0 }?.let { add("欠费 ${formatMoney(it)}") }
        }.joinToString(" · ").ifBlank { null }
        return listOf(BalanceMetric("火山账户可用余额", available, "CNY", detail))
    }

    fun merge(
        codingWindows: List<QuotaWindow>,
        agentWindows: List<QuotaWindow>,
        balances: List<BalanceMetric>,
        errors: List<String> = emptyList(),
    ): ProviderStatus {
        val windows = codingWindows + agentWindows
        val plan = buildList {
            if (codingWindows.isNotEmpty()) add("CODING")
            if (agentWindows.isNotEmpty()) add("AGENT")
            if (balances.isNotEmpty()) add("余额")
        }.joinToString(" + ").ifBlank { "?" }
        val hasData = windows.isNotEmpty() || balances.isNotEmpty()
        val message = errors.joinToString("；").ifBlank {
            if (hasData) "" else "未读取到有效的 Coding Plan、Agent Plan 或账户余额"
        }.ifBlank { null }
        return ProviderStatus(
            id = "volcengine",
            name = "火山方舟",
            plan = plan,
            windows = windows,
            updatedAt = now(),
            error = if (hasData) null else message,
            balances = balances,
            detailMessage = if (hasData) message else null,
            detailMessageIsError = message != null,
        )
    }

    private data class AgentWindowSpec(
        val jsonKey: String,
        val label: String,
        val selectionKey: String,
        val fallbackSeconds: Long,
    )

    private fun codingOrder(key: String): Int = when (key) {
        "coding_session" -> 0
        "coding_week" -> 1
        "coding_month" -> 2
        else -> 3
    }

    private fun resultObject(root: JSONObject): JSONObject? {
        if (!root.has("Result")) return root
        return root.optJSONObject("Result")
    }

    private fun <T> ApiCall.parse(
        label: String,
        errors: MutableList<String>,
        parser: (String) -> List<T>,
    ): List<T> {
        error?.let {
            errors.add("$label：$it")
            return emptyList()
        }
        return runCatching { parser(body.orEmpty()) }.getOrElse {
            errors.add("$label：响应解析失败")
            emptyList()
        }
    }

    private fun formatQuota(value: Double): String = when {
        value % 1.0 == 0.0 -> "%.0f".format(Locale.US, value)
        value >= 1 -> "%.1f".format(Locale.US, value)
        else -> "%.2f".format(Locale.US, value)
    }

    private fun formatMoney(value: Double): String = "%.2f".format(Locale.US, value)
}

private data class ApiCall(val body: String? = null, val error: String? = null)

private class VolcengineOpenApiClient(
    private val accessKeyId: String,
    private val secretAccessKey: String,
    private val region: String,
) {
    fun call(service: String, action: String, version: String, method: String, body: String): ApiCall {
        if (accessKeyId.isBlank() || secretAccessKey.isBlank()) return ApiCall(error = "AK/SK 未填写")
        val query = "Action=$action&Version=$version"
        val url = URL("https://open.volcengineapi.com/?$query")
        val payload = if (method == "GET") "" else body
        val headers = VolcengineSigner.headers(
            accessKeyId = accessKeyId,
            secretAccessKey = secretAccessKey,
            region = region,
            service = service,
            host = url.host,
            method = method,
            canonicalQuery = query,
            body = payload,
        )
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            headers.forEach(connection::setRequestProperty)
            if (method != "GET") {
                connection.doOutput = true
                connection.outputStream.use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val root = runCatching { JSONObject(responseBody) }.getOrNull()
            val apiError = root?.optJSONObject("ResponseMetadata")?.optJSONObject("Error")
            if (code !in 200..299 || apiError != null) {
                val errorCode = apiError?.optString("Code").orEmpty()
                val errorMessage = apiError?.optString("Message").orEmpty()
                val detail = listOf(errorCode, errorMessage).filter { it.isNotBlank() }.joinToString(" · ")
                ApiCall(error = detail.ifBlank { "HTTP $code" })
            } else {
                ApiCall(body = responseBody)
            }
        } catch (error: Exception) {
            ApiCall(error = error.message ?: "network error")
        } finally {
            connection.disconnect()
        }
    }
}

/** 火山引擎 Signature V4。仅返回请求头，不记录或暴露 AK/SK。 */
object VolcengineSigner {
    private val timestampFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC)

    fun headers(
        accessKeyId: String,
        secretAccessKey: String,
        region: String,
        service: String,
        host: String,
        method: String,
        canonicalQuery: String,
        body: String,
        timestamp: Instant = Instant.now(),
    ): Map<String, String> {
        val xDate = timestampFormat.format(timestamp)
        val shortDate = xDate.substring(0, 8)
        val payloadHash = sha256Hex(body.toByteArray(StandardCharsets.UTF_8))
        val signedHeaders = "host;x-content-sha256;x-date"
        val canonicalHeaders = "host:$host\nx-content-sha256:$payloadHash\nx-date:$xDate\n"
        val canonicalRequest = listOf(
            method.uppercase(Locale.US),
            "/",
            canonicalQuery,
            canonicalHeaders,
            signedHeaders,
            payloadHash,
        ).joinToString("\n")
        val scope = "$shortDate/$region/$service/request"
        val stringToSign = "HMAC-SHA256\n$xDate\n$scope\n${sha256Hex(canonicalRequest.toByteArray(StandardCharsets.UTF_8))}"
        val signingKey = hmac(
            hmac(hmac(hmac(secretAccessKey.toByteArray(StandardCharsets.UTF_8), shortDate), region), service),
            "request",
        )
        val signature = hmac(signingKey, stringToSign).toHex()
        return mapOf(
            "X-Date" to xDate,
            "X-Content-Sha256" to payloadHash,
            "Authorization" to "HMAC-SHA256 Credential=$accessKeyId/$scope, SignedHeaders=$signedHeaders, Signature=$signature",
        )
    }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .toHex()

    private fun hmac(key: ByteArray, value: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
}

private fun JSONObject.jsonNumber(key: String): Double? {
    val raw = opt(key)
    return when (raw) {
        is Number -> raw.toDouble().takeIf { it.isFinite() }
        is String -> raw.toDoubleOrNull()?.takeIf { it.isFinite() }
        else -> null
    }
}

private fun epochSeconds(raw: Any?): Long {
    val value = when (raw) {
        is Number -> raw.toDouble()
        is String -> raw.toDoubleOrNull()
        else -> null
    } ?: return 0
    if (!value.isFinite() || value <= 0) return 0
    return (if (value > 10_000_000_000L) value / 1000 else value).toLong()
}
