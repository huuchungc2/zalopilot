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
                // Lọc bỏ nút đã like trước khi addResolved — tránh trường hợp
                // savedId khớp cả nút "Thích" lẫn nút "Đã thích" (cùng resource-id,
                // khác text/contentDescription).
                byId.filter { !isAlreadyLiked(it) }.forEach { addResolved(it) }
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

    /**
     * @return true = label "Thích"; false = không like; null = không đọc được (btn_like_text)
     */
    private fun evaluateBtnLikeText(node: AccessibilityNodeInfo): Boolean? {
        fun scanHost(host: AccessibilityNodeInfo): Boolean? {
            var foundBtnLikeText = false
            var sawThich = false
            for (i in 0 until host.childCount) {
                val c = host.getChild(i) ?: continue
                val id = c.viewIdResourceName ?: ""
                if (!id.contains("btn_like_text")) continue
                foundBtnLikeText = true
                val t = c.text?.toString()?.trim().orEmpty()
                when {
                    t == ZaloIDStore.TEXT_LIKE -> sawThich = true
                    t.isNotEmpty() -> return false
                }
            }
            if (sawThich) return true
            if (foundBtnLikeText) return null
            return null
        }

        scanHost(node)?.let { return it }
        val parent = node.parent ?: return null
        for (i in 0 until parent.childCount) {
            val sib = parent.getChild(i) ?: continue
            if (sib === node) continue
            scanHost(sib)?.let { return it }
        }
        return null
    }

    /**
     * Đoạn text bài viết gần nút like (BFS nông trên vài cấp cha) — dùng ghép [postKey], không dựa isChecked.
     */
    fun getPostSnippetForKey(likeNode: AccessibilityNodeInfo): String {
        var host: AccessibilityNodeInfo? = likeNode.parent
        repeat(5) {
            val h = host ?: return@repeat
            var best = ""
            fun consider(raw: String?) {
                val t = raw?.trim().orEmpty()
                if (t.length < 10) return
                if (t.equals(ZaloIDStore.TEXT_LIKE, ignoreCase = true)) return
                if (t.contains(ZaloIDStore.TEXT_LIKED, ignoreCase = true)) return
                if (t.length > best.length) best = t.take(120)
            }
            val q = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
            q.addLast(h to 0)
            while (q.isNotEmpty()) {
                val (n, d) = q.removeFirst()
                if (d > 3) continue
                consider(n.text?.toString())
                consider(n.contentDescription?.toString())
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let { child -> q.addLast(child to (d + 1)) }
                }
            }
            if (best.isNotEmpty()) {
                return best.replace("\\s+".toRegex(), " ")
            }
            host = h.parent
        }
        return ""
    }

    /** Anh/chị/em cùng cấp với parent của nút like có id chứa [reaction_info] → đã thích. */
    private fun hasReactionInfoSiblingOfParent(likeNode: AccessibilityNodeInfo): Boolean {
        val parent = likeNode.parent ?: return false
        val gp = parent.parent ?: return false
        for (i in 0 until gp.childCount) {
            val sib = gp.getChild(i) ?: continue
            if (sib === parent) continue
            val id = sib.viewIdResourceName ?: ""
            if (id.contains("reaction_info")) return true
        }
        return false
    }

    /**
     * Kiểm tra node đã ở trạng thái "Đã thích" chưa — tập trung tất cả logic tại đây.
     *
     * Zalo có thể biểu thị "đã like" theo nhiều cách:
     *   1. text hoặc contentDescription của node chứa "Đã thích"
     *   2. Child có id "btn_like_text" chứa "Đã thích"
     *   3. isChecked == true (một số phiên bản Zalo)
     *   4. contentDescription chứa "liked" (bản tiếng Anh)
     *
     * Trả về true = đã like → bỏ qua.
     */
    fun isAlreadyLiked(node: AccessibilityNodeInfo): Boolean {
        fun String?.containsLiked(): Boolean {
            if (this == null) return false
            return contains("Đã thích", ignoreCase = true) ||
                contains("đã thích", ignoreCase = true) ||
                contains("liked", ignoreCase = true)
        }

        // 1. Chính node
        if (node.text?.toString().containsLiked()) return true
        if (node.contentDescription?.toString().containsLiked()) return true

        // 2. isChecked (một số phiên bản Zalo dùng checked state)
        if (node.isChecked) return true

        // 3. Duyệt children: tìm btn_like_text hoặc bất kỳ child nào có "Đã thích"
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (child.text?.toString().containsLiked()) return true
            if (child.contentDescription?.toString().containsLiked()) return true
        }

        // 4. Leo lên parent, kiểm tra sibling có id reaction_info
        // (Zalo hiển thị emoji reaction khi mình đã like)
        val parent = node.parent ?: return false
        for (i in 0 until parent.childCount) {
            val sib = parent.getChild(i) ?: continue
            if (sib === node) continue
            val sibId = sib.viewIdResourceName ?: ""
            // reaction_info chỉ xuất hiện khi chính mình đã like
            // (khác với like_count là số lượng like của người khác)
            if (sibId.endsWith("reaction_info") || sibId.endsWith("my_reaction")) return true
        }

        return false
    }

    fun shouldLike(node: AccessibilityNodeInfo): Boolean {
        // Tập trung logic phân biệt đã like ở isAlreadyLiked() — không duplicate ở đây.
        if (isAlreadyLiked(node)) return false

        // Lọc thêm: node là nút "Bình luận", "Chia sẻ", "Nhập" — không phải nút Like
        fun ownTextIsAction(node: AccessibilityNodeInfo): Boolean {
            for (raw in listOf(node.text?.toString(), node.contentDescription?.toString())) {
                val t = raw ?: ""
                if (t.contains("bình luận", ignoreCase = true)) return true
                if (t.contains("nhập", ignoreCase = true)) return true
                if (t.contains("chia sẻ", ignoreCase = true)) return true
            }
            return false
        }
        if (ownTextIsAction(node)) return false

        return true
    }

    fun getAuthorName(likeNode: AccessibilityNodeInfo): String? {
        val authorId = idStore.getAuthorNameID()

        var parent: AccessibilityNodeInfo? = likeNode
        repeat(5) {
            parent = parent?.parent ?: return null

            if (authorId != null) {
                val authorNodes = parent!!.findAccessibilityNodeInfosByViewId(authorId)
                if (authorNodes.isNotEmpty()) {
                    authorTextOrNull(authorNodes.first().text?.toString())?.let { return it }
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
        authorTextOrNull(root.text?.toString())?.let { return it }
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findFirstMeaningfulText(child)
            if (found != null) return found
        }
        return null
    }

    /** Tên tác giả: không trả về nhãn nút hành động (tránh khớp session theo "Thích"). */
    private fun authorTextOrNull(raw: String?): String? {
        val t = raw?.trim().orEmpty()
        if (t.length <= 1) return null
        val skipSubstrings = listOf(
            "thích", "bình luận", "nhập", "chia sẻ", "comment", "like"
        )
        if (skipSubstrings.any { t.contains(it, ignoreCase = true) }) return null
        return t
    }
}
