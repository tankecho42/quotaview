package com.tankecho.quotaview

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.tankecho.quotaview.data.CodexApi
import com.tankecho.quotaview.data.ClaudeApi
import com.tankecho.quotaview.data.DeepSeekApi
import com.tankecho.quotaview.data.DeepSeekBudgetLimits
import com.tankecho.quotaview.data.DeepSeekBudgetStatus
import com.tankecho.quotaview.data.GlmApi
import com.tankecho.quotaview.data.KimiApi
import com.tankecho.quotaview.data.MiniMaxApi
import com.tankecho.quotaview.data.ProviderStatus
import com.tankecho.quotaview.data.meterWindows
import com.tankecho.quotaview.ui.ProviderIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private var previewJob: Job? = null
    private lateinit var ringPreview: com.tankecho.quotaview.ui.DualRingView
    private lateinit var prefs: SharedPreferences
    private data class FieldDef(
        val key: String,
        val label: String,
        val hint: String,
        val multiline: Boolean = false,
        val secret: Boolean = false,
        val required: Boolean = true,
        val numeric: Boolean = false,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("qv", MODE_PRIVATE)
        if (!prefs.contains("minimax_region")) prefs.edit().putString("minimax_region", "cn").apply()

        supportActionBar?.hide()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
            setBackgroundColor(0xFF101218.toInt())
        }

        // header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "←"; textSize = 22f; setTextColor(0xFF6E8BFF.toInt())
            setPadding(0, dp(4), dp(16), dp(4))
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = "Settings"; textSize = 22f; setTextColor(0xFFF2F3F7.toInt()); paint.isFakeBoldText = true
        })
        root.addView(header)
        root.addView(sectionLabel("PROVIDERS"))

        root.addView(providerCard(prefs, "codex", R.drawable.ic_openai, "Codex", "ChatGPT Plan · wham/usage",
            listOf(
                FieldDef("codex_token", "access_token", "~/.codex/auth.json → tokens.access_token", secret = true),
                FieldDef("codex_account", "account_id", "auth.json → tokens.account_id"),
            )) { CodexApi.fetch(prefs.getString("codex_token", "").orEmpty(), prefs.getString("codex_account", "").orEmpty()) })

        root.addView(providerCard(prefs, "glm", R.drawable.ic_zai, "GLM Coding Plan", "bigmodel · quota/limit",
            listOf(
                FieldDef("glm_key", "API key", "open.bigmodel.cn 的 API key", secret = true),
            )) { GlmApi.fetch(prefs.getString("glm_key", "").orEmpty()) })

        root.addView(providerCard(prefs, "kimi", 0, "Kimi Code", "Coding Plan · 5h / 周额度",
            listOf(
                FieldDef("kimi_key", "Kimi Code API key", "api.kimi.com 的 Coding Plan key", secret = true),
            ), defaultEnabled = false) {
            KimiApi.fetch(prefs.getString("kimi_key", "").orEmpty())
        })

        root.addView(providerCard(prefs, "claude", 0, "Claude", "Claude.ai · 5h / 周额度",
            listOf(
                FieldDef("claude_token", "OAuth access token", "~/.claude/.credentials.json → claudeAiOauth.accessToken", secret = true),
            ), defaultEnabled = false) {
            ClaudeApi.fetch(prefs.getString("claude_token", "").orEmpty())
        })

        root.addView(providerCard(prefs, "minimax", 0, "MiniMax", "Coding Plan · 5h / 周额度",
            listOf(
                FieldDef("minimax_key", "API key / OAuth token", "MiniMax Coding Plan 凭证", secret = true),
                FieldDef("minimax_region", "Region", "cn 或 global"),
            ), defaultEnabled = false) {
            MiniMaxApi.fetch(
                prefs.getString("minimax_key", "").orEmpty(),
                prefs.getString("minimax_region", "cn").orEmpty(),
            )
        })

        root.addView(providerCard(prefs, "deepseek", 0, "DeepSeek", "官方余额 · 官方日账单预算",
            listOf(
                FieldDef("deepseek_key", "API key", "platform.deepseek.com 的 API key", secret = true),
                FieldDef(
                    "deepseek_platform_token",
                    "Platform userToken（预算必填）",
                    "登录控制台后 localStorage → userToken",
                    secret = true,
                    required = false,
                ),
                FieldDef("deepseek_budget_24h", "今日预算", "例如 20；留空则不显示", required = false, numeric = true),
                FieldDef("deepseek_budget_7d", "近 7 日预算", "例如 100；留空则不显示", required = false, numeric = true),
                FieldDef("deepseek_budget_30d", "近 30 日预算", "例如 300；留空则不显示", required = false, numeric = true),
            ), defaultEnabled = false) {
            DeepSeekApi.fetch(prefs.getString("deepseek_key", "").orEmpty())
        })

        root.addView(unavailableProviderCard(
            "qwen", "Qwen / 阿里云百炼", "Coding Plan",
            "官方目前没有开放可查询剩余额度的 API；仅能在控制台查看，QuotaView 不会用本地估算冒充真实额度。",
        ))

        // ---------- 灵动岛配置 ----------
        root.addView(sectionLabel("LIVE RING · 悬浮窗"))
        root.addView(liveRingCard(prefs))

        root.addView(sectionLabel("ABOUT"))
        root.addView(footer())

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onPause() {
        super.onPause()
        if (::prefs.isInitialized && prefs.getBoolean("island_enabled", false)) {
            startService(Intent(this, IslandService::class.java).setAction(IslandService.ACTION_REFRESH))
        }
    }

    // ---------- provider 卡片: 开关 + 内嵌配置 + 打开即验证 ----------

    private fun providerCard(
        prefs: SharedPreferences, id: String, iconRes: Int, title: String, subtitle: String,
        fields: List<FieldDef>, defaultEnabled: Boolean = true, validator: () -> ProviderStatus,
    ): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(13), dp(16), dp(14))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF181B22.toInt())
                cornerRadius = resources.displayMetrics.density * 14
                setStroke(dp(1), 0xFF262A33.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }

        val hasConfig = fields.any { !prefs.getString(it.key, "").isNullOrBlank() }

        // header row: logo + 标题列 + chevron + switch
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(providerMark(id, iconRes, 22).apply {
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(10) }
        })
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(this).apply {
            this.text = title; textSize = 16f; setTextColor(0xFFF2F3F7.toInt()); paint.isFakeBoldText = true
        })
        titleCol.addView(TextView(this).apply {
            this.text = subtitle; textSize = 12f; setTextColor(0xFF5A5F6E.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) }
        })
        val chevron = TextView(this).apply {
            text = if (hasConfig) "▾" else "▾"; textSize = 15f; setTextColor(0xFF8A8F9E.toInt())
            setPadding(dp(10), dp(2), dp(10), dp(2))
        }
        val sw = SwitchCompat(this).apply {
            isChecked = prefs.getBoolean("show_$id", defaultEnabled)
        }
        headerRow.addView(titleCol)
        headerRow.addView(chevron)
        headerRow.addView(sw)
        card.addView(headerRow)

        // body: 配置项内嵌
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
        }
        fields.forEach { f ->
            body.addView(fieldLabel(f.label))
            body.addView(input(prefs, f.key, f.hint, f.multiline, f.secret, f.numeric))
        }
        if (id == "deepseek") {
            body.addView(TextView(this).apply {
                text = "预算金额与余额币种一致，消费来自 DeepSeek Platform 官方日账单。API key 只能读取余额；预算还需要网页登录态 userToken。电脑端登录控制台后，可在开发者工具 Console 执行 localStorage.getItem('userToken') 获取。"
                textSize = 12f
                setTextColor(0xFF6F7482.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(10) }
            })
            body.addView(TextView(this).apply {
                text = "保存并刷新首页"
                textSize = 13.5f
                setTextColor(0xFFFFFFFF.toInt())
                paint.isFakeBoldText = true
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF5F73E8.toInt())
                    cornerRadius = dp(12).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(12) }
                setOnClickListener {
                    if (!sw.isChecked) {
                        Toast.makeText(this@SettingsActivity, "请先启用 DeepSeek", Toast.LENGTH_SHORT).show()
                    } else {
                        setResult(RESULT_OK)
                        finish()
                    }
                }
            })
        }
        card.addView(body)
        if (!hasConfig) body.visibility = View.GONE else chevron.text = "▾"

        headerRow.setOnClickListener {
            body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            chevron.text = if (body.visibility == View.VISIBLE) "▾" else "▸"
        }

        // 打开即验证: 通过才保持开, 失败强制关闭
        var validationJob: Job? = null
        sw.setOnCheckedChangeListener { _, checked ->
            validationJob?.cancel()
            if (checked) {
                val missing = fields.any { it.required && prefs.getString(it.key, "").isNullOrBlank() }
                if (missing) {
                    sw.isChecked = false
                    Toast.makeText(this, "$title 还没填完整配置", Toast.LENGTH_SHORT).show()
                    body.visibility = View.VISIBLE; chevron.text = "▾"
                    return@setOnCheckedChangeListener
                }
                validationJob = lifecycleScope.launch {
                    val st = withContext(Dispatchers.IO) { runCatching(validator).getOrNull() }
                    val ok = st != null && st.error == null && st.hasData
                    if (ok) {
                        prefs.edit().putBoolean("show_$id", true).apply()
                        val count = st!!.windows.size + st.balances.size
                        Toast.makeText(this@SettingsActivity, "$title 连通，读取到 $count 项数据", Toast.LENGTH_SHORT).show()
                    } else {
                        sw.isChecked = false
                        prefs.edit().putBoolean("show_$id", false).apply()
                        val reason = st?.error ?: "网络错误"
                        Toast.makeText(this@SettingsActivity, "$title 配置无法连通：$reason", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                prefs.edit().putBoolean("show_$id", false).apply()
                if (prefs.getString("ring_provider", "codex") == id) {
                    val fallback = listOf("codex", "glm", "kimi", "claude", "minimax", "deepseek")
                        .firstOrNull { it != id && ringProviderReady(prefs, it) }
                    if (fallback != null) {
                        prefs.edit().putString("ring_provider", fallback).apply()
                        if (::ringPreview.isInitialized) {
                            ringPreview.iconRes = providerIcon(fallback)
                            ringPreview.centerText = if (ringPreview.iconRes == 0) providerInitial(fallback) else null
                            refreshRingPreview(prefs)
                        }
                        if (prefs.getBoolean("island_enabled", false)) {
                            startService(Intent(this@SettingsActivity, IslandService::class.java).setAction(IslandService.ACTION_REFRESH))
                        }
                    }
                }
            }
        }
        return card
    }

    private fun unavailableProviderCard(id: String, title: String, subtitle: String, reason: String): LinearLayout {
        val card = section("", "")
        card.removeAllViews()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(providerMark(id, 0, 22).apply {
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(10) }
        })
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titles.addView(TextView(this).apply {
            text = title; textSize = 16f; setTextColor(0xFFF2F3F7.toInt()); paint.isFakeBoldText = true
        })
        titles.addView(TextView(this).apply {
            text = subtitle; textSize = 12f; setTextColor(0xFF5A5F6E.toInt())
        })
        row.addView(titles)
        row.addView(TextView(this).apply {
            text = "待开放"; textSize = 11f; setTextColor(0xFFF5A524.toInt())
            setPadding(dp(8), dp(3), dp(8), dp(3))
        })
        card.addView(row)
        card.addView(TextView(this).apply {
            text = reason; textSize = 12f; setTextColor(0xFF8A8F9E.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        })
        return card
    }

    // ---------- 灵动岛配置卡片 ----------

    private val standardWindowOptions = listOf("主窗口" to "primary", "周窗口" to "week", "MCP 工具" to "mcp")

    private fun deepSeekLimits(prefs: SharedPreferences): DeepSeekBudgetLimits = DeepSeekBudgetLimits.parse(
        prefs.getString("deepseek_budget_24h", ""),
        prefs.getString("deepseek_budget_7d", ""),
        prefs.getString("deepseek_budget_30d", ""),
    )

    private fun windowOptions(prefs: SharedPreferences, provider: String): List<Pair<String, String>> {
        if (provider != "deepseek") return standardWindowOptions
        val limits = deepSeekLimits(prefs)
        return buildList {
            if (limits.today > 0) add("今日" to "budget_today")
            if (limits.last7Days > 0) add("近 7 日" to "budget_7d")
            if (limits.last30Days > 0) add("近 30 日" to "budget_30d")
        }.ifEmpty {
            listOf("今日" to "budget_today", "近 7 日" to "budget_7d", "近 30 日" to "budget_30d")
        }
    }

    private fun liveRingCard(prefs: SharedPreferences): LinearLayout {
        val card = section("", "")
        card.removeAllViews()

        // 标题行 + 总开关
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(this).apply {
            text = "悬浮窗展示"
            textSize = 16f; setTextColor(0xFFF2F3F7.toInt()); paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val islandSwitch = SwitchCompat(this).apply {
            isChecked = getSharedPreferences("qv", MODE_PRIVATE).getBoolean("island_enabled", false)
        }
        titleRow.addView(islandSwitch)
        card.addView(titleRow)
        islandSwitch.setOnCheckedChangeListener { _, checked ->
            val prefs = getSharedPreferences("qv", MODE_PRIVATE)
            if (checked) {
                if (!android.provider.Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "请先允许「显示在其他应用上层」权限", Toast.LENGTH_LONG).show()
                    startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")))
                    islandSwitch.isChecked = false
                } else if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
                    islandSwitch.isChecked = false
                    Toast.makeText(this, "请先允许通知权限", Toast.LENGTH_LONG).show()
                } else {
                    prefs.edit().putBoolean("island_enabled", true).apply()
                    ContextCompat.startForegroundService(this, Intent(this, IslandService::class.java))
                    Toast.makeText(this, "悬浮圆环已启动", Toast.LENGTH_SHORT).show()
                }
            } else {
                prefs.edit().putBoolean("island_enabled", false).apply()
                val serviceIntent = Intent(this, IslandService::class.java)
                stopService(serviceIntent)
            }
        }

        // 预览环
        ringPreview = com.tankecho.quotaview.ui.DualRingView(this).apply {
            val id = prefs.getString("ring_provider", "codex") ?: "codex"
            iconRes = providerIcon(id)
            centerText = if (iconRes == 0) providerInitial(id) else null
        }
        val previewWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        previewWrap.addView(ringPreview, LinearLayout.LayoutParams(dp(120), dp(120)).apply { rightMargin = dp(20) })
        val legend = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val outerLegend = TextView(this).apply {
            text = "外环 · 额度用量"; textSize = 13f; setTextColor(0xFF8FA3FF.toInt()); paint.isFakeBoldText = true
        }
        val innerLegend = TextView(this).apply {
            text = "内环 · 时间进度"; textSize = 13f; setTextColor(0xFF9BA1B0.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        }
        legend.addView(outerLegend)
        legend.addView(innerLegend)
        legend.addView(TextView(this).apply {
            text = "开屏即见, 无需打开 App"
            textSize = 12f; setTextColor(0xFF5A5F6E.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        })
        previewWrap.addView(legend)
        card.addView(previewWrap, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12); bottomMargin = dp(4)
        })

        val winRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun rebuildWindowRow() {
            val provider = prefs.getString("ring_provider", "codex") ?: "codex"
            val options = windowOptions(prefs, provider)
            val saved = prefs.getString("ring_window", options.first().second) ?: options.first().second
            val active = saved.takeIf { key -> options.any { it.second == key } } ?: options.first().second
            if (active != saved) prefs.edit().putString("ring_window", active).apply()
            winRow.removeAllViews()
            options.forEach { (name, key) ->
                winRow.addView(makeChip(name, key == active) {
                    prefs.edit().putString("ring_window", key).apply()
                    if (prefs.getBoolean("island_enabled", false)) {
                        startService(Intent(this@SettingsActivity, IslandService::class.java).setAction(IslandService.ACTION_REFRESH))
                    }
                    rebuildWindowRow()
                    refreshRingPreview(prefs)
                })
            }
            val isBudget = provider == "deepseek"
            outerLegend.text = if (isBudget) "外环 · 预算使用" else "外环 · 额度用量"
            innerLegend.text = if (isBudget) "内环 · 无时间进度" else "内环 · 时间进度"
        }

        // Provider 选择 (icon + 名称 的胶囊单选)
        card.addView(fieldLabel("Provider"))
        val provRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun rebuildProvRow() {
            val cur = prefs.getString("ring_provider", "codex")
            provRow.removeAllViews()
            listOf(
                "Codex" to "codex", "GLM" to "glm", "Kimi" to "kimi",
                "Claude" to "claude", "MiniMax" to "minimax", "DeepSeek" to "deepseek",
            ).forEach { (name, id) ->
                provRow.addView(makeProviderChip(name, id, id == cur) {
                    if (id != cur) {
                        if (!ringProviderReady(prefs, id)) {
                            Toast.makeText(this@SettingsActivity, "请先配置并启用 $name", Toast.LENGTH_SHORT).show()
                        } else {
                            prefs.edit().putString("ring_provider", id).apply()
                            val options = windowOptions(prefs, id)
                            val currentWindow = prefs.getString("ring_window", "")
                            if (options.none { it.second == currentWindow }) {
                                prefs.edit().putString("ring_window", options.first().second).apply()
                            }
                            if (prefs.getBoolean("island_enabled", false)) {
                                startService(Intent(this@SettingsActivity, IslandService::class.java).setAction(IslandService.ACTION_REFRESH))
                            }
                            ringPreview.iconRes = providerIcon(id)
                            ringPreview.centerText = if (ringPreview.iconRes == 0) providerInitial(id) else null
                            rebuildWindowRow()
                            refreshRingPreview(prefs)
                        }
                    }
                    rebuildProvRow()
                })
            }
        }
        card.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(provRow)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        // 窗口类型：DeepSeek 使用自然日预算，其余 Provider 使用额度窗口。
        card.addView(fieldLabel("额度类型"))
        card.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(winRow)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        rebuildProvRow()
        rebuildWindowRow()
        refreshRingPreview(prefs)

        return card
    }

    private fun refreshRingPreview(prefs: SharedPreferences) {
        previewJob?.cancel()
        previewJob = lifecycleScope.launch {
            val st = withContext(Dispatchers.IO) {
                val prov = prefs.getString("ring_provider", "codex")!!
                runCatching {
                    when (prov) {
                        "codex" -> CodexApi.fetch(prefs.getString("codex_token", "").orEmpty(), prefs.getString("codex_account", "").orEmpty())
                        "glm" -> GlmApi.fetch(prefs.getString("glm_key", "").orEmpty())
                        "kimi" -> KimiApi.fetch(prefs.getString("kimi_key", "").orEmpty())
                        "claude" -> ClaudeApi.fetch(prefs.getString("claude_token", "").orEmpty())
                        "minimax" -> MiniMaxApi.fetch(
                            prefs.getString("minimax_key", "").orEmpty(),
                            prefs.getString("minimax_region", "cn").orEmpty(),
                        )
                        "deepseek" -> DeepSeekBudgetStatus.fetch(
                            prefs.getString("deepseek_key", "").orEmpty(),
                            prefs.getString("deepseek_platform_token", "").orEmpty(),
                            deepSeekLimits(prefs),
                        )
                        else -> null
                    }
                }.getOrNull()
            }
            val winKey = prefs.getString("ring_window", "primary") ?: "primary"
            val windows = st?.meterWindows().orEmpty()
            val win = windows.firstOrNull { it.selectionKey == winKey } ?: windows.firstOrNull()
            ringPreview.usedPercent = win?.usedPercent?.toFloat() ?: 0f
            ringPreview.timeElapsedPercent = win?.timeElapsedPercent?.toFloat() ?: 0f
            ringPreview.ringColor = when {
                win == null -> 0xFF6E8BFF.toInt()
                win.selectionKey.startsWith("budget_") && win.usedPercent >= 100 -> 0xFFE5484D.toInt()
                win.selectionKey.startsWith("budget_") && win.usedPercent >= 80 -> 0xFFF5A524.toInt()
                win.selectionKey.startsWith("budget_") -> 0xFF46A758.toInt()
                win.pace == null -> 0xFF6E8BFF.toInt()
                win.pace!! > 1.5f -> 0xFFE5484D.toInt()
                win.pace!! > 1f -> 0xFFF5A524.toInt()
                else -> 0xFF6E8BFF.toInt()
            }
            ringPreview.invalidate()
        }
    }

    /** Provider 胶囊: 官方 icon + 名称, 选中态靛蓝描边 */
    private fun makeProviderChip(name: String, id: String, selected: Boolean, onClick: (Boolean) -> Unit): android.view.View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = dp(12)
            setPadding(pad, (pad * 0.7f).toInt(), pad, (pad * 0.7f).toInt())
            val cornerBg = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(if (selected) 0x266E8BFF.toInt() else 0xFF1A1E27.toInt())
                setStroke(dp(1).toInt(), if (selected) 0xFF6E8BFF.toInt() else 0xFF2A2F3B.toInt())
            }
            background = cornerBg
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.rightMargin = dp(8).toInt()
            layoutParams = lp

            addView(providerMark(id, providerIcon(id), 18).apply {
                layoutParams = LinearLayout.LayoutParams(dp(18).toInt(), dp(18).toInt()).apply { rightMargin = dp(6).toInt() }
            })
            addView(TextView(this@SettingsActivity).apply {
                text = name
                textSize = 13.5f
                setTextColor(if (selected) 0xFFE6E8EE.toInt() else 0xFF9BA1B0.toInt())
                paint.isFakeBoldText = selected
            })
            setOnClickListener { onClick(true) }   // 状态与重建由外部统一管理 (修复转发闭包导致的切换失效)
        }

    private fun makeChip(text: String, selected: Boolean, onClick: (Boolean) -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        setPadding(dp(14), dp(7), dp(14), dp(7))
        gravity = Gravity.CENTER
        background = android.graphics.drawable.GradientDrawable().apply {
            color = android.content.res.ColorStateList.valueOf(if (selected) 0xFF6E8BFF.toInt() else 0xFF262A33.toInt())
            cornerRadius = resources.displayMetrics.density * 20
        }
        setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFF8A8F9E.toInt())
        setOnClickListener { onClick(!selected) }
    }

    private fun providerMark(id: String, iconRes: Int, sizeDp: Int): View {
        val resolvedIcon = iconRes.takeIf { it != 0 } ?: ProviderIcons.icon(id)
        if (resolvedIcon != 0) return android.widget.ImageView(this).apply { setImageResource(resolvedIcon) }
        val (letter, color) = when (id) {
            "kimi" -> "K" to 0xFF7357D9.toInt()
            "claude" -> "C" to 0xFFD97757.toInt()
            "qwen" -> "Q" to 0xFF615CED.toInt()
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

    private fun providerIcon(id: String): Int = ProviderIcons.icon(id)

    private fun providerInitial(id: String): String = when (id) {
        "kimi" -> "K"
        "claude" -> "C"
        "minimax" -> "M"
        "qwen" -> "Q"
        "deepseek" -> "D"
        else -> "?"
    }

    private fun ringProviderReady(prefs: SharedPreferences, id: String): Boolean {
        val enabled = prefs.getBoolean("show_$id", id == "codex" || id == "glm")
        if (id == "deepseek") {
            return enabled &&
                !prefs.getString("deepseek_key", "").isNullOrBlank() &&
                !prefs.getString("deepseek_platform_token", "").isNullOrBlank() &&
                deepSeekLimits(prefs).isConfigured
        }
        val credentialKey = when (id) {
            "codex" -> "codex_token"
            "glm" -> "glm_key"
            "kimi" -> "kimi_key"
            "claude" -> "claude_token"
            "minimax" -> "minimax_key"
            else -> return false
        }
        return enabled && !prefs.getString(credentialKey, "").isNullOrBlank()
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f; setTextColor(0xFF6E8BFF.toInt()); letterSpacing = 0.15f
        paint.isFakeBoldText = true
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(24); bottomMargin = dp(8); leftMargin = dp(4)
        }
    }

    private fun section(title: String, subtitle: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(16))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF181B22.toInt())
            cornerRadius = resources.displayMetrics.density * 14
            setStroke(dp(1), 0xFF262A33.toInt())
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        addView(TextView(this@SettingsActivity).apply {
            this.text = title; textSize = 16f; setTextColor(0xFFF2F3F7.toInt()); paint.isFakeBoldText = true
        })
        addView(TextView(this@SettingsActivity).apply {
            this.text = subtitle; textSize = 12f; setTextColor(0xFF5A5F6E.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) }
        })
    }

    private fun fieldLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f; setTextColor(0xFF8A8F9E.toInt())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
    }

    private fun input(
        prefs: SharedPreferences,
        key: String,
        hint: String,
        multiline: Boolean = false,
        secret: Boolean = false,
        numeric: Boolean = false,
    ): EditText = EditText(this).apply {
        setText(prefs.getString(key, ""))
        this.hint = hint
        setHintTextColor(0xFF4A4F5C.toInt())
        setTextColor(0xFFF2F3F7.toInt())
        textSize = 14f
        background.alpha = 30
        setPadding(dp(12), dp(10), dp(12), dp(10))
        if (numeric) {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        } else if (multiline) {
            minLines = 3
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        } else if (secret) {
            // 密码遮罩: 粘进去即显示圆点, 可点眼睛临时明文
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        } else {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { prefs.edit().putString(key, s?.toString().orEmpty()).apply() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    private fun footer(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(14))
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF171A22.toInt())
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), 0xFF292E3A.toInt())
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(8) }

        val version = BuildConfig.VERSION_NAME
        addView(LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@SettingsActivity).apply {
                text = "Q"
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                paint.isFakeBoldText = true
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF6276E9.toInt())
                    cornerRadius = dp(11).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { rightMargin = dp(11) }
            })
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@SettingsActivity).apply {
                    text = "QuotaView"
                    textSize = 15f
                    setTextColor(0xFFF2F3F7.toInt())
                    paint.isFakeBoldText = true
                })
                addView(TextView(this@SettingsActivity).apply {
                    text = "Version $version · 本地直连"
                    textSize = 11.5f
                    setTextColor(0xFF747B8C.toInt())
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(2) }
                })
            })
            addView(TextView(this@SettingsActivity).apply {
                text = "凭证仅存设备"
                textSize = 10.5f
                setTextColor(0xFF8FA3FF.toInt())
                setPadding(dp(9), dp(5), dp(9), dp(5))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF222A40.toInt())
                    cornerRadius = dp(14).toFloat()
                }
            })
        })

        addView(View(this@SettingsActivity).apply {
            setBackgroundColor(0xFF292E3A.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(14); bottomMargin = dp(12)
            }
        })
        addView(TextView(this@SettingsActivity).apply {
            text = "从订阅额度到 API 消费，把每个 Provider 的状态放在同一块仪表盘里。"
            textSize = 12f
            setTextColor(0xFF8A91A1.toInt())
        })
        addView(LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(11), dp(9), dp(11), dp(9))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF20242E.toInt())
                cornerRadius = dp(11).toFloat()
                setStroke(dp(1), 0xFF303746.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
            addView(android.widget.ImageView(this@SettingsActivity).apply {
                setImageResource(R.drawable.ic_github)
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { rightMargin = dp(8) }
            })
            addView(TextView(this@SettingsActivity).apply {
                text = "GitHub · tankecho42/quotaview"
                textSize = 12f
                setTextColor(0xFFC6CFFF.toInt())
                paint.isFakeBoldText = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@SettingsActivity).apply {
                text = "↗"
                textSize = 14f
                setTextColor(0xFF77809A.toInt())
            })
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/tankecho42/quotaview")))
            }
        })
        addView(TextView(this@SettingsActivity).apply {
            text = "© 2026 Tank × TankEcho"
            textSize = 10.5f
            setTextColor(0xFF555B69.toInt())
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
        })
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
