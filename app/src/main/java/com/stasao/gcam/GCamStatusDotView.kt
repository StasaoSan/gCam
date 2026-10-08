package com.stasao.gcam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

class GCamStatusDotView(context: Context) : View(context) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = STATUS_ERROR }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.5f
    }

    fun setStatusColor(color: Int) {
        if (fill.color == color) return
        fill.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val radius = minOf(width, height) * 0.22f
        canvas.drawCircle(width / 2f, height / 2f, radius, fill)
        canvas.drawCircle(width / 2f, height / 2f, radius, border)
    }

    companion object {
        val STATUS_ERROR: Int = Color.parseColor("#F44336")
        val STATUS_READY: Int = Color.parseColor("#FF9800")
        val STATUS_RECORDING: Int = Color.parseColor("#4CAF50")
    }
}
