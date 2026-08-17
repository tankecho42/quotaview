package com.tankecho.quotaview.data

data class ProviderMetricOption(
    val label: String,
    val key: String,
)

/** 主页圆环的可选主指标。key 与 Provider 返回的 selectionKey 保持一致。 */
object ProviderMetrics {
    fun options(providerId: String): List<ProviderMetricOption> = when (providerId) {
        "codex" -> listOf(
            ProviderMetricOption("主窗口", "primary"),
            ProviderMetricOption("周窗口", "week"),
            ProviderMetricOption("附加额度", "additional"),
        )
        "glm" -> listOf(
            ProviderMetricOption("主窗口", "primary"),
            ProviderMetricOption("周窗口", "week"),
            ProviderMetricOption("MCP 工具", "mcp"),
        )
        "kimi", "claude", "minimax" -> listOf(
            ProviderMetricOption("主窗口", "primary"),
            ProviderMetricOption("周窗口", "week"),
        )
        "deepseek" -> listOf(
            ProviderMetricOption("今日预算", "budget_today"),
            ProviderMetricOption("近 7 日预算", "budget_7d"),
            ProviderMetricOption("近 30 日预算", "budget_30d"),
        )
        else -> emptyList()
    }

    fun select(status: ProviderStatus, preferredKey: String?): QuotaWindow? {
        val windows = status.meterWindows()
        return windows.firstOrNull { it.selectionKey == preferredKey } ?: windows.firstOrNull()
    }
}
