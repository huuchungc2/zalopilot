package com.zalopilot.app.accessibility.engine

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.zalopilot.app.util.hasValidScreenBounds

/** Tọa độ tap — không giữ [AccessibilityNodeInfo] sau khi recycle root. */
data class ScriptTapTarget(
    val bounds: Rect,
    val viewId: String? = null,
    val label: String? = null
) {
    companion object {
        fun fromNode(node: AccessibilityNodeInfo): ScriptTapTarget? {
            if (!node.hasValidScreenBounds()) return null
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.isEmpty) return null
            val label = sequenceOf(node.text, node.contentDescription)
                .mapNotNull { it?.toString()?.trim() }
                .firstOrNull { it.isNotEmpty() }
            return ScriptTapTarget(bounds, node.viewIdResourceName, label)
        }
    }
}
