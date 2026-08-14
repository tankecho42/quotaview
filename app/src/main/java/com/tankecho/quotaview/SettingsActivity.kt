package com.tankecho.quotaview

import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tankecho.quotaview.data.CodexApi
import com.tankecho.quotaview.data.GlmApi
import com.tankecho.quotaview.data.ProviderStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private val scope = CoroutineScope(Dispatchers.Main)
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

        root.addView(TextView(this).apply {
            text = "凭证只存本机 SharedPreferences。\nCodex → chatgpt.com · GLM → open.bigmodel.cn\n不经任何第三方服务器。"
            textSize = 12f; setTextColor(0xFF5A5F6E.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) }
        })

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

    // ---------- 通用组件 ----------

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

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
