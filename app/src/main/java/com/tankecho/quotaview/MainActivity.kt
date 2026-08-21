package com.tankecho.quotaview

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.tankecho.quotaview.data.CodexApi
import com.tankecho.quotaview.data.ClaudeApi
import com.tankecho.quotaview.data.CostSimulator
import com.tankecho.quotaview.data.DeepSeekBudgetLimits
import com.tankecho.quotaview.data.DeepSeekBudgetStatus
import com.tankecho.quotaview.data.GlmApi
import com.tankecho.quotaview.data.KimiApi
import com.tankecho.quotaview.data.MiniMaxApi
import com.tankecho.quotaview.data.ProviderMetrics
import com.tankecho.quotaview.data.ProviderRefreshCoordinator
import com.tankecho.quotaview.data.ProviderRefreshRequest
import com.tankecho.quotaview.data.ProviderStatus
import com.tankecho.quotaview.data.QuotaWindow
import com.tankecho.quotaview.data.VolcengineArkApi
import com.tankecho.quotaview.ui.DualRingView
import com.tankecho.quotaview.ui.ProviderIcons
import com.tankecho.quotaview.ui.RaceBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private lateinit var scroll: ScrollView
    private var settingsOpen = false
    private var refreshGeneration = 0L
    private var providersRefreshing = false
    private var lastStatuses: List<ProviderStatus> = emptyList()
    private val collapsed = mutableSetOf<String>()
    private val settingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        settingsOpen = false
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("qv", MODE_PRIVATE)
        if (!prefs.contains("overlay_enabled")) {
            prefs.edit()
                .putBoolean("overlay_enabled", prefs.getBoolean("island_enabled", false))
                .remove("island_enabled")
                .apply()
        }
        // v0.13.3 起改用 DeepSeek 官方日账单，清除旧版余额差估算历史。
        if (prefs.contains("deepseek_budget_history_v1")) {
            prefs.edit().remove("deepseek_budget_history_v1").apply()
        }

        // 悬浮窗: 按持久化开关恢复后台服务
        if (prefs.getBoolean("overlay_enabled", false)) {
            runCatching { ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java)) }
        }

        swipe = SwipeRefreshLayout(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(24))
            setBackgroundColor(0xFF101218.toInt())
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(root)
        }
        swipe.setOnRefreshListener { refresh() }
        swipe.setOnChildScrollUpCallback { _, _ -> scroll.canScrollVertically(-1) }
        swipe.setDistanceToTriggerSync(dp(104))
        swipe.setColorSchemeColors(0xFF8FA3FF.toInt(), 0xFF6E8BFF.toInt())
        supportActionBar?.hide()

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(14), dp(12), dp(14))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF1C2130.toInt(), 0xFF171A22.toInt()),
            ).apply {
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), 0xFF2A3040.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                leftMargin = dp(16); rightMargin = dp(16)
                topMargin = dp(18); bottomMargin = dp(10)
            }
        }
        header.addView(TextView(this).apply {
            text = "Q"
            gravity = Gravity.CENTER
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            paint.isFakeBoldText = true
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF8FA3FF.toInt(), 0xFF5B6DEB.toInt()),
            ).apply { cornerRadius = dp(13).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(12) }
        })
        header.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@MainActivity).apply {
                text = "QuotaView"
                textSize = 22f
                setTextColor(0xFFF4F5F8.toInt())
                paint.isFakeBoldText = true
            })
            addView(TextView(this@MainActivity).apply {
                text = "额度与消费，一屏掌握"
                textSize = 11.5f
                setTextColor(0xFF8991A3.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(2) }
            })
        })
        header.addView(TextView(this).apply {
            text = "设置  ›"
            textSize = 13.5f
            setTextColor(0xFFDDE2FF.toInt())
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
            setPadding(dp(13), dp(8), dp(11), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF292F43.toInt())
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), 0xFF3A4567.toInt())
            }
            setOnClickListener {
                settingsOpen = true
                settingsLauncher.launch(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        })

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF101218.toInt())
        }
        outer.addView(header)
        outer.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        ViewCompat.setOnApplyWindowInsetsListener(outer) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        swipe.addView(outer)
        setContentView(swipe)
        ViewCompat.requestApplyInsets(outer)
    }

    override fun onResume() {
        super.onResume()
        if (!settingsOpen) refresh()
    }

    private fun refresh() {
        val generation = ++refreshGeneration
        refreshJob?.cancel()
        val requests = providerRequests()
        providersRefreshing = requests.isNotEmpty()
        swipe.isRefreshing = providersRefreshing
        render(emptyList())

        if (requests.isEmpty()) {
            refreshJob = null
            return
        }

        refreshJob = lifecycleScope.launch {
            val completed = linkedMapOf<String, ProviderStatus>()
            try {
                ProviderRefreshCoordinator.collect(requests) { status ->
                    if (generation == refreshGeneration) {
                        completed[status.id] = status
                        render(completed.values.toList())
                    }
                }
            } finally {
                if (generation == refreshGeneration) {
                    providersRefreshing = false
                    swipe.isRefreshing = false
                    render(completed.values.toList())
                }
            }
        }
    }

    private fun providerRequests(): List<ProviderRefreshRequest> = buildList {
        val codexToken = prefs.getString("codex_token", "").orEmpty()
        val codexAccount = prefs.getString("codex_account", "").orEmpty()
        if (prefs.getBoolean("show_codex", true) && codexToken.isNotBlank()) {
            add(ProviderRefreshRequest("codex", "Codex") {
                withContext(Dispatchers.IO) { CodexApi.fetch(codexToken, codexAccount) }
            })
        }

        val glmKey = prefs.getString("glm_key", "").orEmpty()
        if (prefs.getBoolean("show_glm", true) && glmKey.isNotBlank()) {
            add(ProviderRefreshRequest("glm", "GLM Coding Plan") {
                withContext(Dispatchers.IO) { GlmApi.fetch(glmKey) }
            })
        }

        val kimiKey = prefs.getString("kimi_key", "").orEmpty()
        if (prefs.getBoolean("show_kimi", false) && kimiKey.isNotBlank()) {
            add(ProviderRefreshRequest("kimi", "Kimi Code") {
                withContext(Dispatchers.IO) { KimiApi.fetch(kimiKey) }
            })
        }

        val claudeToken = prefs.getString("claude_token", "").orEmpty()
        if (prefs.getBoolean("show_claude", false) && claudeToken.isNotBlank()) {
            add(ProviderRefreshRequest("claude", "Claude") {
                withContext(Dispatchers.IO) { ClaudeApi.fetch(claudeToken) }
            })
        }

        val miniMaxKey = prefs.getString("minimax_key", "").orEmpty()
        val miniMaxRegion = prefs.getString("minimax_region", "cn").orEmpty()
        if (prefs.getBoolean("show_minimax", false) && miniMaxKey.isNotBlank()) {
            add(ProviderRefreshRequest("minimax", "MiniMax") {
                withContext(Dispatchers.IO) { MiniMaxApi.fetch(miniMaxKey, miniMaxRegion) }
            })
        }

        val volcengineAccessKey = prefs.getString("volcengine_access_key", "").orEmpty()
        val volcengineSecretKey = prefs.getString("volcengine_secret_key", "").orEmpty()
        val volcengineRegion = prefs.getString("volcengine_region", "cn-beijing").orEmpty()
        if (prefs.getBoolean("show_volcengine", false) &&
            volcengineAccessKey.isNotBlank() && volcengineSecretKey.isNotBlank()
        ) {
            add(ProviderRefreshRequest("volcengine", "火山方舟") {
                withContext(Dispatchers.IO) {
                    VolcengineArkApi.fetch(volcengineAccessKey, volcengineSecretKey, volcengineRegion)
                }
            })
        }

        val deepSeekKey = prefs.getString("deepseek_key", "").orEmpty()
        if (prefs.getBoolean("show_deepseek", false) && deepSeekKey.isNotBlank()) {
            val limits = DeepSeekBudgetLimits.parse(
                prefs.getString("deepseek_budget_24h", ""),
                prefs.getString("deepseek_budget_7d", ""),
                prefs.getString("deepseek_budget_30d", ""),
            )
            val platformToken = prefs.getString("deepseek_platform_token", "").orEmpty()
            add(ProviderRefreshRequest("deepseek", "DeepSeek") {
                withContext(Dispatchers.IO) { DeepSeekBudgetStatus.fetch(deepSeekKey, platformToken, limits) }
            })
        }
    }

    // ---------- 渲染 ----------

    private fun render(statuses: List<ProviderStatus>) {
        lastStatuses = statuses
        root.removeAllViews()
        root.addView(homeViewSwitcher())

        val visible = statuses.filter { prefs.getBoolean("show_${it.id}", it.id == "codex" || it.id == "glm") }
        if (visible.isEmpty()) {
            val message = if (providersRefreshing) {
                "正在并行刷新 Provider…\n\n完成一个就会立即显示"
            } else {
                "还没有可显示的 provider\n\n请从右上角「设置」完成配置"
            }
            root.addView(tv(message, 15, 0xFF8A8F9E.toInt(), Gravity.CENTER))
            return
        }

        val fmtReset = SimpleDateFormat("M/d HH:mm", Locale.getDefault())
        val fmtUpd = SimpleDateFormat("HH:mm", Locale.getDefault())

        if (prefs.getString("home_view_style", "list") == "cards") {
            root.addView(providerRingGrid(visible))
        } else {
            visible.forEach { st -> root.addView(providerSection(st, fmtReset)) }
        }

        val updated = statuses.maxOfOrNull { it.updatedAt } ?: 0
        val refreshHint = if (providersRefreshing) "仍在刷新其他 Provider…" else "下拉刷新"
        root.addView(tv("更新于 ${fmtUpd.format(Date(updated * 1000))} · $refreshHint", 12, 0xFF5A5F6E.toInt()))

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

    /** 一键切换主页 Provider 展示：原有折叠列表 / 圆环卡片。 */
    private fun homeViewSwitcher(): View {
        val active = prefs.getString("home_view_style", "list") ?: "list"
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(2))
            addView(TextView(this@MainActivity).apply {
                text = "主页视图"
                textSize = 12f
                setTextColor(0xFF697184.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            listOf("折叠列表" to "list", "圆环卡片" to "cards").forEach { (label, key) ->
                addView(TextView(this@MainActivity).apply {
                    text = label
                    textSize = 12.5f
                    gravity = Gravity.CENTER
                    setTextColor(if (key == active) 0xFFFFFFFF.toInt() else 0xFF8A91A1.toInt())
                    setPadding(dp(12), dp(7), dp(12), dp(7))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(if (key == active) 0xFF5F73E8.toInt() else 0xFF1B1F28.toInt())
                        cornerRadius = dp(16).toFloat()
                        setStroke(dp(1), if (key == active) 0xFF7487F2.toInt() else 0xFF2A303C.toInt())
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { leftMargin = dp(7) }
                    setOnClickListener {
                        if (key != active) {
                            prefs.edit().putString("home_view_style", key).apply()
                            render(lastStatuses)
                        }
                    }
                })
            }
        }
    }

    /** 手机单列，宽屏三列；每张卡只使用圆环作为进度可视化。 */
    private fun providerRingGrid(statuses: List<ProviderStatus>): View {
        val columns = if (resources.configuration.screenWidthDp >= 720) 3 else 1
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        statuses.chunked(columns).forEach { group ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
            }
            group.forEach { status ->
                row.addView(providerRingCard(status, compact = columns > 1), LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                ).apply {
                    leftMargin = dp(4)
                    rightMargin = dp(4)
                    topMargin = dp(8)
                    bottomMargin = dp(4)
                })
            }
            repeat(columns - group.size) {
                row.addView(Space(this), LinearLayout.LayoutParams(0, 1, 1f).apply {
                    leftMargin = dp(4)
                    rightMargin = dp(4)
                })
            }
            container.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
        return container
    }

    private fun providerRingCard(st: ProviderStatus, compact: Boolean): View {
        val selectedKey = prefs.getString("home_metric_${st.id}", null)
        val metric = ProviderMetrics.select(st, selectedKey)
        val balance = st.balances.firstOrNull().takeIf { metric == null }
        val ringSize = dp(if (compact) 78 else 112)
        val color = metricHealthColor(metric)
        return card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(if (compact) 9 else 14), dp(13), dp(if (compact) 9 else 14), dp(13))

            addView(DualRingView(this@MainActivity).apply {
                usedPercent = metric?.usedPercent?.toFloat() ?: 0f
                timeElapsedPercent = metric?.timeElapsedPercent?.toFloat() ?: 0f
                ringColor = color
                iconRes = ProviderIcons.icon(st.id)
                centerText = if (iconRes == 0) st.name.take(1).uppercase() else null
                centerTextSizeSp = if (compact) 15f else 18f
            }, LinearLayout.LayoutParams(ringSize, ringSize).apply {
                rightMargin = dp(if (compact) 9 else 16)
            })

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@MainActivity).apply {
                    text = st.name
                    textSize = if (compact) 13.5f else 17f
                    setTextColor(0xFFF2F3F7.toInt())
                    paint.isFakeBoldText = true
                    maxLines = 1
                })
                addView(TextView(this@MainActivity).apply {
                    text = buildList {
                        if (st.plan.isNotBlank() && st.plan != "?") add(st.plan)
                        add(when {
                            st.error != null -> "请求失败"
                            metric != null -> metric.label
                            balance != null -> balance.label
                            else -> "暂无可用指标"
                        })
                    }.joinToString(" · ")
                    textSize = if (compact) 10f else 11.5f
                    setTextColor(if (st.error != null) 0xFFE5484D.toInt() else 0xFF81899A.toInt())
                    maxLines = 1
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(3) }
                })
                addView(TextView(this@MainActivity).apply {
                    text = metric?.let { "${it.usedPercent}%" }
                        ?: balance?.let { formatBalance(it.amount, it.currency) }
                        ?: "—"
                    textSize = if (compact) 20f else 28f
                    setTextColor(if (st.error != null) 0xFFE5484D.toInt() else color)
                    paint.isFakeBoldText = true
                    maxLines = 1
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(if (compact) 2 else 4) }
                })
                addView(TextView(this@MainActivity).apply {
                    text = ringMetricDetail(st, metric)
                    textSize = if (compact) 9.5f else 11.5f
                    setTextColor(0xFF737B8C.toInt())
                    maxLines = 2
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(2) }
                })
            })
        }
    }

    private fun ringMetricDetail(st: ProviderStatus, metric: QuotaWindow?): String {
        if (st.error != null) return st.error
        if (metric == null) return st.balances.firstOrNull()?.detail
            ?: if (st.balances.isNotEmpty()) "账户余额" else "请在设置中配置并选择主页圆环指标"
        if (metric.selectionKey.startsWith("budget_")) {
            val days = when (metric.selectionKey) {
                "budget_today" -> 1
                "budget_7d" -> 7
                else -> 30
            }
            val budget = st.budgets.firstOrNull { it.periodDays == days }
            return if (budget == null) "预算数据尚未返回" else
                "${formatBalance(budget.spent, budget.currency)} / ${formatBalance(budget.limit, budget.currency)}"
        }
        return metric.timeElapsedPercent.takeIf { it > 0 }
            ?.let { "时间已过 $it%" }
            ?: "时间进度暂不可用"
    }

    private fun metricHealthColor(metric: QuotaWindow?): Int = when {
        metric == null -> 0xFF6E8BFF.toInt()
        metric.selectionKey.startsWith("budget_") && metric.usedPercent >= 100 -> 0xFFE5484D.toInt()
        metric.selectionKey.startsWith("budget_") && metric.usedPercent >= 80 -> 0xFFF5A524.toInt()
        metric.selectionKey.startsWith("budget_") -> 0xFF46A758.toInt()
        metric.pace == null -> 0xFF6E8BFF.toInt()
        metric.pace!! > 1.5f -> 0xFFE5484D.toInt()
        metric.pace!! > 1f -> 0xFFF5A524.toInt()
        else -> 0xFF46A758.toInt()
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
        st.budgets.forEach { budget ->
            body.addView(tv(budget.label, 14, 0xFF8A8F9E.toInt(), bold = true))
            val race = RaceBars(this).apply {
                showTimeBar = false
                setProgress(budget.usedPercent, 0)
                overheated = budget.usedPercent >= 100
            }
            body.addView(race, vlp(top = 6, bottom = 4, height = dp(18)))

            val dotColor = when {
                budget.usedPercent >= 100 -> 0xFFE5484D.toInt()
                budget.usedPercent >= 80 -> 0xFFF5A524.toInt()
                else -> 0xFF3DD68C.toInt()
            }
            val line = "● 支出 ${formatBalance(budget.spent, budget.currency)} / " +
                "预算 ${formatBalance(budget.limit, budget.currency)} · ${budget.usedPercent}%"
            val span = android.text.SpannableString(line).apply {
                setSpan(android.text.style.ForegroundColorSpan(dotColor), 0, 1,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.RelativeSizeSpan(0.8f), 0, 1,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            body.addView(TextView(this).apply {
                text = span
                textSize = 13f
                setTextColor(0xFFB4B9C6.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(12) }
            })
        }
        st.detailMessage?.let { message ->
            body.addView(tv(
                message,
                12,
                if (st.detailMessageIsError) 0xFFE5484D.toInt() else 0xFF6F7482.toInt(),
                bottom = 4,
            ))
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
            "volcengine" -> "V" to 0xFF1664FF.toInt()
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
