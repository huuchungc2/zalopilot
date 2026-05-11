package com.zalopilot.app.util

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** Bỏ qua node snapshot lỗi / bounds rỗng gây nhiễu khi scan. */
fun AccessibilityNodeInfo.hasValidScreenBounds(): Boolean {
    val r = Rect()
    getBoundsInScreen(r)
    return r.right > r.left && r.bottom > r.top && r.width() > 0 && r.height() > 0
}
