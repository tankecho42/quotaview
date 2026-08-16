package com.tankecho.quotaview.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.view.View

/**
 * 折叠态竖条 v2: 三层结构.
 *
 * 1. 半透明黑填充 (贴屏侧直角, 外侧圆角)
 * 2. 1dp 深色半透明外框 (只描 顶+外侧+底 三边, 贴屏侧开放)
 * 3. 用量进度描边: 在外框内部, 沿同样三边路径 — 健康色=已用, 暗色轨道=剩余
 */
class BarView(context: Context) : View(context) {

    var usedPercent = 0f            // 0..100 额度用量
    var healthColor = 0xFF6E8BFF.toInt()
    var attachRight = true          // true=贴右缘 (贴屏侧=右边, 无边框)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x99 shl 24
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT; color = (0x59 shl 24) or 0x9BA1B0
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
        fillPaint.color = 0x66000000
        val fill = android.graphics.drawable.GradientDrawable()
        if (attachRight) {
            fill.setCornerRadii(floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r))
        } else {
            fill.setCornerRadii(floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f))
        }
        fill.setColor(0x66000000)
        fill.setBounds(0, 0, width, height)
        fill.draw(canvas)

        // 2. 1dp 深色外框 (三边)
        borderPaint.strokeWidth = 1 * d
        openPath(tmp, w, h, 0f, borderPaint.strokeWidth, r)
        canvas.drawPath(tmp, borderPaint)

        // 3. 用量进度描边 (外框内部, 三边路径)
        val inset = 2 * d
        val stroke = 1.5f * d
        trackPaint.strokeWidth = stroke
        usedPaint.strokeWidth = stroke
        usedPaint.color = healthColor
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
}
