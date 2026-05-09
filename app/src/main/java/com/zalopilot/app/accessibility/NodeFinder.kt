package com.zalopilot.app.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import com.zalopilot.app.data.model.ZaloIDStore
import com.zalopilot.app.util.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NodeFinder @Inject constructor(
    private val idStore: ZaloIDStore,
    private val logger: Logger
) {
    /**
     * Tìm tất cả nút like chưa được like.
     * Ưu tiên dùng ID đã học, fallback về text nếu ID fail.
     */
    fun findLikeButtons(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()

        // Thử dùng ID đã học trước
        val savedId = idStore.getLikeButtonID()
        if (savedId != null) {
            val byId = root.findAccessibilityNodeInfosByViewId(savedId)
            if (byId.isNotEmpty()) {
                result.addAll(byId)
                return result
            }
            // ID không còn tìm được → ZaloUIScanner sẽ tự học ID mới ở scan tiếp theo
        }

        // Fallback: tìm bằng text "Thích"
        val byText = root.findAccessibilityNodeInfosByText(ZaloIDStore.TEXT_LIKE)
        result.addAll(byText)
        return result
    }

    /**
     * Check node này có nên like không.
     * - Chưa được check/like
     * - Text là "Thích" chứ không phải "Đã thích"
     */
    fun shouldLike(node: AccessibilityNodeInfo): Boolean {
        if (node.isChecked) return false
        val text = node.text?.toString() ?: return false
        if (text == ZaloIDStore.TEXT_LIKED) return false
        if (text.contains("Đã")) return false
        return text == ZaloIDStore.TEXT_LIKE || text.contains("Thích")
    }

    /**
     * Lấy tên tác giả của bài đăng từ node like button.
     * Leo lên parent → tìm node text đầu tiên.
     * Dùng để track "1 like/người/lần lướt".
     */
    fun getAuthorName(likeNode: AccessibilityNodeInfo): String? {
        // Thử dùng ID đã học
        val authorId = idStore.getAuthorNameID()

        // Leo lên 3-5 cấp để tìm feed item container
        var parent: AccessibilityNodeInfo? = likeNode
        repeat(5) {
            parent = parent?.parent ?: return null

            // Nếu có author ID đã học, tìm trong subtree này
            if (authorId != null) {
                val authorNodes = parent!!.findAccessibilityNodeInfosByViewId(authorId)
                if (authorNodes.isNotEmpty()) {
                    return authorNodes.first().text?.toString()
                }
            }
        }

        // Fallback: leo lên 4 cấp, lấy text node đầu tiên có nội dung
        parent = likeNode
        repeat(4) { parent = parent?.parent }
        return findFirstMeaningfulText(parent)
    }

    /**
     * Tìm tab Nhật ký.
     */
    fun findTimelineTab(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val savedId = idStore.getTabTimelineID()
        if (savedId != null) {
            val byId = root.findAccessibilityNodeInfosByViewId(savedId)
            if (byId.isNotEmpty()) return byId.first()
        }
        // Fallback text
        return root.findAccessibilityNodeInfosByText(ZaloIDStore.TEXT_TIMELINE).firstOrNull()
    }

    /**
     * Duyệt toàn bộ node tree từ root và ghi ra log để debug UI Zalo.
     * In ra text/resourceId/className của từng node.
     */
    fun debugDump(root: AccessibilityNodeInfo?, maxNodes: Int = 800) {
        root ?: run {
            logger.log("DEBUG_DUMP", "root=null", "EMPTY")
            return
        }

        var visited = 0
        logger.log("DEBUG_DUMP", "start maxNodes=$maxNodes", "BEGIN")

        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(root to 0)

        while (stack.isNotEmpty() && visited < maxNodes) {
            val (node, depth) = stack.removeLast()
            visited++

            val indent = buildString { repeat(depth.coerceAtMost(30)) { append("  ") } }
            val text = node.text?.toString()?.replace("\n", "\\n") ?: ""
            val resId = node.viewIdResourceName ?: ""
            val cls = node.className?.toString() ?: ""

            val line = "$indent- text=\"$text\" id=\"$resId\" class=\"$cls\""
            logger.log("DEBUG_DUMP", line, "NODE")

            val childCount = node.childCount
            if (childCount > 0 && depth < 60) {
                for (i in childCount - 1 downTo 0) {
                    val child = node.getChild(i) ?: continue
                    stack.addLast(child to (depth + 1))
                }
            }
        }

        val endResult = if (visited >= maxNodes) "TRUNCATED" else "DONE"
        logger.log("DEBUG_DUMP", "visited=$visited", endResult)
    }

    // ─── Helpers ──────────────────────────────────────────────────

    private fun findFirstMeaningfulText(root: AccessibilityNodeInfo?): String? {
        root ?: return null
        val text = root.text?.toString()
        if (!text.isNullOrBlank() && text.length > 1 && !text.contains("Thích") && !text.contains("Bình luận")) {
            return text
        }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findFirstMeaningfulText(child)
            if (found != null) return found
        }
        return null
    }
}
