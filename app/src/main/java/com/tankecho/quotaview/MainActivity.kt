package com.tankecho.quotaview

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.tankecho.quotaview.data.CodexApi
import com.tankecho.quotaview.data.CostSimulator
import com.tankecho.quotaview.data.GlmApi
import com.tankecho.quotaview.data.ProviderStatus
import com.tankecho.quotaview.ui.RaceBars
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var prefs: SharedPreferences
    private lateinit var root: LinearLayout
    private lateinit var swipe: SwipeRefreshLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("qv", MODE_PRIVATE)

        // ---------- 纯代码 UI (暗色, 无 ActionBar) ----------
        swipe = SwipeRefreshLayout(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
            setBackgroundColor(0xFF101218.toInt())
        }
        val scroll = ScrollView(this).apply { addView(root) }
        swipe.setOnRefreshListener { refresh() }
        supportActionBar?.hide()

        // header: 标题 + ⚙️ 设置入口
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
            text = "⚙️"
            textSize = 22f
            setPadding(dp(12), dp(4), dp(4), dp(8))
            setOnClickListener { startActivity(android.content.Intent(this@MainActivity, SettingsActivity::class.java)) }
        })

        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(header)
        outer.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        swipe.addView(outer)
        setContentView(swipe)
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        swipe.isRefreshing = true
        scope.launch {
            val results = withContext(Dispatchers.IO) {
                listOfNotNull(
                    runCatching {
                        val tok = prefs.getString("codex_token", "").orEmpty()
                        val acct = prefs.getString("codex_account", "").orEmpty()
                        if (tok.isNotBlank()) CodexApi.fetch(tok, acct) else null
                    }.getOrNull(),
                    runCatching {
                        val key = prefs.getString("glm_key", "").orEmpty()
                        if (key.isNotBlank()) GlmApi.fetch(key) else null
                    }.getOrNull(),
                )
            }
            swipe.isRefreshing = false
            render(results)
        }
    }

    // ---------- 渲染 ----------

    private fun render(statuses: List<ProviderStatus>) {
        root.removeAllViews()
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }

        if (statuses.isEmpty()) {
            root.addView(tv(this, "⚙️ 还没配置凭证\n\n右上角 ⋮ → Settings\n填入 Codex token 或 GLM API key", 15, 0xFF8A8F9E.toInt(), Gravity.CENTER))
            return
        }

        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        statuses.forEach { st ->
            // provider header
            root.addView(tv(this, "${st.name} · ${st.plan}", 22, 0xFFF2F3F7.toInt(), Gravity.START, bold = true))
            st.error?.let { root.addView(tv(this, "⚠️ ${it}", 13, 0xFFE5484D.toInt())); return@forEach }

            st.windows.forEach { win ->
                // window card
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    background = gradientDrawable()
                }
                card.addView(tv(this, win.label, 15, 0xFF8A8F9E.toInt(), bold = true))
                val race = RaceBars(this).apply {
                    usedPercent = win.usedPercent
                    timePercent = win.timeElapsedPercent
                    overheated = (win.pace ?: 1f) > 1f
                }
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(26))
                lp.topMargin = dp(8); lp.bottomMargin = dp(6)
                card.addView(race, lp)

                // metrics row: used% · PACE · reset
                val paceTxt = win.pace?.let { p ->
                    when {
                        p > 1.5f -> "PACE %.2f 🔥".format(p)
                        p > 1f -> "PACE %.2f ⚠️".format(p)
                        else -> "PACE %.2f ✅".format(p)
                    }
                } ?: "—"
                val resetTxt = if (win.resetAt > 0) "${fmt.format(Date(win.resetAt * 1000))} 回血" else ""
                card.addView(tv(this, "${win.usedPercent}% 已用 · ${paceTxt} · ${resetTxt}", 13, 0xFFB4B9C6.toInt()))

                val clp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                clp.topMargin = dp(10); clp.bottomMargin = dp(10)
                root.addView(card, clp)
            }
        }

        // footer
        val updated = statuses.maxOfOrNull { it.updatedAt } ?: 0
        root.addView(tv(this, "更新于 ${fmt.format(Date(updated * 1000))} · 下拉刷新", 12, 0xFF5A5F6E.toInt()))

        // ---------- 费用模拟卡片 (本地估算, 价目表可配) ----------
        val breakdown = com.tankecho.quotaview.data.CostEstimates.providerBreakdown(
            prefs.getString("cost_breakdown_json", null))
        breakdown.forEach { (prov, tokens) ->
            val rates = CostSimulator.DEFAULT_RATES[prov] ?: return@forEach
            val cost = CostSimulator.costUSD(tokens, rates)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = gradientDrawable()
            }
            card.addView(tv(this, when (prov) {
                "codex" -> "Codex 等量 API 成本"
                else -> "GLM 等量 API 成本"
            }, 15, 0xFF8A8F9E.toInt(), bold = true))
            card.addView(tv(this, "${CostSimulator.fmtUSD(cost)}", 26, 0xFFF2F3F7.toInt(), bold = true))
            card.addView(tv(this,
                "in ${tokens.fmtTokens(tokens.input)} · cache ${tokens.fmtTokens(tokens.cacheRead)} · out ${tokens.fmtTokens(tokens.output)}",
                12, 0xFF5A5F6E.toInt()))
            val clp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            clp.topMargin = dp(10); clp.bottomMargin = dp(10)
            root.addView(card, clp)
        }
    }

    private fun gradientDrawable(): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF181B22.toInt())
            cornerRadius = resources.displayMetrics.density * 14
            setStroke(dp(1), 0xFF262A33.toInt())
        }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun tv(
        ctx: Context, text: String, sizeSp: Int, color: Int,
        gravity: Int = Gravity.START, bold: Boolean = false,
    ): TextView = TextView(ctx).apply {
        this.text = text
        this.textSize = sizeSp.toFloat()
        setTextColor(color)
        this.gravity = gravity
        if (bold) paint.isFakeBoldText = true
        if (gravity == Gravity.CENTER) {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(40)
            }
        }
    }
}
