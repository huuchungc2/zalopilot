package com.zalopilot.app.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.zalopilot.app.data.model.ZaloIDStore
import com.zalopilot.app.util.LogTag
import com.zalopilot.app.util.Logger
import com.zalopilot.app.util.UiNodeEntry
import java.util.ArrayDeque
import java.util.LinkedHashSet
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NodeFinder @Inject constructor(
    private val idStore: ZaloIDStore,
    private val logger: Logger
) {
    /**
     * Tìm tất cả nút like chưa được like.
     * Ưu tiên ID đã học, sau đó text hệ thống, rồi duyệt cây (text + contentDescription).
     */
    fun findLikeButtons(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val seen = LinkedHashSet<String>()
        val result = mutableListOf<AccessibilityNodeInfo>()

        fun dedupeKey(node: AccessibilityNodeInfo): String {
            val r = Rect()
            node.getBoundsInScreen(r)
            return "${r.centerX()}_${r.centerY()}_${node.viewIdResourceName ?: ""}"
        }

        fun addResolved(raw: AccessibilityNodeInfo?) {
            val node = resolveClickable(raw) ?: return
            val key = dedupeKey(node)
            if (key !in seen) {
                seen.add(key)
                result.add(node)
            }
        }

        val savedId = idStore.getLikeButtonID()
        if (savedId != null) {
            val byId = root.findAccessibilityNodeInfosByViewId(savedId)
            if (byId.isNotEmpty()) {
                byId.forEach { addResolved(it) }
                if (result.isNotEmpty()) {
                    logFoundSummary(result)
                    return result
                }
            }
            logger.log(LogTag.SCAN, "savedId=$savedId", "ID_MISS_FALLBACK")
        }

        root.findAccessibilityNodeInfosByText(ZaloIDStore.TEXT_LIKE).forEach { addResolved(it) }

        collectLikeHintsByTraversal(root, maxNodes = 2800).forEach { addResolved(it) }

        if (result.isNotEmpty()) {
            logFoundSummary(result)
        } else {
            logger.log(LogTag.SCAN, "findLikeButtons", "EMPTY")
        }

        return result
    }

    private fun logFoundSummary(nodes: List<AccessibilityNodeInfo>) {
        val first = nodes.first()
        val r = Rect()
        first.getBoundsInScreen(r)
        logger.log(
            LogTag.FOUND,
            "count=${nodes.size} firstBounds=[${r.left},${r.top},${r.right},${r.bottom}] clickable=${first.isClickable}",
            "NODES_FOUND"
        )
    }

    /**
     * Duyệt cây: khớp [contentDescription] hoặc [text] chứa "Thích", loại "Đã thích".
     */
    private fun collectLikeHintsByTraversal(root: AccessibilityNodeInfo, maxNodes: Int): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < maxNodes) {
            val node = stack.removeLast()
            visited++
            if (nodeMatchesLikeHint(node)) {
                out.add(node)
            }
            val n = node.childCount
            if (n > 0) {
                for (i in 0 until n) {
                    node.getChild(i)?.let { stack.addLast(it) }
                }
            }
        }
        if (visited >= maxNodes) {
            logger.log(LogTag.SCAN, "traversal cap=$maxNodes visited=$visited", "TRUNCATED")
        }
        return out
    }

    private fun nodeMatchesLikeHint(node: AccessibilityNodeInfo): Boolean {
        val t = node.text?.toString() ?: ""
        val cd = node.contentDescription?.toString() ?: ""
        val hay = "$t $cd"
        if (hay.contains("Đã thích", ignoreCase = true)) return false
        if (hay.contains("đã thích", ignoreCase = true)) return false
        return hay.contains(ZaloIDStore.TEXT_LIKE, ignoreCase = true)
    }

    /**
     * Từ 1 node (có thể là TextView text "Thích"),
     * leo lên tối đa 4 cấp để tìm node clickable thật sự.
     */
    private fun resolveClickable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isClickable) return node
        var parent: AccessibilityNodeInfo? = node.parent
        repeat(4) {
            val p = parent ?: return null
            if (p.isClickable) return p
            parent = p.parent
        }
        return node
    }

    fun shouldLike(node: AccessibilityNodeInfo): Boolean {
        if (node.isChecked) return false
        if (!node.isEnabled) return false

        val ownText = node.text?.toString() ?: ""
        val ownDesc = node.contentDescription?.toString() ?: ""
        val childText = (0 until node.childCount)
            .mapNotNull { node.getChild(it)?.text?.toString() }
            .joinToString("")
        val childDesc = (0 until node.childCount)
            .mapNotNull { node.getChild(it)?.contentDescription?.toString() }
            .joinToString("")

        val text = (ownText + ownDesc + childText + childDesc).trim()
        if (text.isEmpty()) return true

        if (text.contains("Đã thích", ignoreCase = true)) return false
        if (text.contains("Đã", ignoreCase = true)) return false
        return text.contains("Thích", ignoreCase = true)
    }

    fun getAuthorName(likeNode: AccessibilityNodeInfo): String? {
        val authorId = idStore.getAuthorNameID()

        var parent: AccessibilityNodeInfo? = likeNode
        repeat(5) {
            parent = parent?.parent ?: return null

            if (authorId != null) {
                val authorNodes = parent!!.findAccessibilityNodeInfosByViewId(authorId)
                if (authorNodes.isNotEmpty()) {
                    return authorNodes.first().text?.toString()
                }
            }
        }

        parent = likeNode
        repeat(4) { parent = parent?.parent }
        return findFirstMeaningfulText(parent)
    }

    fun findTimelineTab(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val savedId = idStore.getTabTimelineID()
        if (savedId != null) {
            val byId = root.findAccessibilityNodeInfosByViewId(savedId)
            if (byId.isNotEmpty()) return byId.first()
        }
        root.findAccessibilityNodeInfosByText(ZaloIDStore.TEXT_TIMELINE).firstOrNull()?.let { return it }
        return findFirstNodeWithTextOrDesc(root, ZaloIDStore.TEXT_TIMELINE, maxNodes = 2000)
    }

    private fun findFirstNodeWithTextOrDesc(
        root: AccessibilityNodeInfo,
        needle: String,
        maxNodes: Int
    ): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < maxNodes) {
            val node = stack.removeLast()
            visited++
            val t = node.text?.toString() ?: ""
            val d = node.contentDescription?.toString() ?: ""
            if (t.contains(needle, ignoreCase = true) || d.contains(needle, ignoreCase = true)) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return null
    }

    fun debugDump(root: AccessibilityNodeInfo?, maxNodes: Int = 800) {
        root ?: run {
            logger.log(LogTag.SCAN, "debugDump", "ROOT_NULL")
            return
        }

        var visited = 0
        logger.log(LogTag.SCAN, "debugDump maxNodes=$maxNodes", "BEGIN")

        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(root to 0)

        while (stack.isNotEmpty() && visited < maxNodes) {
            val (node, depth) = stack.removeLast()
            visited++

            val indent = buildString { repeat(depth.coerceAtMost(30)) { append("  ") } }
            val text = node.text?.toString()?.replace("\n", "\\n") ?: ""
            val desc = node.contentDescription?.toString()?.replace("\n", "\\n") ?: ""
            val resId = node.viewIdResourceName ?: ""
            val cls = node.className?.toString() ?: ""
            val r = Rect()
            node.getBoundsInScreen(r)
            val bounds = "[${r.left},${r.top},${r.right},${r.bottom}]"

            val line = "$indent- text=\"$text\" desc=\"$desc\" id=\"$resId\" class=\"$cls\" bounds=$bounds clickable=${node.isClickable}"
            logger.log(LogTag.SCAN, line, "NODE")

            val childCount = node.childCount
            if (childCount > 0 && depth < 60) {
                for (i in childCount - 1 downTo 0) {
                    val child = node.getChild(i) ?: continue
                    stack.addLast(child to (depth + 1))
                }
            }
        }

        val endResult = if (visited >= maxNodes) "TRUNCATED" else "DONE"
        logger.log(LogTag.SCAN, "visited=$visited", endResult)
    }

    fun dumpToFile(root: AccessibilityNodeInfo?, maxNodes: Int = 1000) {
        root ?: run {
            logger.log(LogTag.SCAN, "dumpToFile", "ROOT_NULL")
            return
        }

        val result = mutableListOf<UiNodeEntry>()
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(root to 0)

        while (stack.isNotEmpty() && result.size < maxNodes) {
            val (node, depth) = stack.removeLast()
            result.add(
                UiNodeEntry(
                    depth = depth,
                    text = node.text?.toString()?.replace("\n", "↵") ?: "",
                    resourceId = node.viewIdResourceName ?: "",
                    className = node.className?.toString()?.substringAfterLast(".") ?: "",
                    clickable = node.isClickable,
                    checked = node.isChecked
                )
            )
            if (node.childCount > 0 && depth < 60) {
                for (i in node.childCount - 1 downTo 0) {
                    val child = node.getChild(i) ?: continue
                    stack.addLast(child to (depth + 1))
                }
            }
        }

        logger.saveUiTree(result)
    }

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
