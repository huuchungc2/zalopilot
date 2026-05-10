package com.zalopilot.app.accessibility

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View

/**
 * Vẽ viền nhẹ quanh bounds (screen) — không nhận touch ([FLAG_NOT_TOUCHABLE] ở window).
 */
internal class NodeHighlightOverlayView(context: Context) : View(context) {

    private val paintTargets = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(160, 0, 180, 120)
    }
    private val paintClick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f
        color = Color.argb(230, 255, 152, 0)
    }

    private val rects = ArrayList<Rect>(8)
    private var clickRect: Rect? = null

    fun setHighlights(allBounds: List<Rect>, clickBounds: Rect?) {
        rects.clear()
        for (r in allBounds) {
            if (!r.isEmpty) rects.add(Rect(r))
        }
        clickRect = clickBounds?.takeIf { !it.isEmpty }?.let { Rect(it) }
        invalidate()
    }

    fun clearHighlights() {
        rects.clear()
        clickRect = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pr = clickRect
        for (r in rects) {
            if (pr != null && sameBounds(r, pr)) continue
            canvas.drawRect(r, paintTargets)
        }
        pr?.let { canvas.drawRect(it, paintClick) }
    }

    private fun sameBounds(a: Rect, b: Rect): Boolean =
        a.left == b.left && a.top == b.top && a.right == b.right && a.bottom == b.bottom
}
