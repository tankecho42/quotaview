package com.tankecho.quotaview.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.drawable.GradientDrawable
import android.view.View

/**
 * 折叠态竖条 v3: 两层结构.
 *
 * 1. 半透明黑填充 (贴屏侧直角, 外侧圆角)
 * 2. 用量进度描边: 沿顶+外侧+底三边 — 健康色=已用, 暗色轨道=剩余
 */
class BarView(context: Context) : View(context) {

    var usedPercent = 0f            // 0..100 额度用量
    var healthColor = 0xFF6E8BFF.toInt()
    var attachRight = true          // true=贴右缘 (贴屏侧=右边, 无边框)

    private val fillDrawable = GradientDrawable().apply { setColor(0x66000000) }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT; color = (0x70 shl 24) or 0xB4B9C6
    }
    private val usedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }

    private val tmp = Path()
    private val seg = Path()
    private val pm = PathMeasure()

    /** 三边开放路径 (顶+外侧+底, 贴屏侧留口). inset: 各边内缩 */
    private fun openPath(out: Path, w: Float, h: Float, inset: Float, strokeW: Float, radius: Float) {
        val i = inset + strokeW / 2f
        val r = (radius - inset).coerceAtLeast(0f)
        out.reset()
        if (attachRight) {
            // 贴右缘: 路径 top-right → top-left(圆角) → bottom-left(圆角) → bottom-right
            out.moveTo(w - i, i)
            out.lineTo(i + r, i)
            if (r > 0) out.quadTo(i, i, i, i + r)
            out.lineTo(i, h - i - r)
            if (r > 0) out.quadTo(i, h - i, i + r, h - i)
            out.lineTo(w - i, h - i)
        } else {
            // 贴左缘: 镜像
            out.moveTo(i, i)
            out.lineTo(w - i - r, i)
            if (r > 0) out.quadTo(w - i, i, w - i, i + r)
            out.lineTo(w - i, h - i - r)
            if (r > 0) out.quadTo(w - i, h - i, w - i - r, h - i)
            out.lineTo(i, h - i)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        val r = w / 2f

        // 1. 半透明黑填充 (单侧圆角胶囊)
        if (attachRight) {
            fillDrawable.setCornerRadii(floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r))
        } else {
            fillDrawable.setCornerRadii(floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f))
        }
        fillDrawable.setBounds(0, 0, width, height)
        fillDrawable.draw(canvas)

        // 2. 用量进度描边；不再额外绘制最外围黑色边框。
        val inset = 1 * d
        val stroke = 2.1f * d
        trackPaint.strokeWidth = stroke
        usedPaint.strokeWidth = stroke
        usedPaint.color = brighten(healthColor, 0.34f)
        openPath(tmp, w, h, inset, stroke, r)
        pm.setPath(tmp, false)
        val len = pm.length
        // 轨道 (剩余额度 = 全路径暗色)
        canvas.drawPath(tmp, trackPaint)
        // 已用段 (健康色, 沿路径从起点推进)
        val frac = (usedPercent / 100f).coerceIn(0f, 1f)
        if (frac > 0.005f && len > 0) {
            seg.reset()
            pm.getSegment(0f, len * frac, seg, true)
            usedPaint.alpha = 255
            canvas.drawPath(seg, usedPaint)
        }
    }

    /** 窄边栏在深色壁纸上容易吃色；向白色提亮，同时保留健康度色相。 */
    private fun brighten(color: Int, amount: Float): Int {
        val a = amount.coerceIn(0f, 1f)
        fun channel(value: Int): Int = (value + (255 - value) * a).toInt().coerceIn(0, 255)
        return Color.rgb(channel(Color.red(color)), channel(Color.green(color)), channel(Color.blue(color)))
    }
}
