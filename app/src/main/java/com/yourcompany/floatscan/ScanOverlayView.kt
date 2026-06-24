package com.yourcompany.floatscan

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat

/**
 * 扫码取景框叠加层：绿色四角 + 动态扫描线。
 */
class ScanOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.scan_corner)
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.SQUARE
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.scan_line)
        style = Paint.Style.FILL
    }

    private val dimPaint = Paint().apply {
        color = 0x66000000
        style = Paint.Style.FILL
    }

    private val frameRect = RectF()
    private var scanLineY = 0f
    private var lineAnimator: android.animation.ValueAnimator? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val size = (w.coerceAtMost(h) * 0.65f)
        val left = (w - size) / 2f
        val top = (h - size) / 2f
        frameRect.set(left, top, left + size, top + size)
        startLineAnimation()
    }

    private fun startLineAnimation() {
        lineAnimator?.cancel()
        lineAnimator = android.animation.ValueAnimator.ofFloat(frameRect.top, frameRect.bottom).apply {
            duration = 2000
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener {
                scanLineY = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 暗色遮罩（镂空取景框）
        canvas.drawRect(0f, 0f, width.toFloat(), frameRect.top, dimPaint)
        canvas.drawRect(0f, frameRect.top, frameRect.left, frameRect.bottom, dimPaint)
        canvas.drawRect(frameRect.right, frameRect.top, width.toFloat(), frameRect.bottom, dimPaint)
        canvas.drawRect(0f, frameRect.bottom, width.toFloat(), height.toFloat(), dimPaint)

        val cornerLen = frameRect.width() * 0.12f
        drawCorner(canvas, frameRect.left, frameRect.top, cornerLen, true, true)
        drawCorner(canvas, frameRect.right, frameRect.top, cornerLen, false, true)
        drawCorner(canvas, frameRect.left, frameRect.bottom, cornerLen, true, false)
        drawCorner(canvas, frameRect.right, frameRect.bottom, cornerLen, false, false)

        canvas.drawRect(
            frameRect.left + 16f,
            scanLineY - 2f,
            frameRect.right - 16f,
            scanLineY + 2f,
            linePaint
        )
    }

    private fun drawCorner(
        canvas: Canvas,
        x: Float,
        y: Float,
        len: Float,
        left: Boolean,
        top: Boolean
    ) {
        val dx = if (left) len else -len
        val dy = if (top) len else -len
        canvas.drawLine(x, y, x + dx, y, cornerPaint)
        canvas.drawLine(x, y, x, y + dy, cornerPaint)
    }

    override fun onDetachedFromWindow() {
        lineAnimator?.cancel()
        super.onDetachedFromWindow()
    }
}
