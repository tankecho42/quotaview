package com.tankecho.quotaview.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * 双同心圆环: 外环=额度用量(粗), 内环=时间进度(细, 紧贴外环内侧), 中心=provider icon
 */
class DualRingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    var usedPercent = 0f
    var timeElapsedPercent = 0f
    var ringColor = 0xFF6E8BFF.toInt()
    var timeColor = 0xFF9BA1B0.toInt()
    var iconRes = 0
    var centerText: String? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF262A33.toInt(); style = Paint.Style.STROKE
    }
    private val usedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE6E8EE.toInt(); textAlign = Paint.Align.CENTER
        textSize = resources.displayMetrics.density * 13
        isFakeBoldText = true
    }

    private val oval = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = resources.displayMetrics.density
        val cx = width / 2f
        val cy = height / 2f
        // 防截断: 外沿 = 中心线半径 + 半个 stroke, 必须完整落在 view 内
        val outerStroke = d * 5
        val outerR = minOf(width, height) / 2f - outerStroke / 2 - d * 0.5f
        // 内环紧贴外环内侧: 内环外沿 = 外环内沿
        val innerStroke = d * 2.5f
        val innerR = outerR - outerStroke / 2 - d * 1.5f - innerStroke / 2

        trackPaint.strokeWidth = outerStroke
        usedPaint.strokeWidth = outerStroke
        usedPaint.color = ringColor
        timePaint.strokeWidth = innerStroke
        timePaint.color = timeColor

        // 外环: 额度用量 (顶部顺时针)
        oval.set(cx - outerR, cy - outerR, cx + outerR, cy + outerR)
        canvas.drawArc(oval, -90f, 360f, false, trackPaint)
        canvas.drawArc(oval, -90f, 360f * (usedPercent / 100f).coerceIn(0f, 1f), false, usedPaint)

        // 内环: 时间进度
        oval.set(cx - innerR, cy - innerR, cx + innerR, cy + innerR)
        trackPaint.strokeWidth = innerStroke
        canvas.drawArc(oval, -90f, 360f, false, trackPaint)
        canvas.drawArc(oval, -90f, 360f * (timeElapsedPercent / 100f).coerceIn(0f, 1f), false, timePaint)

        // 中心: provider icon
        if (iconRes != 0) {
            val dr = resources.getDrawable(iconRes, null)
            val s = (innerR - d * 6) * 1.55f
            dr.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
            dr.draw(canvas)
        } else {
            centerText?.let { canvas.drawText(it, cx, cy + textPaint.textSize / 3, textPaint) }
        }
    }

}
