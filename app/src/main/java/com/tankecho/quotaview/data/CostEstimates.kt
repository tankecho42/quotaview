package com.tankecho.quotaview.data

/**
 * 费用模拟数据源（第一版: 手动导入明细 JSON）。
 *
 * 采集脚本跑在 Mac mini 上, 汇总 ~/.codex/sessions 和 ~/.claude/projects 的
 * token usage, 生成 breakdown JSON。用户通过设置页导入或粘贴。
 *
 * 后续版本: 应用内直接扫描本地 jsonl (需要 SAF 权限), 或对接采集服务端。
 */
object CostEstimates {

    /** 从设置页导入的 JSON: {"codex": {input, cacheRead, output}, "glm": {...}} */
    fun providerBreakdown(prefsJson: String?): Map<String, TokenBreakdown> {
        if (prefsJson.isNullOrBlank()) return emptyMap()
        return try {
            val root = org.json.JSONObject(prefsJson)
            val out = mutableMapOf<String, TokenBreakdown>()
            for (key in root.keys()) {
                val o = root.optJSONObject(key) ?: continue
                out[key] = TokenBreakdown(
                    input = o.optLong("input", 0),
                    cacheRead = o.optLong("cacheRead", 0),
                    output = o.optLong("output", 0),
                )
            }
            out
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
