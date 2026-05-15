package com.zalopilot.app.accessibility

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.zalopilot.app.data.model.ZaloIDStore
import com.zalopilot.app.util.LikeSettingsManager
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
    private val logger: Logger,
    private val settingsManager: LikeSettingsManager
) {
    private val inlineCommentPhrases = listOf(
        "nhập bình luận",
        "viết bình luận",
        "thêm bình luận",
        "write a comment",
        "add a comment",
        "post a comment",
        "enter a comment"
    )

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

    /** Bottom sheet Zing MP3 (tap nhầm media trên feed). */
    fun isZingMusicBottomSheet(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        return screenContainsTextOrDesc(root, "Nghe trên Zing MP3", maxNodes = 900) ||
            screenContainsTextOrDesc(root, "Đăng lên nhật ký", maxNodes = 900)
    }

    /**
     * Số lượt thích hiển thị trên bài (vd. 12 / "12 bạn") — chỉ log/UI.
     * Không dùng để suy [isAlreadyLiked] (tránh nhầm like người khác).
     */
    fun readPostReactionCount(likeNode: AccessibilityNodeInfo): Int {
        if (!likeNode.hasValidScreenBounds()) return 0
        val likeRect = Rect().also { likeNode.getBoundsInScreen(it) }
        var bestCount = 0
        var bestScore = Int.MIN_VALUE

        for (n in collectNearbyNodesForLikeState(likeNode, cap = 220)) {
            if (System.identityHashCode(n) == System.identityHashCode(likeNode)) continue
            if (LikeViewIdRules.isWhitelistedLikeResourceId(n.viewIdResourceName)) continue

            val idTail = LikeViewIdRules.resourceIdTail(n.viewIdResourceName)
            val fields = listOf(
                n.text?.toString(),
                n.contentDescription?.toString(),
                n.hintText?.toString()
            )
            for (raw in fields) {
                val parsed = parseReactionCountFromText(raw) ?: continue
                if (parsed <= 0) continue

                var score = 10
                when {
                    idTail.contains("reaction_info") -> score += 80
                    idTail.contains("reaction_count") -> score += 70
                    idTail.contains("reaction") -> score += 55
                    idTail.contains("like_count") -> score += 50
                    idTail.contains("num_like") -> score += 45
                }

                val r = Rect().also { n.getBoundsInScreen(it) }
                if (r.width() <= 0 || r.height() <= 0) continue
                if (r.left >= likeRect.right - 24) score += 25
                val yDelta = abs(r.centerY() - likeRect.centerY())
                if (yDelta <= (likeRect.height() * 2).coerceAtLeast(80)) score += 15
                val rawLen = raw?.trim()?.length ?: 0
                if (rawLen in 1..16) score += 8

                if (score > bestScore) {
                    bestScore = score
                    bestCount = parsed
                }
            }
        }
        return bestCount
    }

    fun readPostReactionCountLabel(likeNode: AccessibilityNodeInfo): String? {
        val n = readPostReactionCount(likeNode)
        return if (n > 0) n.toString() else null
    }

    private fun parseReactionCountFromText(raw: String?): Int? {
        val t = raw?.trim().orEmpty()
        if (t.isEmpty() || isReactionCountLabelNoise(t)) return null
        if (textIndicatesCurrentUserLiked(t)) return null

        val digitsOnly = t.replace(",", "").replace(".", "")
        if (digitsOnly.all { it.isDigit() }) {
            return digitsOnly.toIntOrNull()?.takeIf { it > 0 }
        }

        val patterns = listOf(
            Regex("""(\d[\d,]*)\s*(?:bạn|friends?|người|others?)""", RegexOption.IGNORE_CASE),
            Regex("""(\d[\d,]*)\s*(?:likes?|thích)""", RegexOption.IGNORE_CASE),
            Regex("""(\d[\d,]*)\s*(?:đã\s+thích|liked)""", RegexOption.IGNORE_CASE),
            Regex("""^(\d[\d,]*)$""")
        )
        for (pattern in patterns) {
            val m = pattern.find(t) ?: continue
            val num = m.groupValues[1].replace(",", "")
            val v = num.toIntOrNull() ?: continue
            if (v > 0) return v
        }
        return null
    }

    private fun isReactionCountLabelNoise(raw: String): Boolean {
        val t = raw.trim().lowercase()
        if (t.length > 48) return true
        if (t == ZaloIDStore.TEXT_LIKE.lowercase() || t == ZaloIDStore.TEXT_LIKED.lowercase()) return true
        val noise = listOf(
            "bình luận",
            "comment",
            "chia sẻ",
            "share",
            "nhập bình luận",
            "write a comment",
            "nghe trên zing",
            "đăng lên nhật ký"
        )
        if (noise.any { t == it || t.startsWith(it) }) return true
        return false
    }

    /**
     * Đang ở tab Nhật ký / feed (có swipe refresh, layout feed, nút Thích…).
     * Dùng chặn nhận nhầm viewer ảnh khi cuộn feed (feed có vpager / tab dưới).
     */
    fun isLikelyTimelineFeedScreen(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        if (hasZaloMainBottomTabs(root)) return true
        if (hasViewId(root, "swipe_refresh_layout")) return true
        if (hasViewId(root, "layoutSocialFeed")) return true
        if (hasViewId(root, "feedItemFooterBarModule")) return true
        if (findLikeButtons(root).isNotEmpty()) return true
        if (findTimelineTab(root) != null) return true
        if (screenContainsTextOrDesc(root, ZaloIDStore.TEXT_TIMELINE, 900)) return true
        if (screenContainsTextOrDesc(root, ZaloIDStore.TEXT_LIKE, 700)) return true
        return screenContainsTextOrDesc(root, ZaloIDStore.TEXT_LIKED, 700)
    }

    /** Thanh tab dưới Zalo (Tin nhắn / Danh bạ) — có trên feed, thường ẩn khi xem ảnh full-screen. */
    fun hasZaloMainBottomTabs(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        return hasViewId(root, "maintab_message") || hasViewId(root, "maintab_contact")
    }

    /**
     * Viewer ảnh full-screen sau khi tap nhầm ảnh trên feed.
     * Không dùng trong vòng lặp cuộn — feed có vpager lớn dễ false positive.
     */
    fun isStrictFullscreenImageViewer(
        root: AccessibilityNodeInfo?,
        screenW: Int,
        screenH: Int
    ): Boolean {
        if (root == null || screenW <= 0 || screenH <= 0) return false
        if (isLikelyTimelineFeedScreen(root)) return false
        if (hasZaloMainBottomTabs(root)) return false
        if (hasInlineFeedCommentComposerVisible(root)) return false

        val screenArea = screenW * screenH
        val minArea = (screenArea * 0.88f).toInt().coerceAtLeast(1)
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 2200) {
            val n = stack.removeLast()
            visited++
            val tail = LikeViewIdRules.resourceIdTail(n.viewIdResourceName)
            if (tail.contains("vpager")) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.width() * r.height() >= minArea) return true
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return false
    }

    /** Nhiều placeholder "Nhập bình luận" inline → đang ở feed, không phải viewer ảnh. */
    private fun hasInlineFeedCommentComposerVisible(root: AccessibilityNodeInfo): Boolean {
        if (hasViewId(root, "cmtinput_text")) return false
        var count = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 1200 && count < 2) {
            val n = stack.removeLast()
            visited++
            val fields = listOf(
                n.text?.toString(),
                n.contentDescription?.toString(),
                n.hintText?.toString()
            )
            for (raw in fields) {
                val low = raw?.trim()?.lowercase().orEmpty()
                if (low == "nhập bình luận" || low.startsWith("nhập bình luận") ||
                    low == "viết bình luận" || low.startsWith("viết bình luận")
                ) {
                    count++
                    break
                }
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return count >= 2
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
        if (footerBarImpliesSelfLiked(node)) return true

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
     * Tìm lại node like-area (id whitelist) tại bounds [origRect] trên [root] mới — KHÔNG lọc theo
     * `isAlreadyLiked`. Dùng cho **verify sau click**: sau khi like xong nút chuyển "Đã thích" và
     * `findLikeButtons` sẽ bỏ qua → nếu dùng `reResolveLikeNodeForClick` (chain qua findLikeButtons)
     * sẽ trả null → caller fallback dùng node CŨ (snapshot stale) → kết luận chưa like → re-click → unlike nhầm.
     */
    fun findLikeAreaNodeAt(
        root: AccessibilityNodeInfo,
        origRect: Rect,
        origId: String?
    ): AccessibilityNodeInfo? {
        for (tol in intArrayOf(48, 120, 200)) {
            findLikeAreaNodeAtTolerance(root, origRect, origId, tol)?.let { return it }
        }
        return findNearestLikeAreaNode(root, origRect, maxDistancePx = 260)
    }

    /** Verify sau click khi bounds/id lệch nhẹ do animation feed. */
    fun verifyLikedNearClickArea(root: AccessibilityNodeInfo, origRect: Rect, origId: String?): Boolean {
        val anchor = findLikeAreaNodeAt(root, origRect, origId)
            ?: findNearestLikeAreaNode(root, origRect, maxDistancePx = 320)
        if (anchor != null && isAlreadyLiked(anchor)) return true
        return isLikedStateInRectBand(root, origRect)
    }

    private fun findLikeAreaNodeAtTolerance(
        root: AccessibilityNodeInfo,
        origRect: Rect,
        origId: String?,
        tol: Int
    ): AccessibilityNodeInfo? {
        fun match(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            if (LikeViewIdRules.shouldRejectNodeForLike(n)) return null
            if (!n.hasValidScreenBounds()) return null
            val r = Rect()
            n.getBoundsInScreen(r)
            if (abs(r.centerX() - origRect.centerX()) <= tol &&
                abs(r.centerY() - origRect.centerY()) <= tol
            ) {
                return n
            }
            return null
        }

        if (!origId.isNullOrBlank()) {
            val byId = root.findAccessibilityNodeInfosByViewId(origId)
            if (byId != null) {
                for (n in byId) {
                    val ok = match(n)
                    if (ok != null) return ok
                }
            }
        }

        val savedId = idStore.getLikeButtonID()
        if (!savedId.isNullOrBlank() && savedId != origId) {
            val byId = root.findAccessibilityNodeInfosByViewId(savedId)
            if (byId != null) {
                for (n in byId) {
                    val ok = match(n)
                    if (ok != null) return ok
                }
            }
        }

        // Fallback theo whitelist id thông dụng (không cần biết user đã học id nào).
        for (tail in listOf("btn_like_text", "btn_like_icon", "btn_like", "like_component")) {
            for (pkg in listOf("com.zing.zalo")) {
                val full = "$pkg:id/$tail"
                val byId = root.findAccessibilityNodeInfosByViewId(full) ?: continue
                for (n in byId) {
                    val ok = match(n)
                    if (ok != null) return ok
                }
            }
        }

        // Cuối cùng quét traversal hint — tốn hơn nhưng đảm bảo bắt được node.
        for (raw in collectLikeHintsByTraversal(root, maxNodes = 2800)) {
            val ok = match(raw)
            if (ok != null) return ok
        }
        return null
    }

    private fun findNearestLikeAreaNode(
        root: AccessibilityNodeInfo,
        origRect: Rect,
        maxDistancePx: Int
    ): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestDist = Int.MAX_VALUE
        val ox = origRect.centerX()
        val oy = origRect.centerY()
        for (raw in collectLikeHintsByTraversal(root, maxNodes = 2800)) {
            val leaf = resolveLikeLeafNode(raw) ?: continue
            if (LikeViewIdRules.shouldRejectNodeForLike(leaf)) continue
            if (!leaf.hasValidScreenBounds()) continue
            val r = Rect().also { leaf.getBoundsInScreen(it) }
            val d = abs(r.centerX() - ox) + abs(r.centerY() - oy)
            if (d <= maxDistancePx && d < bestDist) {
                bestDist = d
                best = leaf
            }
        }
        return best
    }

    private fun footerBarImpliesSelfLiked(likeNode: AccessibilityNodeInfo): Boolean {
        var p: AccessibilityNodeInfo? = likeNode
        repeat(10) {
            if (p == null) return false
            val id = p.viewIdResourceName.orEmpty()
            if (id.contains("feedItemFooterBarModule")) {
                val t = p.text?.toString().orEmpty()
                if (t.isBlank()) return false
                for (line in t.lines()) {
                    val s = line.trim()
                    if (s.equals(ZaloIDStore.TEXT_LIKED, ignoreCase = true)) return true
                    if (s.startsWith(ZaloIDStore.TEXT_LIKED, ignoreCase = true) &&
                        s.length <= 24 &&
                        !s.contains("bạn", ignoreCase = true)
                    ) {
                        return true
                    }
                }
                return false
            }
            p = p.parent
        }
        return false
    }

    /** Quét vùng ngang quanh [origRect] (feed có thể dịch sau tap). */
    private fun isLikedStateInRectBand(root: AccessibilityNodeInfo, origRect: Rect): Boolean {
        val band = Rect(
            (origRect.left - 80).coerceAtLeast(0),
            (origRect.top - 100).coerceAtLeast(0),
            origRect.right + 400,
            origRect.bottom + 80
        )
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 1600) {
            val n = stack.removeLast()
            visited++
            if (n.hasValidScreenBounds()) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                if (Rect.intersects(band, r)) {
                    if (LikeViewIdRules.isWhitelistedLikeResourceId(n.viewIdResourceName) &&
                        isAlreadyLiked(n)
                    ) {
                        return true
                    }
                }
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.addLast(it) }
            }
        }
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

        // Không skip vì "Nhập bình luận" gần like — mọi bài trên feed đều có ô comment inline.

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

    /**
     * Màn full-screen "Bình luận" của Zalo (mở từ tap vào icon comment hoặc nhầm khi click).
     *
     * Khác với composer **inline trên feed** (nhiều bài cũng có "Nhập bình luận" gần nút Like):
     *  - Header (top app bar) có text == "Bình luận" nằm sát đỉnh màn hình
     *  - + Có ô nhập "Nhập bình luận" hoặc text "Chưa có bình luận" / "Thả sticker..."
     *
     * Khi đúng màn này, bot phải `GLOBAL_ACTION_BACK` để về feed; không thoát sẽ kẹt
     * và heuristic [hasInlineCommentComposerNearLikeAnchor] dễ confirm sai ngữ cảnh.
     */
    fun isContactListScreen(root: AccessibilityNodeInfo): Boolean {
        if (hasChatScreenMarkers(root) || hasProfileScreenMarkers(root)) return false
        if (hasViewId(root, "main_chat_view")) return false
        val friendsTabSelected = findByViewId(root, "tv_friends").any { node ->
            val label = node.text?.toString().orEmpty()
            (label.contains("Bạn bè", ignoreCase = true) || label.contains("Friends", ignoreCase = true)) &&
                (node.isSelected || node.isChecked)
        }
        if (friendsTabSelected) return true
        val onDanhBa = findByViewId(root, "maintab_contact").isNotEmpty() ||
            screenContainsTextOrDesc(root, "Danh bạ", 1200) ||
            screenContainsTextOrDesc(root, "Contacts", 1200)
        val hasFriendsSubTab = screenContainsTextOrDesc(root, "Bạn bè", 1200) ||
            screenContainsTextOrDesc(root, "Friends", 1200)
        if (onDanhBa && hasFriendsSubTab) return true
        return hasViewId(root, "tv_friends") && hasFriendsSubTab
    }

    private fun hasChatScreenMarkers(root: AccessibilityNodeInfo): Boolean =
        hasViewId(root, "main_chat_view") &&
            (hasViewId(root, "chatinput_text") || hasViewId(root, "chat_drawer_layout"))

    private fun hasProfileScreenMarkers(root: AccessibilityNodeInfo): Boolean =
        hasViewId(root, "rl_profile_bio_container") ||
            hasViewId(root, "profile_avatar") ||
            hasViewId(root, "profile_bottom_functions_layout") ||
            (hasViewId(root, "feedItemFooterBarModule") && hasViewId(root, "layoutSendMessage"))

    fun isChatScreen(root: AccessibilityNodeInfo): Boolean {
        if (hasProfileScreenMarkers(root)) return false
        return hasChatScreenMarkers(root)
    }

    fun isProfileScreen(root: AccessibilityNodeInfo): Boolean {
        if (hasChatScreenMarkers(root)) return false
        return hasProfileScreenMarkers(root)
    }

    fun findNodeWithTextOrDesc(
        root: AccessibilityNodeInfo,
        needle: String,
        ignoreCase: Boolean = true,
        maxNodes: Int = 2000
    ): AccessibilityNodeInfo? = findFirstNodeWithTextOrDesc(root, needle, maxNodes, ignoreCase)

    fun findNodeWithHint(
        root: AccessibilityNodeInfo,
        needle: String,
        maxNodes: Int = 2000
    ): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < maxNodes) {
            val node = stack.removeLast()
            visited++
            val fields = listOf(
                node.hintText?.toString(),
                node.text?.toString(),
                node.contentDescription?.toString()
            )
            for (raw in fields) {
                val t = raw?.trim().orEmpty()
                if (t.isNotEmpty() && t.contains(needle, ignoreCase = true)) return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return null
    }

    /** Hàng danh bạ / bạn bè — ưu tiên ID đã học ([ZaloIDStore]), rồi RecyclerView/heuristic. */
    fun findContactListItems(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val rootRect = Rect().also { root.getBoundsInScreen(it) }
        val tabBottom = findByViewId(root, "layoutTab").firstOrNull()?.let { tab ->
            Rect().also { tab.getBoundsInScreen(it) }.bottom
        } ?: (rootRect.top + rootRect.height() / 7)
        val navTop = rootRect.top + (rootRect.height() * 0.86f).toInt()
        val skipPhrases = contactSkipPhrases()

        findContactItemsFromStore(root, tabBottom, navTop, skipPhrases)?.let { return it }

        val fromRecycler = mutableListOf<AccessibilityNodeInfo>()
        collectRecyclerRowItems(root, tabBottom, navTop, fromRecycler, skipPhrases)
        if (fromRecycler.isNotEmpty()) {
            return fromRecycler.distinctBy { contactRowKey(it) }
        }

        val out = mutableListOf<AccessibilityNodeInfo>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 2500 && out.size < 80) {
            val n = stack.removeLast()
            visited++
            if (isContactRowCandidate(n, tabBottom, navTop, skipPhrases)) {
                out.add(n)
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return out
            .distinctBy { contactRowKey(it) }
            .sortedBy { Rect().also { r -> it.getBoundsInScreen(r) }.top }
    }

    /** Chat → profile: tap action bar giữa (actionbar_txtTitle / zds_action_bar). */
    fun findProfileEntryNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        findByViewId(root, "zds_action_bar").firstOrNull { it.isClickable }?.let { return it }
        findByViewId(root, "actionbar_middle_container").firstOrNull()?.let { middle ->
            var p: AccessibilityNodeInfo? = middle
            repeat(6) {
                if (p == null) return@repeat
                if (p.isClickable && p.hasValidScreenBounds()) return p
                p = p.parent
            }
            return middle
        }
        findByViewId(root, "actionbar_txtTitle").firstOrNull()?.let { title ->
            return findClickableAncestor(title) ?: title
        }
        return null
    }

    /** Like trên timeline profile — quét trong feedItemFooterBarModule trước. */
    fun findProfileLikeButtons(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        for (footer in findByViewId(root, "feedItemFooterBarModule")) {
            val inFooter = findLikeButtons(footer)
            if (inFooter.isNotEmpty()) return inFooter
        }
        return findLikeButtons(root)
    }

    fun findCommentButton(likeNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var footer: AccessibilityNodeInfo? = null
        var walk: AccessibilityNodeInfo? = likeNode
        var steps = 0
        while (walk != null && steps < 10) {
            val id = walk.viewIdResourceName.orEmpty()
            if (id.contains("feedItemFooterBarModule")) {
                footer = walk
                break
            }
            walk = walk.parent
            steps++
        }
        footer?.let { bar ->
            findClickableWithPhrase(bar, "bình luận")?.let { return it }
            findClickableWithPhrase(bar, "comment")?.let { return it }
        }

        val likeRect = Rect().also { likeNode.getBoundsInScreen(it) }
        val parent = likeNode.parent ?: return null
        for (i in 0 until parent.childCount) {
            val sib = parent.getChild(i) ?: continue
            if (sib == likeNode) continue
            if (!sib.isClickable) continue
            val r = Rect().also { sib.getBoundsInScreen(it) }
            if (r.left < likeRect.right - 8) continue
            val cls = sib.className?.toString()?.lowercase().orEmpty()
            if (!cls.contains("image") && !cls.contains("button")) continue
            val t = sib.text?.toString().orEmpty()
            if (t.contains("…") || t.contains("...")) continue
            return sib
        }
        return null
    }

    fun findCommentInput(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findByViewId(root, "cmtinput_text").firstOrNull()
            ?: findNodeWithHint(root, "Nhập bình luận", 2000)
            ?: findNodeWithHint(root, "Write a comment", 2000)

    fun findCommentSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findByViewId(root, "cmtinput_send").firstOrNull { it.isClickable }
            ?: findByViewId(root, "cmtinput_send").firstOrNull()

    fun getRandomComment(): String {
        val list = settingsManager.getVisitCommentList()
        if (list.isEmpty()) return ""
        return list.random()
    }

    fun findByViewId(root: AccessibilityNodeInfo, idSuffix: String): List<AccessibilityNodeInfo> {
        val fullId = if (idSuffix.contains(":id/")) idSuffix else "com.zing.zalo:id/$idSuffix"
        return root.findAccessibilityNodeInfosByViewId(fullId) ?: emptyList()
    }

    fun hasViewId(root: AccessibilityNodeInfo, idSuffix: String): Boolean =
        findByViewId(root, idSuffix).isNotEmpty()

    private fun findContactItemsFromStore(
        root: AccessibilityNodeInfo,
        tabBottom: Int,
        navTop: Int,
        skipPhrases: List<String>
    ): List<AccessibilityNodeInfo>? {
        val listId = idStore.getContactListID()
        if (!listId.isNullOrBlank()) {
            val recyclers = root.findAccessibilityNodeInfosByViewId(listId) ?: emptyList()
            if (recyclers.isEmpty()) {
                idStore.clearContactListID()
            } else {
                val out = mutableListOf<AccessibilityNodeInfo>()
                for (rv in recyclers) {
                    collectContactRowsFromRecycler(rv, tabBottom, navTop, skipPhrases, out)
                }
                val sorted = out.distinctBy { contactRowKey(it) }
                    .sortedBy { Rect().also { r -> it.getBoundsInScreen(r) }.top }
                if (sorted.isNotEmpty()) return sorted
            }
        }
        val itemId = idStore.getContactItemID()
        if (!itemId.isNullOrBlank()) {
            val rows = (root.findAccessibilityNodeInfosByViewId(itemId) ?: emptyList())
                .filter { isContactRowCandidate(it, tabBottom, navTop, skipPhrases) }
            if (rows.isEmpty()) {
                idStore.clearContactItemID()
            } else {
                return rows.distinctBy { contactRowKey(it) }
                    .sortedBy { Rect().also { r -> it.getBoundsInScreen(r) }.top }
            }
        }
        return null
    }

    private fun contactSkipPhrases(): List<String> = listOf(
        "friend request", "lời mời kết bạn",
        "birthday", "sinh nhật", "contacts", "danh bạ", "tất cả", "all",
        "bạn bè", "friends", "nhóm", "groups", "oa", "tìm kiếm", "search"
    )

    private fun collectContactRowsFromRecycler(
        recycler: AccessibilityNodeInfo,
        tabBottom: Int,
        navTop: Int,
        skipPhrases: List<String>,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        for (i in 0 until recycler.childCount) {
            val child = recycler.getChild(i) ?: continue
            if (isContactRowCandidate(child, tabBottom, navTop, skipPhrases)) {
                out.add(child)
            }
        }
    }

    private fun collectRecyclerRowItems(
        root: AccessibilityNodeInfo,
        tabBottom: Int,
        navTop: Int,
        out: MutableList<AccessibilityNodeInfo>,
        skipPhrases: List<String>
    ) {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 2200 && out.size < 80) {
            val n = stack.removeLast()
            visited++
            val cls = n.className?.toString().orEmpty()
            if (cls.contains("RecyclerView", ignoreCase = true)) {
                collectContactRowsFromRecycler(n, tabBottom, navTop, skipPhrases, out)
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.addLast(it) }
            }
        }
    }

    private fun isContactRowCandidate(
        n: AccessibilityNodeInfo,
        tabBottom: Int,
        navTop: Int,
        skipPhrases: List<String>
    ): Boolean {
        if (!n.isClickable || !n.hasValidScreenBounds()) return false
        val r = Rect().also { n.getBoundsInScreen(it) }
        if (r.height() !in 100..320 || r.width() < 200) return false
        if (r.centerY() < tabBottom || r.centerY() > navTop) return false
        val label = collectNodeLabel(n)
        if (label.length !in 2..120) return false
        val low = label.lowercase()
        if (skipPhrases.any { low.contains(it) }) return false
        if (low.all { it.isDigit() }) return false
        if (looksLikeConversationPreview(label)) return false
        return true
    }

    private fun contactRowKey(n: AccessibilityNodeInfo): String {
        val r = Rect().also { n.getBoundsInScreen(it) }
        val name = collectNodeLabel(n).lineSequence().firstOrNull().orEmpty().trim()
        return "${r.top}_${r.left}_$name"
    }

    private fun looksLikeConversationPreview(label: String): Boolean {
        val low = label.lowercase()
        if (low.contains("bạn:")) return true
        if (low.contains("[file]") || low.contains("[ảnh]") || low.contains("[photo]")) return true
        if (Regex("""\d+\s*(phút|giây|giờ|min|mins|hour|hours)\b""").containsMatchIn(low)) return true
        if (low.contains("hôm qua") || low.contains("yesterday")) return true
        return false
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var p: AccessibilityNodeInfo? = node.parent
        repeat(6) {
            if (p == null) return null
            if (p.isClickable && p.hasValidScreenBounds()) return p
            p = p.parent
        }
        return null
    }

    private fun findClickableWithPhrase(root: AccessibilityNodeInfo, phrase: String): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 120) {
            val n = stack.removeLast()
            visited++
            val fields = listOf(
                n.text?.toString(),
                n.contentDescription?.toString()
            )
            for (raw in fields) {
                val t = raw?.trim().orEmpty()
                if (t.isNotEmpty() && t.contains(phrase, ignoreCase = true)) {
                    if (n.isClickable) return n
                    findClickableAncestor(n)?.let { return it }
                }
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return null
    }

    private fun collectNodeLabel(n: AccessibilityNodeInfo): String {
        val t = n.text?.toString()?.trim().orEmpty()
        if (t.isNotEmpty()) return t
        return n.contentDescription?.toString()?.trim().orEmpty()
    }

    private fun hasAvatarHint(n: AccessibilityNodeInfo): Boolean {
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.addLast(n)
        var visited = 0
        var hasImage = false
        var hasText = false
        while (q.isNotEmpty() && visited < 40) {
            val x = q.removeFirst()
            visited++
            val cls = x.className?.toString()?.lowercase().orEmpty()
            if (cls.contains("image")) hasImage = true
            val t = x.text?.toString()?.trim().orEmpty()
            if (t.length in 2..40) hasText = true
            for (i in 0 until x.childCount) {
                x.getChild(i)?.let { q.addLast(it) }
            }
        }
        return hasImage && hasText
    }

    fun isFullScreenCommentScreen(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false

        val titleBar = findByViewId(root, "action_bar_title").any {
            it.text?.toString()?.trim().equals("Bình luận", ignoreCase = true)
        }
        val hasDedicatedInput = hasViewId(root, "cmtinput_text")
        if (titleBar && hasDedicatedInput) return true

        if (isLikelyTimelineFeedScreen(root)) return false

        val rootRect = Rect()
        root.getBoundsInScreen(rootRect)
        val screenH = rootRect.height().takeIf { it > 0 } ?: 1920
        val topThreshold = (screenH * 0.18f).toInt().coerceAtLeast(220)

        var hasHeader = false
        var hasEmptyHint = false

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        val cap = 1500
        while (stack.isNotEmpty() && visited < cap) {
            val n = stack.removeLast()
            visited++
            val text = n.text?.toString()?.trim().orEmpty()
            val desc = n.contentDescription?.toString()?.trim().orEmpty()

            if (!hasHeader && text.equals("Bình luận", ignoreCase = true)) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.top in 0..topThreshold) hasHeader = true
            }

            if (!hasEmptyHint) {
                for (s in arrayOf(text, desc)) {
                    val low = s.lowercase()
                    if (low.startsWith("chưa có bình luận") ||
                        low.contains("thả sticker") ||
                        low.contains("hãy là người đầu tiên")
                    ) {
                        hasEmptyHint = true
                        break
                    }
                }
            }

            if (hasHeader && hasDedicatedInput) return true
            if (hasHeader && hasEmptyHint) return true

            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return false
    }

    fun hasInlineCommentComposerNearLikeAnchor(likeNode: AccessibilityNodeInfo): Boolean {
        fun matchesPhrase(s: String?): Boolean {
            val t = s?.trim()?.lowercase().orEmpty()
            if (t.isEmpty()) return false
            return inlineCommentPhrases.any { t.contains(it) }
        }

        // Lấy một container gần like (leo tối đa 6 cấp) rồi duyệt subtree tìm placeholder comment.
        var container: AccessibilityNodeInfo? = likeNode
        repeat(6) {
            val p = container?.parent ?: return@repeat
            container = p
        }
        val root = container ?: likeNode

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        val cap = 900
        while (stack.isNotEmpty() && visited < cap) {
            val n = stack.removeLast()
            visited++
            if (matchesPhrase(n.text?.toString()) || matchesPhrase(n.contentDescription?.toString())) return true
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return false
    }

    private fun boundsSummary(node: AccessibilityNodeInfo): String {
        val r = Rect()
        node.getBoundsInScreen(r)
        return "bounds=[${r.left},${r.top},${r.right},${r.bottom}] id=${node.viewIdResourceName ?: ""}"
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

    private fun screenContainsTextOrDesc(
        root: AccessibilityNodeInfo,
        needle: String,
        maxNodes: Int
    ): Boolean {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < maxNodes) {
            val node = stack.removeLast()
            visited++
            val t = node.text?.toString().orEmpty()
            val d = node.contentDescription?.toString().orEmpty()
            if (t.contains(needle, ignoreCase = true) || d.contains(needle, ignoreCase = true)) {
                return true
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return false
    }

    private fun screenContainsHintOrText(
        root: AccessibilityNodeInfo,
        needle: String,
        maxNodes: Int
    ): Boolean = findNodeWithHint(root, needle, maxNodes) != null

    private fun findFirstNodeWithTextOrDesc(
        root: AccessibilityNodeInfo,
        needle: String,
        maxNodes: Int,
        ignoreCase: Boolean = true
    ): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < maxNodes) {
            val node = stack.removeLast()
            visited++
            val t = node.text?.toString() ?: ""
            val d = node.contentDescription?.toString() ?: ""
            if (t.contains(needle, ignoreCase = ignoreCase) || d.contains(needle, ignoreCase = ignoreCase)) {
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
