package com.tankecho.quotaview

import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("qv", MODE_PRIVATE)
        val focus = intent?.getStringExtra("focus")

        supportActionBar?.hide()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
            setBackgroundColor(0xFF101218.toInt())
        }

        // ---------- header ----------
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
            text = "Settings"
            textSize = 22f; setTextColor(0xFFF2F3F7.toInt()); paint.isFakeBoldText = true
        })
        root.addView(header)

        // ---------- 显示开关 ----------
        root.addView(sectionLabel("DISPLAY"))
        displaySwitch(root, prefs, "show_codex", "⚡ Codex", "ChatGPT Plan · wham/usage")
        displaySwitch(root, prefs, "show_glm", "🧩 GLM Coding Plan", "bigmodel · quota/limit")

        // ---------- Codex ----------
        val codexSec = section("⚡ Codex", "ChatGPT OAuth · 直连 chatgpt.com")
        if (focus == "codex") codexSec.setBackgroundColor(0xFF1D2130.toInt())
        codexSec.addView(fieldLabel("access_token"))
        codexSec.addView(input(prefs, "codex_token", "粘贴 ~/.codex/auth.json → tokens.access_token"))
        codexSec.addView(fieldLabel("account_id"))
        codexSec.addView(input(prefs, "codex_account", "auth.json → tokens.account_id"))
        root.addView(codexSec)

        // ---------- GLM ----------
        val glmSec = section("🧩 GLM Coding Plan", "bigmodel API key · 直连 open.bigmodel.cn")
        if (focus == "glm") glmSec.setBackgroundColor(0xFF1D2130.toInt())
        glmSec.addView(fieldLabel("API key"))
        glmSec.addView(input(prefs, "glm_key", "粘贴 bigmodel 的 API key"))
        root.addView(glmSec)

        // ---------- 费用模拟数据 ----------
        val costSec = section("💰 费用模拟", "本地 token 明细 → 等量 API 成本")
        costSec.addView(fieldLabel("明细 JSON"))
        costSec.addView(input(prefs, "cost_breakdown_json", "粘贴采集器 collect_tokens.py 的输出", multiline = true))
        root.addView(costSec)

        // ---------- footer ----------
        root.addView(TextView(this).apply {
            text = "凭证只存本机 SharedPreferences。\nCodex → chatgpt.com · GLM → open.bigmodel.cn\n不经任何第三方服务器。"
            textSize = 12f; setTextColor(0xFF5A5F6E.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) }
        })

        setContentView(ScrollView(this).apply {
            addView(root)
            isScrollbarFadingEnabled = false
        })
    }

    // ---------- 组件 ----------

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
            this.text = title
            textSize = 17f; setTextColor(0xFFF2F3F7.toInt()); paint.isFakeBoldText = true
        })
        addView(TextView(this@SettingsActivity).apply {
            this.text = subtitle
            textSize = 12f; setTextColor(0xFF5A5F6E.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) }
        })
    }

    private fun fieldLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f; setTextColor(0xFF8A8F9E.toInt())
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }
    }

    private fun input(prefs: SharedPreferences, key: String, hint: String, multiline: Boolean = false): EditText = EditText(this).apply {
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
        } else {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { prefs.edit().putString(key, s?.toString().orEmpty()).apply() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    private fun displaySwitch(parent: LinearLayout, prefs: SharedPreferences, key: String, title: String, subtitle: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(13), dp(12), dp(13))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF181B22.toInt())
                cornerRadius = resources.displayMetrics.density * 14
                setStroke(dp(1), 0xFF262A33.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
        }
        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textCol.addView(TextView(this).apply {
            text = title; textSize = 15f; setTextColor(0xFFF2F3F7.toInt()); paint.isFakeBoldText = true
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        textCol.addView(TextView(this).apply {
            text = subtitle; textSize = 12f; setTextColor(0xFF5A5F6E.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        })
        row.addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Switch(this).apply {
            isChecked = prefs.getBoolean(key, true)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean(key, checked).apply() }
        })
        parent.addView(row)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
