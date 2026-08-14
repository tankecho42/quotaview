package com.tankecho.quotaview.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.roundToInt

/**
 * 双进度条竞赛: 大 bar = 额度消耗, 小 bar = 时间流逝, 同刻度上下并排。
 * PACE 指标由外部计算显示, 这里只画条。
 */
class RaceBars @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var usedPercent: Int = 0
        set(v) { field = v.coerceIn(0, 100); animateTo(); }
    var timePercent: Int = 0
        set(v) { field = v.coerceIn(0, 100); animateTo(); }

    /** 用于颜色: 额度条是否处于超速状态 */
    var overheated: Boolean = false
        set(v) { field = v; invalidate(); }

    private val barHeight = resources.displayMetrics.density * 14
    private val smallBarHeight = resources.displayMetrics.density * 6
    private val gap = resources.displayMetrics.density * 4
    private val cornerRadius = barHeight / 2

    private var displayUsed = 0f
    private var displayTime = 0f
    private var animator: ValueAnimator? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF262A33.toInt() }
    private val usedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF9BA1B0.toInt() }
    private val clipRect = RectF()

    private fun animateTo() {
        animator?.cancel()
        val startUsed = displayUsed
        val startTime = displayTime
        val targetUsed = usedPercent.toFloat()
        val targetTime = timePercent.toFloat()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450
            addUpdateListener {
                val f = it.animatedFraction
                displayUsed = startUsed + (targetUsed - startUsed) * f
                displayTime = startTime + (targetTime - startTime) * f
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // track
        clipRect.set(0f, 0f, w, barHeight)
        canvas.drawRoundRect(clipRect, cornerRadius, cornerRadius, trackPaint)
        // used fill
        val usedW = w * displayUsed / 100f
        if (usedW > 0f) {
            clipRect.set(0f, 0f, usedW.coerceAtLeast(cornerRadius), barHeight)
            usedPaint.color = when {
                overheated && usedPercent >= 50 -> 0xFFE5484D.toInt()   // 红
                overheated -> 0xFFF5A524.toInt()                        // 橙
                else -> 0xFF6E8BFF.toInt()                              // 靛蓝(单一强调色)
            }
            canvas.drawRoundRect(clipRect, cornerRadius, cornerRadius, usedPaint)
        }

        // time bar
        val ty = barHeight + gap
        clipRect.set(0f, ty, w, ty + smallBarHeight)
        canvas.drawRoundRect(clipRect, smallBarHeight / 2, smallBarHeight / 2, trackPaint)
        val timeW = w * displayTime / 100f
        if (timeW > 0f) {
            clipRect.set(0f, ty, timeW.coerceAtLeast(smallBarHeight), ty + smallBarHeight)
            canvas.drawRoundRect(clipRect, smallBarHeight / 2, smallBarHeight / 2, timePaint)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val totalH = (barHeight + gap + smallBarHeight).roundToInt()
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(totalH, heightMeasureSpec)
        )
    }
}
