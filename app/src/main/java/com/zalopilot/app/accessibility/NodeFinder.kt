package com.zalopilot.app.accessibility

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.zalopilot.app.data.model.ZaloIDStore
import com.zalopilot.app.util.LogTag
import com.zalopilot.app.util.Logger
import com.zalopilot.app.util.UiNodeEntry
import com.zalopilot.app.util.hasValidScreenBounds
import java.util.ArrayDeque
import java.util.LinkedHashSet
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

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
            val leaf = resolveLikeLeafNode(raw) ?: return
            if (!leaf.hasValidScreenBounds()) return
            if (LikeViewIdRules.shouldRejectNodeForLike(leaf)) return
            // Important: trạng thái like phải kiểm tra trên leaf (btn_like_text/icon/btn_like...), không dùng container ancestor.
            if (isAlreadyLiked(leaf)) return

            val clickTarget = resolveLikeClickTargetFromLeaf(leaf)
            if (!clickTarget.hasValidScreenBounds()) return
            if (LikeViewIdRules.shouldRejectNodeForLike(clickTarget)) return

            val key = dedupeKey(clickTarget)
            if (key !in seen) {
                seen.add(key)
                result.add(clickTarget)
            }
        }

        var savedId = idStore.getLikeButtonID()
        if (savedId != null && LikeViewIdRules.isBlacklistedResourceId(savedId)) {
            logger.log(LogTag.SCAN, "savedId=$savedId", "LIKE_ID_BLACKLIST_CLEARED")
            idStore.clearLikeButtonID()
            savedId = null
        }
        if (savedId != null) {
            val byId = root.findAccessibilityNodeInfosByViewId(savedId)
            if (byId.isNotEmpty()) {
                byId.filter { !LikeViewIdRules.shouldRejectNodeForLike(it) && !isAlreadyLiked(it) }
                    .forEach { addResolved(it) }
                if (result.isNotEmpty()) {
                    logFoundSummary(result)
                    return result
                }
            }
            logger.log(LogTag.SCAN, "savedId=$savedId", "ID_MISS_FALLBACK")
        }

        // findAccessibilityNodeInfosByText("Thích") có thể khớp cả "Đã thích" (substring).
        val byText = root.findAccessibilityNodeInfosByText(ZaloIDStore.TEXT_LIKE)
        for (raw in byText) {
            addResolved(raw)
        }

        collectLikeHintsByTraversal(root, maxNodes = 2800).forEach { raw ->
            addResolved(raw)
        }

        if (result.isNotEmpty()) {
            logFoundSummary(result)
        } else {
            logger.log(LogTag.SCAN, "findLikeButtons", "EMPTY")
        }

        return result
    }

    /**
     * Có ít nhất một target like (id whitelist) trên màn hình mà **tài khoản hiện tại** đã like
     * ([isAlreadyLiked]). Dùng khi [findLikeButtons] rỗng nhưng feed vẫn toàn bài mình đã thích —
     * cần cuộn tiếp như ALL_SKIPPED, không xử lý như NO_BUTTONS (kẹt empty).
     */
    fun hasVisibleSelfAlreadyLikedLikeControl(root: AccessibilityNodeInfo): Boolean {
        fun probeRaw(raw: AccessibilityNodeInfo?): Boolean {
            if (raw == null) return false
            val leaf = resolveLikeLeafNode(raw) ?: return false
            if (!leaf.hasValidScreenBounds()) return false
            if (LikeViewIdRules.shouldRejectNodeForLike(leaf)) return false
            return isAlreadyLiked(leaf)
        }

        var savedId = idStore.getLikeButtonID()
        if (savedId != null && LikeViewIdRules.isBlacklistedResourceId(savedId)) {
            savedId = null
        }
        if (savedId != null) {
            val byId = root.findAccessibilityNodeInfosByViewId(savedId)
            try {
                if (byId != null) {
                    for (n in byId) {
                        if (probeRaw(n)) return true
                    }
                }
            } finally {
                byId?.forEach { runCatching { it.recycle() } }
            }
        }

        val byText = root.findAccessibilityNodeInfosByText(ZaloIDStore.TEXT_LIKE)
        try {
            for (raw in byText) {
                if (probeRaw(raw)) return true
            }
        } finally {
            byText.forEach { runCatching { it.recycle() } }
        }

        for (raw in collectLikeHintsByTraversal(root, maxNodes = 2800)) {
            if (probeRaw(raw)) return true
        }
        return false
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
            if (!node.hasValidScreenBounds()) continue
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
     * Từ node gợi ý (text "Thích", scan…), tìm target thật trong subtree + vài cấp cha:
     * ưu tiên id whitelist (btn_like_text → btn_like_icon → btn_like → like_component), không dùng container blacklist.
     */
    private fun resolveLikeClickTarget(raw: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        val leaf = resolveLikeLeafNode(raw) ?: return null
        return resolveLikeClickTargetFromLeaf(leaf)
    }

    /** Leaf node đúng vùng like (whitelist) — dùng cho isAlreadyLiked/state checks. */
    private fun resolveLikeLeafNode(raw: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (raw == null) return null
        var current: AccessibilityNodeInfo? = raw
        repeat(8) {
            val host = current ?: return null
            val best = findBestLikeClickTargetInSubtree(host, maxDepth = 12)
            if (best != null) return best
            current = host.parent
        }
        return null
    }

    /** Click target: từ leaf leo lên tối đa 6 cấp cha, lấy ancestor clickable/longClickable đầu tiên (không blacklist class). */
    private fun resolveLikeClickTargetFromLeaf(leaf: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var p: AccessibilityNodeInfo? = leaf
        repeat(6) {
            val parent = p?.parent ?: return leaf
            if (!LikeViewIdRules.isBlacklistedClassName(parent.className?.toString()) &&
                (parent.isClickable || parent.isLongClickable)
            ) {
                return parent
            }
            p = parent
        }
        return leaf
    }

    private fun findBestLikeClickTargetInSubtree(
        root: AccessibilityNodeInfo,
        maxDepth: Int
    ): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestP = Int.MAX_VALUE
        var bestArea = Int.MAX_VALUE
        var bestClickable = false
        val q = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        q.addLast(root to 0)
        while (q.isNotEmpty()) {
            val (n, d) = q.removeFirst()
            if (d > maxDepth) continue
            val id = n.viewIdResourceName
            if (LikeViewIdRules.isWhitelistedLikeResourceId(id) &&
                !LikeViewIdRules.shouldRejectNodeForLike(n) &&
                n.hasValidScreenBounds()
            ) {
                val p = LikeViewIdRules.likeClickPriority(id)
                val r = Rect()
                n.getBoundsInScreen(r)
                val area = r.width() * r.height()
                val clickable = n.isClickable || n.isLongClickable
                val better =
                    // Ưu tiên node có thể click thật (tránh btn_like_text clickable=false).
                    (clickable && !bestClickable) ||
                        (clickable == bestClickable && (p < bestP || (p == bestP && area < bestArea)))
                if (better) {
                    bestP = p
                    bestArea = area
                    bestClickable = clickable
                    best = n
                }
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { child -> q.addLast(child to (d + 1)) }
            }
        }
        return best
    }

    private fun collectNearbyNodesForLikeState(anchor: AccessibilityNodeInfo, cap: Int): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val seen = HashSet<Int>()
        fun addSafe(n: AccessibilityNodeInfo) {
            if (out.size >= cap) return
            val code = System.identityHashCode(n)
            if (seen.add(code)) out.add(n)
        }
        var host: AccessibilityNodeInfo? = anchor
        repeat(7) {
            val h = host ?: return@repeat
            val q = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
            q.addLast(h to 0)
            while (q.isNotEmpty() && out.size < cap) {
                val (n, d) = q.removeFirst()
                if (d > 9) continue
                addSafe(n)
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let { child -> q.addLast(child to (d + 1)) }
                }
            }
            host = h.parent
        }
        return out
    }

    private fun idTailImpliesUserReaction(id: String?): Boolean {
        val t = LikeViewIdRules.resourceIdTail(id)
        return t.contains("my_reaction") || t.contains("myreact") || t.contains("user_reaction")
    }

    /**
     * Viewer ảnh toàn màn (vd. share / album) — [vpager] hoặc ViewPager lớn.
     */
    fun isLikelyZaloImageViewer(root: AccessibilityNodeInfo?, screenW: Int, screenH: Int): Boolean {
        if (root == null || screenW <= 0 || screenH <= 0) return false
        val thresh = (screenW * screenH * 0.42f).toInt().coerceAtLeast(1)
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 2200) {
            val n = stack.removeLast()
            visited++
            val id = n.viewIdResourceName
            if (LikeViewIdRules.isBlacklistedResourceId(id) &&
                LikeViewIdRules.resourceIdTail(id).contains("vpager")
            ) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.width() * r.height() >= thresh) return true
            }
            val simple = n.className?.toString()?.substringAfterLast('.') ?: ""
            if (simple.equals("ViewPager", ignoreCase = true) ||
                simple.equals("ViewPager2", ignoreCase = true)
            ) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.width() * r.height() >= thresh) return true
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return false
    }

    /** Text placeholder ô bình luận / IME — không phải nội dung bài; dùng làm snippet sẽ gây postKey trùng. */
    private fun isCommentUiNoiseForPostKey(raw: String?): Boolean {
        val t = raw?.trim()?.lowercase().orEmpty()
        if (t.isEmpty()) return false
        if (t == "bình luận" || t == "comment") return true
        val noisePhrases = listOf(
            "nhập bình luận",
            "viết bình luận",
            "thêm bình luận",
            "bình luận của bạn",
            "write a comment",
            "add a comment",
            "post a comment",
            "enter a comment",
            "say something",
        )
        if (noisePhrases.any { t == it || t.startsWith(it) }) return true
        if (noisePhrases.any { phrase -> t.contains(phrase) && t.length <= phrase.length + 8 }) return true
        return false
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
                if (isCommentUiNoiseForPostKey(t)) return
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
                consider(n.hintText?.toString())
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

    /**
     * Chỉ coi là "Đã thích của tài khoản hiện tại" khi label/state rõ ràng — không dùng
     * khối reaction count (người khác đã like vẫn có reaction_info).
     */
    private fun textIndicatesCurrentUserLiked(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val s = raw.trim()
        if (s.contains("Đã thích", ignoreCase = true)) return true
        if (s.contains("đã thích", ignoreCase = true)) return true
        // Tiếng Anh: tránh "1.2K likes", "5 liked this"
        if (s.any { it.isDigit() }) return false
        if (s.equals("Liked", ignoreCase = true)) return true
        if (s.startsWith("Liked", ignoreCase = true) && s.length <= 28) return true
        return false
    }

    /**
     * Đã like **của user hiện tại** — không kết luận "chưa like" chỉ vì text vẫn "Thích" (Zalo đôi khi stale).
     * Ưu tiên: [isChecked]/[isSelected]/stateDescription trên vùng id like / reaction của user;
     * chỉ tin text "Đã thích" rõ ràng, không dùng "Thích" làm bằng chứng chưa like.
     */
    fun isAlreadyLiked(node: AccessibilityNodeInfo): Boolean {
        for (n in collectNearbyNodesForLikeState(node, cap = 140)) {
            if (LikeViewIdRules.shouldRejectNodeForLike(n)) continue
            val id = n.viewIdResourceName
            val tail = LikeViewIdRules.resourceIdTail(id)

            val whitelistedOrMine =
                LikeViewIdRules.isWhitelistedLikeResourceId(id) || idTailImpliesUserReaction(id)

            if (whitelistedOrMine) {
                if (n.isChecked || n.isSelected) return true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val st = n.stateDescription?.toString().orEmpty()
                    if (st.isNotBlank()) {
                        if (textIndicatesCurrentUserLiked(st)) return true
                        if (st.contains("selected", ignoreCase = true) ||
                            st.contains("đã chọn", ignoreCase = true)
                        ) {
                            return true
                        }
                    }
                }
            }

            if (tail.contains("btn_like_text") || tail.contains("btn_like_icon")) {
                if (textIndicatesCurrentUserLiked(n.text?.toString())) return true
                if (textIndicatesCurrentUserLiked(n.contentDescription?.toString())) return true
            }

            if (whitelistedOrMine &&
                textIndicatesCurrentUserLiked(n.contentDescription?.toString())
            ) {
                return true
            }
        }

        if (textIndicatesCurrentUserLiked(node.text?.toString())) return true
        if (textIndicatesCurrentUserLiked(node.contentDescription?.toString())) return true

        return false
    }

    /**
     * Tìm lại nút like trên [root] mới tương ứng [original] (cùng view id + gần bounds) — tránh click snapshot cũ.
     * @return null nếu không khớp được.
     */
    fun reResolveLikeNodeForClick(
        root: AccessibilityNodeInfo,
        original: AccessibilityNodeInfo
    ): AccessibilityNodeInfo? {
        if (!original.hasValidScreenBounds()) return null
        val origRect = Rect()
        original.getBoundsInScreen(origRect)
        val origId = original.viewIdResourceName
        val tol = 36
        val candidates = findLikeButtons(root)
        if (origId != null && origId.isNotBlank()) {
            for (n in candidates) {
                if (n.viewIdResourceName != origId) continue
                val r = Rect()
                n.getBoundsInScreen(r)
                if (!n.hasValidScreenBounds()) continue
                if (abs(r.centerX() - origRect.centerX()) <= tol &&
                    abs(r.centerY() - origRect.centerY()) <= tol
                ) {
                    return n
                }
            }
        }
        for (n in candidates) {
            val r = Rect()
            n.getBoundsInScreen(r)
            if (!n.hasValidScreenBounds()) continue
            if (abs(r.centerX() - origRect.centerX()) <= tol &&
                abs(r.centerY() - origRect.centerY()) <= tol
            ) {
                return n
            }
        }
        return null
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
            if (!node.hasValidScreenBounds()) {
                if (node.childCount > 0 && depth < 60) {
                    for (i in node.childCount - 1 downTo 0) {
                        node.getChild(i)?.let { stack.addLast(it to (depth + 1)) }
                    }
                }
                continue
            }

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
            if (!node.hasValidScreenBounds()) {
                if (node.childCount > 0 && depth < 60) {
                    for (i in node.childCount - 1 downTo 0) {
                        node.getChild(i)?.let { stack.addLast(it to (depth + 1)) }
                    }
                }
                continue
            }
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
