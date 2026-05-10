package com.zalopilot.app.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import com.zalopilot.app.data.model.ZaloIDStore
import com.zalopilot.app.util.Logger
import com.zalopilot.app.util.UiNodeEntry
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

        // 1. Thử dùng ID đã học
        val savedId = idStore.getLikeButtonID()
        if (savedId != null) {
            val byId = root.findAccessibilityNodeInfosByViewId(savedId)
            if (byId.isNotEmpty()) {
                // ID tìm được → resolve về node clickable thật
                byId.mapNotNull { resolveClickable(it) }.forEach { result.add(it) }
                if (result.isNotEmpty()) return result
            }
            logger.log("FINDER", "savedId=$savedId not found, fallback text", "ID_MISS")
        }

        // 2. Fallback: tìm text "Thích" → resolve về node clickable thật
        val byText = root.findAccessibilityNodeInfosByText(ZaloIDStore.TEXT_LIKE)
        byText.mapNotNull { resolveClickable(it) }.forEach { result.add(it) }

        // 3. Fallback nữa: tìm "Đã thích" để biết đã like rồi (không add vào result)
        if (result.isEmpty()) {
            logger.log("FINDER", "no like button found in UI", "EMPTY")
        }

        return result
    }

    /**
     * Từ 1 node (có thể là TextView text "Thích"),
     * leo lên tối đa 4 cấp để tìm node clickable thật sự.
     * Zalo thường wrap TextView trong FrameLayout/LinearLayout clickable.
     */
    private fun resolveClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable) return node
        var parent: AccessibilityNodeInfo? = node.parent
        repeat(4) {
            if (parent == null) return null
            if (parent!!.isClickable) return parent
            parent = parent!!.parent
        }
        // Không tìm được node clickable → dùng chính node đó và thử performClick
        return node
    }

    /**
     * Check node này có nên like không.
     * - Chưa được check/like
     * - Text là "Thích" chứ không phải "Đã thích"
     */
    fun shouldLike(node: AccessibilityNodeInfo): Boolean {
        if (node.isChecked) return false

        // Check text của chính node và các con
        val ownText = node.text?.toString() ?: ""
        val childText = (0 until node.childCount)
            .mapNotNull { node.getChild(it)?.text?.toString() }
            .joinToString("")

        val text = (ownText + childText).trim()
        if (text.isEmpty()) return true // ImageButton không có text → cứ thử like

        if (text.contains("Đã thích", ignoreCase = true)) return false
        if (text.contains("Đã", ignoreCase = true)) return false
        return text.contains("Thích", ignoreCase = true)
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

    /**
     * Dump toàn bộ UI tree → lưu vào uitree.json qua logger.
     * Tab UI Tree đọc file này để hiển thị.
     */
    fun dumpToFile(root: AccessibilityNodeInfo?, maxNodes: Int = 1000) {
        root ?: run {
            logger.log("UI_TREE", "root=null", "EMPTY")
            return
        }

        val result = mutableListOf<UiNodeEntry>()
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(root to 0)

        while (stack.isNotEmpty() && result.size < maxNodes) {
            val (node, depth) = stack.removeLast()
            result.add(UiNodeEntry(
                depth      = depth,
                text       = node.text?.toString()?.replace("\n", "↵") ?: "",
                resourceId = node.viewIdResourceName ?: "",
                className  = node.className?.toString()?.substringAfterLast(".") ?: "",
                clickable  = node.isClickable,
                checked    = node.isChecked
            ))
            if (node.childCount > 0 && depth < 60) {
                for (i in node.childCount - 1 downTo 0) {
                    val child = node.getChild(i) ?: continue
                    stack.addLast(child to (depth + 1))
                }
            }
        }

        logger.saveUiTree(result)
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
