package com.stasao.gcam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

class GCamStatusDotView(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.2f
    }
    private var left = STATUS_ERROR
    private var right: Int? = null

    fun setStatus(left: Int, right: Int? = null) {
        if (this.left == left && this.right == right) return
        this.left = left
        this.right = right
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val radius = minOf(width, height) * 0.22f
        val centerX = width / 2f
        val centerY = height / 2f
        if (right == null) {
            fill.color = left
            canvas.drawCircle(centerX, centerY, radius, fill)
        } else {
            val circle = Path().apply { addCircle(centerX, centerY, radius, Path.Direction.CW) }
            canvas.save()
            canvas.clipPath(circle)
            fill.color = left
            canvas.drawRect(centerX - radius, centerY - radius, centerX, centerY + radius, fill)
            fill.color = right!!
            canvas.drawRect(centerX, centerY - radius, centerX + radius, centerY + radius, fill)
            canvas.restore()
        }
        canvas.drawCircle(centerX, centerY, radius, border)
    }

    companion object {
        val STATUS_ERROR: Int = Color.parseColor("#F44336")
        val STATUS_IDLE: Int = Color.parseColor("#2196F3")
        val STATUS_READY: Int = Color.parseColor("#FF9800")
        val STATUS_RECORDING: Int = Color.parseColor("#4CAF50")
    }
}
