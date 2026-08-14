package com.tankecho.quotaview

import android.content.SharedPreferences
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("qv", MODE_PRIVATE)
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }

        supportActionBar?.hide()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(24))
            setBackgroundColor(0xFF101218.toInt())
        }

        fun label(text: String): TextView = TextView(this).apply {
            this.text = text; textSize = 13f; setTextColor(0xFF8A8F9E.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) }
        }
        fun input(key: String, hint: String, multiline: Boolean = false): EditText = EditText(this).apply {
            setText(prefs.getString(key, ""))
            this.hint = hint
            setHintTextColor(0xFF5A5F6E.toInt())
            setTextColor(0xFFF2F3F7.toInt())
            textSize = 14f
            background.alpha = 40
            if (multiline) minLines = 2
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { prefs.edit().putString(key, s?.toString().orEmpty()).apply() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }

        root.addView(TextView(this).apply { text = "⚙️ 凭证"; textSize = 20f; setTextColor(0xFFF2F3F7.toInt()); paint.isFakeBoldText = true })

        root.addView(label("Codex access_token (ChatGPT OAuth)"))
        root.addView(input("codex_token", "粘贴 ~/.codex/auth.json 的 access_token", multiline = true))
        root.addView(label("Codex account_id (chatgpt-account-id)"))
        root.addView(input("codex_account", "粘贴 auth.json 的 account_id"))
        root.addView(label("GLM API key"))
        root.addView(input("glm_key", "粘贴 bigmodel 的 API key"))

        root.addView(label("Token 明细 JSON（费用模拟数据源，可选）"))
        root.addView(input("cost_breakdown_json", "粘贴采集器输出的 breakdown JSON", multiline = true))

        root.addView(TextView(this).apply {
            text = "凭证只存在本机 SharedPreferences，不经过任何第三方服务器。\nCodex 请求直连 chatgpt.com，GLM 直连 open.bigmodel.cn。"
            textSize = 12f; setTextColor(0xFF5A5F6E.toInt()); gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) }
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }
}
