package com.miraero.driveapp

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.sin

// 지금 재생 중인 곡 표시: 막대 4개가 위아래로 움직이는 이퀄라이저
class EqualizerView(context: Context, private val color: Int) : View(context) {

    private val bars = 4
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@EqualizerView.color }
    private val rect = RectF()
    private var phase = 0f

    // 막대마다 속도·시작점을 다르게 해서 자연스럽게
    private val speeds = floatArrayOf(1.0f, 1.45f, 0.8f, 1.2f)
    private val offsets = floatArrayOf(0f, 1.3f, 2.4f, 0.7f)

    private val animator = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
        duration = 1200
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedValue as Float; invalidate() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val gap = width * 0.12f
        val barW = (width - gap * (bars - 1)) / bars
        val r = barW / 2
        for (i in 0 until bars) {
            // 0.25 ~ 1.0 사이로 높이 변화
            val level = 0.25f + 0.75f * ((sin(phase * speeds[i] + offsets[i]) + 1f) / 2f)
            val h = height * level
            val left = i * (barW + gap)
            rect.set(left, height - h, left + barW, height.toFloat())
            canvas.drawRoundRect(rect, r, r, paint)
        }
    }
}
