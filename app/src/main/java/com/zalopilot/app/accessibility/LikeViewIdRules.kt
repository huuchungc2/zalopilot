package com.zalopilot.app.accessibility

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Quy tắc resource-id / class cho vùng like Zalo — tránh nhầm [vpager], RecyclerView, layout feed… thành nút like.
 */
object LikeViewIdRules {

    fun resourceIdTail(id: String?): String =
        id?.substringAfter(":id/", missingDelimiterValue = id ?: "")?.lowercase().orEmpty()

    fun isBlacklistedResourceId(id: String?): Boolean {
        val t = resourceIdTail(id)
        if (t.isEmpty()) return false
        if (t.contains("vpager")) return true
        if (t.contains("layoutsocialfeed")) return true
        if (t.contains("feeditemgrouphorizontal")) return true
        return false
    }

    fun isBlacklistedClassName(className: String?): Boolean {
        val s = className?.substringAfterLast('.')?.lowercase().orEmpty()
        return when (s) {
            "viewpager",
            "viewpager2",
            "recyclerview",
            "framelayout" -> true
            else -> false
        }
    }

    /** Chỉ id like thật (package Zalo); đã lọc blacklist. */
    fun isWhitelistedLikeResourceId(id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        if (!id.contains("zalo", ignoreCase = true)) return false
        if (isBlacklistedResourceId(id)) return false
        val t = resourceIdTail(id)
        if (t.contains("btn_like_text")) return true
        if (t.contains("btn_like_icon")) return true
        if (t.contains("like_component")) return true
        if (t.contains("btn_like")) return true
        return false
    }

    /** Nhỏ hơn = ưu tiên click cao hơn: btn_like_text → btn_like_icon → btn_like → like_component */
    fun likeClickPriority(id: String?): Int {
        val t = resourceIdTail(id)
        return when {
            t.contains("btn_like_text") -> 0
            t.contains("btn_like_icon") -> 1
            t.contains("btn_like") && !t.contains("btn_like_text") && !t.contains("btn_like_icon") -> 2
            t.contains("like_component") -> 3
            else -> 100
        }
    }

    fun shouldRejectNodeForLike(n: AccessibilityNodeInfo): Boolean {
        if (isBlacklistedResourceId(n.viewIdResourceName)) return true
        if (isBlacklistedClassName(n.className?.toString())) return true
        return false
    }
}
