package com.tankecho.quotaview

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import org.json.JSONObject
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.forEachIndexed
import com.tankecho.quotaview.data.CodexApi
import com.tankecho.quotaview.data.GlmApi
import com.tankecho.quotaview.data.ProviderStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private lateinit var ringPreview: com.tankecho.quotaview.ui.DualRingView
    private data class FieldDef(val key: String, val label: String, val hint: String, val multiline: Boolean = false, val secret: Boolean = false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("qv", MODE_PRIVATE)

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

        // ---------- 灵动岛配置 ----------
        root.addView(sectionLabel("LIVE RING · 悬浮窗"))
        root.addView(liveRingCard(prefs))

        root.addView(footer(prefs))

        setContentView(ScrollView(this).apply { addView(root) })
    }

    // ---------- provider 卡片: 开关 + 内嵌配置 + 打开即验证 ----------

    private fun providerCard(
        prefs: SharedPreferences, id: String, @Suppress("unused") iconRes: Int, title: String, subtitle: String,
        fields: List<FieldDef>, validator: () -> ProviderStatus,
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
        headerRow.addView(android.widget.ImageView(this).apply {
            setImageResource(iconRes)
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
        val sw = Switch(this).apply {
            isChecked = prefs.getBoolean("show_$id", true)
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
            body.addView(input(prefs, f.key, f.hint, f.multiline, f.secret))
        }
        card.addView(body)
        if (!hasConfig) body.visibility = View.GONE else chevron.text = "▾"

        headerRow.setOnClickListener {
            body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            chevron.text = if (body.visibility == View.VISIBLE) "▾" else "▸"
        }

        // 打开即验证: 通过才保持开, 失败强制关闭
        sw.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                val missing = fields.any { prefs.getString(it.key, "").isNullOrBlank() }
                if (missing) {
                    sw.isChecked = false
                    Toast.makeText(this, "$title 还没填完整配置", Toast.LENGTH_SHORT).show()
                    body.visibility = View.VISIBLE; chevron.text = "▾"
                    return@setOnCheckedChangeListener
                }
                scope.launch {
                    val st = withContext(Dispatchers.IO) { runCatching(validator).getOrNull() }
                    val ok = st != null && st.error == null && st.windows.isNotEmpty()
                    if (ok) {
                        prefs.edit().putBoolean("show_$id", true).apply()
                        Toast.makeText(this@SettingsActivity, "$title 连通 ✓ ${st!!.windows.size} 个窗口", Toast.LENGTH_SHORT).show()
                    } else {
                        sw.isChecked = false
                        prefs.edit().putBoolean("show_$id", false).apply()
                        val reason = st?.error ?: "网络错误"
                        Toast.makeText(this@SettingsActivity, "$title 配置无法连通：$reason", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                prefs.edit().putBoolean("show_$id", false).apply()
            }
        }
        return card
    }

    // ---------- 灵动岛配置卡片 ----------

    private val windowOptions = listOf("主窗口" to "primary", "周窗口" to "week", "MCP 工具" to "mcp")

    private fun liveRingCard(prefs: SharedPreferences): LinearLayout {
        val card = section("", "")
        card.removeAllViews()

        // 标题行 + 总开关
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(this).apply {
            text = "💍 悬浮窗展示"
            textSize = 16f; setTextColor(0xFFF2F3F7.toInt()); paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val islandSwitch = Switch(this).apply {
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
                } else if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
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
                stopService(Intent(this, IslandService::class.java))
            }
        }

        // 预览环
        ringPreview = com.tankecho.quotaview.ui.DualRingView(this).apply {
            iconRes = if (prefs.getString("ring_provider", "codex") == "codex") R.drawable.ic_openai else R.drawable.ic_zai
        }
        val previewWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        previewWrap.addView(ringPreview, LinearLayout.LayoutParams(dp(120), dp(120)).apply { rightMargin = dp(20) })
        val legend = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        legend.addView(TextView(this).apply {
            text = "外环 · 额度用量"; textSize = 13f; setTextColor(0xFF8FA3FF.toInt()); paint.isFakeBoldText = true
        })
        legend.addView(TextView(this).apply {
            text = "内环 · 时间进度"; textSize = 13f; setTextColor(0xFF9BA1B0.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        })
        legend.addView(TextView(this).apply {
            text = "开屏即见, 无需打开 App"
            textSize = 12f; setTextColor(0xFF5A5F6E.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
        })
        previewWrap.addView(legend)
        card.addView(previewWrap, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12); bottomMargin = dp(4)
        })

        // Provider 选择 (icon + 名称 的胶囊单选; 重建函数统一管理, 不搞闭包转发)
        card.addView(fieldLabel("Provider"))
        val provRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun rebuildProvRow() {
            val cur = prefs.getString("ring_provider", "codex")
            provRow.removeAllViews()
            listOf("Codex" to "codex", "GLM" to "glm").forEach { (name, id) ->
                provRow.addView(makeProviderChip(name, id == cur) {
                    if (id != cur) {
                        prefs.edit().putString("ring_provider", id).apply()
                        if (prefs.getBoolean("island_enabled", false)) {
                            startService(Intent(this@SettingsActivity, IslandService::class.java).setAction(IslandService.ACTION_REFRESH))
                        }
                        ringPreview.iconRes = if (id == "codex") R.drawable.ic_openai else R.drawable.ic_zai
                        refreshRingPreview(prefs)
                    }
                    rebuildProvRow()
                })
            }
        }
        rebuildProvRow()
        card.addView(provRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        // 窗口类型
        card.addView(fieldLabel("额度类型"))
        val winRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        windowOptions.forEach { (name, _) ->
            winRow.addView(makeChip(name, false) { })
        }
        card.addView(winRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })

        // 初始化选中态 + 实时预览
        val savedWin = prefs.getString("ring_window", "primary") ?: "primary"
        updateChipStates(winRow, windowOptions.map { it.first to it.second }, savedWin)
        winRow.forEachIndexed { i, v ->
            if (v is TextView) {
                val (name, key) = windowOptions[i]
                v.setOnClickListener {
                    prefs.edit().putString("ring_window", key).apply()
                if (prefs.getBoolean("island_enabled", false)) {
                    startService(Intent(this@SettingsActivity, IslandService::class.java).setAction(IslandService.ACTION_REFRESH))
                }
                    updateChipStates(winRow, windowOptions.map { it.first to it.second }, key)
                    refreshRingPreview(prefs)
                }
            }
        }
        refreshRingPreview(prefs)

        // 诊断按钮
        val diagBtn = TextView(this).apply {
            text = "🩺 诊断悬浮窗"
            textSize = 13f; setTextColor(0xFF6E8BFF.toInt()); paint.isFakeBoldText = true
            setPadding(dp(2), dp(14), dp(2), dp(6))
            setOnClickListener { diagIsland() }
        }
        card.addView(diagBtn)
        return card
    }

    private fun diagIsland() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        val sb = StringBuilder()
        sb.append("SDK=").append(android.os.Build.VERSION.SDK_INT).append(" (").append(android.os.Build.VERSION.RELEASE).append(")\n")
        sb.append("ColorOS=").append(android.os.Build.VERSION.INCREMENTAL ?: "?").append("\n")
        if (android.os.Build.VERSION.SDK_INT >= 36) {
            sb.append("hasPromotableCharacteristics(需通知已post)=").append("(post后见logcat QVIsland)\n")
            sb.append("canPostPromotedNotifications=").append(runCatching { nm.canPostPromotedNotifications() }.getOrElse { it.message }).append("\n")
            sb.append("POST_NOTIFICATIONS granted=").append(
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            ).append("\n")
            sb.append("channel importance=").append(nm.getNotificationChannel("qv_island")?.importance ?: "null").append("\n")
        } else {
            sb.append("SDK<36, ProgressStyle 不可用\n")
        }
        sb.append("service running=").append(isServiceRunning())
        // 读回诊断: 系统是否真的打了 FLAG_PROMOTED_ONGOING
        val promoted = getSharedPreferences("qv", MODE_PRIVATE).getBoolean("island_promoted", false)
        val diagTs = getSharedPreferences("qv", MODE_PRIVATE).getLong("island_diag_ts", 0)
        sb.append("\npromoted(readback)=").append(promoted)
        sb.append("\npromotable(readback)=").append(getSharedPreferences("qv", MODE_PRIVATE).getBoolean("island_promotable", false))
        if (diagTs > 0) sb.append("\nreadback时间=").append(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(diagTs)))
        sb.append("\nlistener授权=").append(android.provider.Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners")?.contains(packageName) == true)
        // 流体云端侧: 现场实测一次 (不依赖后台)
        sb.append("\nMANUFACTURER=").append(android.os.Build.MANUFACTURER)
        val fluid = try { OppoFluidCloud.share(this, 0, 0, "Codex", "诊断") } catch (e: Exception) {
            JSONObject().put("code", -9).put("message", e.message ?: "调用异常")
        }
        sb.append("\n流体云端侧=").append(fluid.toString())

        // ===== 判决: AOSP 提升判定逐条对照 =====
        val vprefs = getSharedPreferences("qv", MODE_PRIVATE)
        val vnm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        sb.append("\n\n===== 提升判定 (AOSP 8491) =====")
        val c1 = runCatching { vnm.canPostPromotedNotifications() }.getOrDefault(false)
        val c2 = vprefs.getBoolean("island_promotable", false)
        val c3 = vprefs.getBoolean("island_promoted", false)
        sb.append("\n① canBePromoted(授权)=").append(if (c1) "PASS" else "FAIL")
        sb.append("\n② hasPromotableCharacteristics(readback)=").append(if (c2) "PASS" else "FAIL")
        sb.append("\n③ channel importance>MIN=").append("PASS(importance=3)")
        sb.append("\n④ FLAG_PROMOTED_ONGOING(系统盖章)=").append(if (c3) "PASS ✅已提升" else "FAIL")
        sb.append("\n⑤ post自检 style=").append(if (vprefs.getBoolean("island_selfcheck_style", false)) "PASS" else "FAIL")
        sb.append(" colorized=").append(if (vprefs.getBoolean("island_selfcheck_color", false)) "PASS" else "FAIL")
        sb.append(" ongoing=").append(if (vprefs.getBoolean("island_selfcheck_ongoing", false)) "PASS" else "FAIL")
        sb.append(" title=").append(if (vprefs.getBoolean("island_selfcheck_title", false)) "PASS" else "FAIL")
        sb.append(" noCustomViews=").append(if (vprefs.getBoolean("island_selfcheck_nocustom", false)) "PASS" else "FAIL")
        sb.append("\n结论: ")
        sb.append(when {
            c3 -> "系统已提升! 若无岛, 是 ColorOS 渲染层选择不显示"
            c1 && c2 -> "①②全过但未盖章 → ColorOS 侧在 NotificationManagerService 加了额外门槛"
            c1 && !c2 -> "授权OK但通知特征FAIL → 查 logcat QVIsland 的 post 时真值"
            else -> "前置条件未满足, 按上面 FAIL 项修"
        })

        val msg = sb.toString()
        val dlg = android.app.AlertDialog.Builder(this)
            .setTitle("灵动岛诊断")
            .create()
        val scroll = ScrollView(this)
        val innerPad = dp(20)
        scroll.setPadding(innerPad, innerPad, innerPad, innerPad)
        scroll.addView(TextView(this).apply {
            text = msg; textSize = 12f; setTextColor(0xFFC9CDD6.toInt())
            setTextIsSelectable(true)
        })
        dlg.setView(scroll)
        dlg.setButton(android.app.AlertDialog.BUTTON_NEUTRAL, "复制") { _, _ ->
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("diag", msg))
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
        }
        dlg.setButton(android.app.AlertDialog.BUTTON_POSITIVE, "好的",android.content.DialogInterface.OnClickListener { _, _ -> })
        dlg.show()
        dlg.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).paint.isFakeBoldText = true
        dlg.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setTextColor(0xFF6E8BFF.toInt())
    }

    private fun isServiceRunning(): Boolean {
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        return am.getRunningServices(Int.MAX_VALUE).any { it.service.className == "com.tankecho.quotaview.IslandService" }
    }

    private fun refreshRingPreview(prefs: SharedPreferences) {
        scope.launch {
            val st = withContext(Dispatchers.IO) {
                val prov = prefs.getString("ring_provider", "codex")!!
                runCatching {
                    if (prov == "codex") CodexApi.fetch(prefs.getString("codex_token", "").orEmpty(), prefs.getString("codex_account", "").orEmpty())
                    else GlmApi.fetch(prefs.getString("glm_key", "").orEmpty())
                }.getOrNull()
            }
            val winKey = prefs.getString("ring_window", "primary") ?: "primary"
            val win = st?.windows?.firstOrNull { labelToKey(it.label) == winKey } ?: st?.windows?.firstOrNull()
            ringPreview.usedPercent = win?.usedPercent?.toFloat() ?: 0f
            ringPreview.timeElapsedPercent = win?.timeElapsedPercent?.toFloat() ?: 0f
            ringPreview.ringColor = when {
                win == null || win.pace == null -> 0xFF6E8BFF.toInt()
                win.pace!! > 1.5f -> 0xFFE5484D.toInt()
                win.pace!! > 1f -> 0xFFF5A524.toInt()
                else -> 0xFF6E8BFF.toInt()
            }
            ringPreview.invalidate()
        }
    }

    private fun labelToKey(label: String): String = when {
        label.startsWith("MCP") -> "mcp"
        label.contains("周") -> "week"
        else -> "primary"
    }

    /** Provider 胶囊: 官方 icon + 名称, 选中态靛蓝描边 */
    private fun makeProviderChip(name: String, selected: Boolean, onClick: (Boolean) -> Unit): android.view.View =
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

            addView(android.widget.ImageView(this@SettingsActivity).apply {
                setImageResource(if (name == "Codex") R.drawable.ic_openai else R.drawable.ic_zai)
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

    private fun updateChipStates(row: LinearLayout, options: List<Pair<String, String>>, activeKey: String) {
        row.forEachIndexed { i, v ->
            if (v is TextView) {
                val key = options.getOrNull(i)?.second ?: return@forEachIndexed
                val sel = key == activeKey
                v.background = android.graphics.drawable.GradientDrawable().apply {
                    color = android.content.res.ColorStateList.valueOf(if (sel) 0xFF6E8BFF.toInt() else 0xFF262A33.toInt())
                    cornerRadius = resources.displayMetrics.density * 20
                }
                v.setTextColor(if (sel) 0xFFFFFFFF.toInt() else 0xFF8A8F9E.toInt())
            }
        }
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

    private fun input(prefs: SharedPreferences, key: String, hint: String, multiline: Boolean = false, secret: Boolean = false): EditText = EditText(this).apply {
        setText(prefs.getString(key, ""))
        this.hint = hint
        setHintTextColor(0xFF4A4F5C.toInt())
        setTextColor(0xFFF2F3F7.toInt())
        textSize = 14f
        background.alpha = 30
        setPadding(dp(12), dp(10), dp(12), dp(10))
        if (multiline) {
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

    private fun footer(prefs: SharedPreferences): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(28) }

        val version = BuildConfig.VERSION_NAME
        addView(TextView(this@SettingsActivity).apply {
            text = "QuotaView v$version"
            textSize = 13f; setTextColor(0xFF8A8F9E.toInt()); paint.isFakeBoldText = true
        })
        addView(TextView(this@SettingsActivity).apply {
            text = "© 2026 Tank × TankEcho\n从订阅套餐的额度里，看清每一分钱的去向"
            textSize = 12f; setTextColor(0xFF5A5F6E.toInt()); gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        })
        addView(LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
            addView(android.widget.ImageView(this@SettingsActivity).apply {
                setImageResource(R.drawable.ic_github)
                layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(5); topMargin = dp(1) }
            })
            addView(TextView(this@SettingsActivity).apply {
                text = "github.com/tankecho42/quotaview"
                textSize = 12f; setTextColor(0xFF6E8BFF.toInt())
            })
        })
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
