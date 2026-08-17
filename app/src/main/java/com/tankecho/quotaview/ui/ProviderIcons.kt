package com.tankecho.quotaview.ui

import androidx.annotation.DrawableRes
import com.tankecho.quotaview.R

/** Provider 图标的唯一映射；资源统一来自 Lobe Icons。 */
object ProviderIcons {
    @DrawableRes
    fun icon(id: String): Int = when (id) {
        "codex" -> R.drawable.ic_openai
        "glm" -> R.drawable.ic_zai
        "kimi" -> R.drawable.ic_kimi
        "claude" -> R.drawable.ic_claude
        "qwen" -> R.drawable.ic_qwen
        "minimax" -> R.drawable.ic_minimax
        "deepseek" -> R.drawable.ic_deepseek
        else -> 0
    }
}
