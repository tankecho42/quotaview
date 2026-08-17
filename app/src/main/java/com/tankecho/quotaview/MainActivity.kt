package com.tankecho.quotaview

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.tankecho.quotaview.data.CodexApi
import com.tankecho.quotaview.data.ClaudeApi
import com.tankecho.quotaview.data.CostSimulator
import com.tankecho.quotaview.data.DeepSeekApi
import com.tankecho.quotaview.data.GlmApi
import com.tankecho.quotaview.data.KimiApi
import com.tankecho.quotaview.data.MiniMaxApi
import com.tankecho.quotaview.data.ProviderStatus
import com.tankecho.quotaview.data.QuotaWindow
import android.content.Intent
import androidx.core.content.ContextCompat
import com.tankecho.quotaview.ui.RaceBars
import com.tankecho.quotaview.ui.ProviderIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var refreshJob: Job? = null
    private lateinit var prefs: SharedPreferences
    private lateinit var root: LinearLayout
    private lateinit var swipe: SwipeRefreshLayout
    private val collapsed = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("qv", MODE_PRIVATE)

        // 灵动岛: 按持久化开关恢复服务
        if (prefs.getBoolean("island_enabled", false)) {
            runCatching { ContextCompat.startForegroundService(this, Intent(this, IslandService::class.java)) }
        }

        swipe = SwipeRefreshLayout(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(24))
            setBackgroundColor(0xFF101218.toInt())
        }
        val scroll = ScrollView(this).apply { addView(root) }
        swipe.setOnRefreshListener { refresh() }
        supportActionBar?.hide()

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(24), dp(20), 0)
        }
        header.addView(TextView(this).apply {
            text = "QuotaView"
            textSize = 24f; setTextColor(0xFFF2F3F7.toInt()); paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this).apply {
            text = "设置"
            textSize = 14f
            setTextColor(0xFF8FA3FF.toInt())
            paint.isFakeBoldText = true
            setPadding(dp(12), dp(8), dp(4), dp(8))
            setOnClickListener {
                startActivity(android.content.Intent(this@MainActivity, SettingsActivity::class.java))
            }
        })

        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(header)
        outer.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        swipe.addView(outer)
        setContentView(swipe)
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        refreshJob?.cancel()
        swipe.isRefreshing = true
        refreshJob = lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                coroutineScope {
                    listOf(
                    async { runCatching {
                        val tok = prefs.getString("codex_token", "").orEmpty()
                        val acct = prefs.getString("codex_account", "").orEmpty()
                        if (prefs.getBoolean("show_codex", true) && tok.isNotBlank()) CodexApi.fetch(tok, acct) else null
                    }.getOrNull() },
                    async { runCatching {
                        val key = prefs.getString("glm_key", "").orEmpty()
                        if (prefs.getBoolean("show_glm", true) && key.isNotBlank()) GlmApi.fetch(key) else null
                    }.getOrNull() },
                    async { runCatching {
                        val key = prefs.getString("kimi_key", "").orEmpty()
                        if (prefs.getBoolean("show_kimi", false) && key.isNotBlank()) KimiApi.fetch(key) else null
                    }.getOrNull() },
                    async { runCatching {
                        val token = prefs.getString("claude_token", "").orEmpty()
                        if (prefs.getBoolean("show_claude", false) && token.isNotBlank()) ClaudeApi.fetch(token) else null
                    }.getOrNull() },
                    async { runCatching {
                        val key = prefs.getString("minimax_key", "").orEmpty()
                        val region = prefs.getString("minimax_region", "cn").orEmpty()
                        if (prefs.getBoolean("show_minimax", false) && key.isNotBlank()) MiniMaxApi.fetch(key, region) else null
                    }.getOrNull() },
                    async { runCatching {
                        val key = prefs.getString("deepseek_key", "").orEmpty()
                        if (prefs.getBoolean("show_deepseek", false) && key.isNotBlank()) DeepSeekApi.fetch(key) else null
                    }.getOrNull() },
                    ).awaitAll().filterNotNull()
                }
            }
            swipe.isRefreshing = false
            render(results)
        }
    }

    // ---------- 渲染 ----------

    private fun render(statuses: List<ProviderStatus>) {
        root.removeAllViews()

        val visible = statuses.filter { prefs.getBoolean("show_${it.id}", it.id == "codex" || it.id == "glm") }
        if (visible.isEmpty()) {
            root.addView(tv("还没有可显示的 provider\n\n请从右上角「设置」完成配置", 15, 0xFF8A8F9E.toInt(), Gravity.CENTER))
            return
        }

        val fmtReset = SimpleDateFormat("M/d HH:mm", Locale.getDefault())
        val fmtUpd = SimpleDateFormat("HH:mm", Locale.getDefault())

        visible.forEach { st -> root.addView(providerSection(st, fmtReset)) }

        val updated = statuses.maxOfOrNull { it.updatedAt } ?: 0
        root.addView(tv("更新于 ${fmtUpd.format(Date(updated * 1000))} · 下拉刷新", 12, 0xFF5A5F6E.toInt()))

        // ---------- 费用模拟卡片 ----------
        val breakdown = com.tankecho.quotaview.data.CostEstimates.providerBreakdown(
            prefs.getString("cost_breakdown_json", null))
        breakdown.forEach { (prov, tokens) ->
            val rates = CostSimulator.DEFAULT_RATES[prov] ?: return@forEach
            val cost = CostSimulator.costUSD(tokens, rates)
            val card = card()
            card.addView(tv(when (prov) {
                "codex" -> "Codex 等量 API 成本"
                else -> "GLM 等量 API 成本"
            }, 15, 0xFF8A8F9E.toInt(), bold = true))
            card.addView(tv(CostSimulator.fmtUSD(cost), 26, 0xFFF2F3F7.toInt(), bold = true))
            card.addView(tv(
                "in ${tokens.fmtTokens(tokens.input)} · cache ${tokens.fmtTokens(tokens.cacheRead)} · out ${tokens.fmtTokens(tokens.output)}",
                12, 0xFF5A5F6E.toInt()))
            root.addView(card, vlp(top = 10, bottom = 10))
        }
    }

    /** provider 可折叠分区: header(icon+名称+⚙+chevron) + body(窗口列表) */
    private fun providerSection(st: ProviderStatus, fmtReset: SimpleDateFormat): View {
        val section = card(paddingDp = 0)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(12))
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(12), dp(10))
        }
        headerRow.addView(providerMark(st.id, 24).apply {
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(10) }
        })
        headerRow.addView(TextView(this).apply {
            text = st.name
            textSize = 18f; setTextColor(0xFFF2F3F7.toInt()); paint.isFakeBoldText = true
        })
        // 套餐胶囊标签
        if (st.plan.isNotBlank() && st.plan != "?") {
            headerRow.addView(TextView(this).apply {
                text = st.plan
                textSize = 11f; setTextColor(0xFF8FA3FF.toInt()); letterSpacing = 0.08f
                setPadding(dp(9), dp(3), dp(9), dp(3))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF1F2637.toInt())
                    cornerRadius = resources.displayMetrics.density * 20
                }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(10) }
            })
        }
        val chevron = TextView(this).apply {
            text = "▾"; textSize = 16f; setTextColor(0xFF8A8F9E.toInt())
            setPadding(dp(4), dp(2), dp(8), dp(2))
        }
        headerRow.addView(chevron)
        headerRow.setOnClickListener {
            if (collapsed.contains(st.id)) {
                collapsed.remove(st.id); body.visibility = View.VISIBLE; chevron.text = "▾"
            } else {
                collapsed.add(st.id); body.visibility = View.GONE; chevron.text = "▸"
            }
        }

        section.addView(headerRow)

        st.error?.let {
            body.addView(tv("请求失败：$it", 13, 0xFFE5484D.toInt()))
            section.addView(body)
            return section
        }

        st.windows.forEach { win ->
            body.addView(tv(win.label, 14, 0xFF8A8F9E.toInt(), bold = true))
            val race = RaceBars(this).apply {
                setProgress(win.usedPercent, win.timeElapsedPercent)
                overheated = (win.pace ?: 1f) > 1f
            }
            body.addView(race, vlp(top = 6, bottom = 4, height = dp(26)))

            // 圆点颜色仍按 PACE 内部计算, 数值不展示
            val statusDot = if (win.pace != null) "●" else "○"
            val dotColor = win.pace?.let { p -> when {
                p > 1.5f -> 0xFFE5484D.toInt()   // 红
                p > 1f -> 0xFFF5A524.toInt()     // 黄
                else -> 0xFF3DD68C.toInt()       // 绿
            } } ?: 0xFF5A5F6E.toInt()
            val timeTxt = if (win.timeElapsedPercent > 0) "${win.timeElapsedPercent}%" else "—"
            val resetTxt = if (win.resetAt > 0) "${fmtReset.format(Date(win.resetAt * 1000))} 回血" else ""
            val line = "$statusDot 用量 ${win.usedPercent}% · 已过 $timeTxt · $resetTxt"
            val span = android.text.SpannableString(line)
            span.setSpan(android.text.style.ForegroundColorSpan(dotColor), 0, 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            span.setSpan(android.text.style.RelativeSizeSpan(0.8f), 0, 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            body.addView(TextView(this).apply {
                text = span
                textSize = 13f
                setTextColor(0xFFB4B9C6.toInt())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
            })
        }
        st.balances.forEach { metric ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(2))
            }
            row.addView(tv(metric.label, 14, 0xFF8A8F9E.toInt(), bold = true).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(tv(formatBalance(metric.amount, metric.currency), 22, 0xFFF2F3F7.toInt(), bold = true).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            body.addView(row)
            metric.detail?.let {
                body.addView(tv(it, 12, 0xFF6F7482.toInt(), bottom = 10))
            }
        }
        section.addView(body)
        if (collapsed.contains(st.id)) { body.visibility = View.GONE; chevron.text = "▸" }
        return section
    }

    // ---------- 小工具 ----------

    private fun providerMark(id: String, sizeDp: Int): View {
        val icon = ProviderIcons.icon(id)
        if (icon != 0) return android.widget.ImageView(this).apply { setImageResource(icon) }
        val (letter, color) = when (id) {
            "kimi" -> "K" to 0xFF7357D9.toInt()
            "claude" -> "C" to 0xFFD97757.toInt()
            "minimax" -> "M" to 0xFF3B82F6.toInt()
            "deepseek" -> "D" to 0xFF4D6BFE.toInt()
            else -> "?" to 0xFF5A5F6E.toInt()
        }
        return TextView(this).apply {
            text = letter
            gravity = Gravity.CENTER
            textSize = sizeDp * 0.48f
            setTextColor(0xFFFFFFFF.toInt())
            paint.isFakeBoldText = true
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(color)
            }
        }
    }

    private fun formatBalance(value: Double, currency: String): String {
        val amount = when {
            value >= 100 -> "%.0f".format(value)
            value >= 1 -> "%.2f".format(value)
            else -> "%.4f".format(value)
        }
        return "$amount $currency"
    }

    private fun card(paddingDp: Int = 16): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        if (paddingDp > 0) setPadding(dp(paddingDp), dp(14), dp(paddingDp), dp(14))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF181B22.toInt())
            cornerRadius = resources.displayMetrics.density * 14
            setStroke(dp(1), 0xFF262A33.toInt())
        }
        layoutParams = vlp(top = 12, bottom = 4)
    }

    private fun vlp(top: Int = 0, bottom: Int = 0, height: Int = LinearLayout.LayoutParams.WRAP_CONTENT): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height).apply {
            topMargin = dp(top); bottomMargin = dp(bottom)
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun tv(
        text: String, sizeSp: Int, color: Int,
        gravity: Int = Gravity.START, bold: Boolean = false, bottom: Int = 0,
    ): TextView = TextView(this).apply {
        this.text = text
        this.textSize = sizeSp.toFloat()
        setTextColor(color)
        this.gravity = gravity
        if (bold) paint.isFakeBoldText = true
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            if (bottom > 0) bottomMargin = dp(bottom)
            if (gravity == Gravity.CENTER) topMargin = dp(60)
        }
    }
}
