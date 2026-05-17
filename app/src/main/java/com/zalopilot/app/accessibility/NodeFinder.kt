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
    fun findLikeButtons(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        findLikeButtonsInternal(root, useLearnedLikeId = true, skipAlreadyLiked = true)

    /** Neo comment — gồm bài đã thích (không lọc [isAlreadyLiked]). */
    fun findLikeAnchorsForFeedComment(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        findLikeButtonsInternal(root, useLearnedLikeId = true, skipAlreadyLiked = false)

    /**
     * Feed like — không lọc [isAlreadyLiked]; skip/xác nhận chỉ bằng ô bình luận trên item.
     */
    fun findFeedLikeTapTargets(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        findLikeButtonsInternal(root, useLearnedLikeId = true, skipAlreadyLiked = false)

    /**
     * Quét nút like chỉ bằng text + traversal — **không** dùng [ZaloIDStore.getLikeButtonID].
     * Layout profile/timeline dùng id khác feed nhật ký; saved id feed gây ID_MISS và bỏ lỡ nút like profile.
     */
    private fun findLikeButtonsWithoutLearnedId(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        findLikeButtonsInternal(root, useLearnedLikeId = false, skipAlreadyLiked = true)

    private fun findLikeButtonsInternal(
        root: AccessibilityNodeInfo,
        useLearnedLikeId: Boolean,
        skipAlreadyLiked: Boolean = true
    ): List<AccessibilityNodeInfo> {
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
            if (skipAlreadyLiked && isAlreadyLiked(leaf)) return

            val clickTarget = resolveLikeClickTargetFromLeaf(leaf)
            if (!clickTarget.hasValidScreenBounds()) return
            if (LikeViewIdRules.shouldRejectNodeForLike(clickTarget)) return

            val key = dedupeKey(clickTarget)
            if (key !in seen) {
                seen.add(key)
                result.add(clickTarget)
            }
        }

        if (useLearnedLikeId) {
            var savedId = idStore.getLikeButtonID()
            if (savedId != null && LikeViewIdRules.isBlacklistedResourceId(savedId)) {
                logger.log(LogTag.SCAN, "savedId=$savedId", "LIKE_ID_BLACKLIST_CLEARED")
                idStore.clearLikeButtonID()
                savedId = null
            }
            if (savedId != null) {
                val byId = root.findAccessibilityNodeInfosByViewId(savedId)
                if (byId.isNotEmpty()) {
                    byId.filter {
                        !LikeViewIdRules.shouldRejectNodeForLike(it) &&
                            (!skipAlreadyLiked || !isAlreadyLiked(it))
                    }.forEach { addResolved(it) }
                    if (result.isNotEmpty()) {
                        logFoundSummary(result)
                        return result
                    }
                }
                logger.log(LogTag.SCAN, "savedId=$savedId", "ID_MISS_FALLBACK")
            }
        }

        // findAccessibilityNodeInfosByText("Thích") có thể khớp cả "Đã thích" (substring).
        val byText = root.findAccessibilityNodeInfosByText(ZaloIDStore.TEXT_LIKE)
        for (raw in byText) {
            addResolved(raw)
        }

        collectLikeHintsByTraversal(root, maxNodes = 2800).forEach { raw ->
            addResolved(raw)
        }

        val logTag = if (useLearnedLikeId) "findLikeButtons" else "findProfileLikeButtonsNoId"
        if (result.isNotEmpty()) {
            logFoundSummary(result)
        } else {
            logger.log(LogTag.SCAN, logTag, "EMPTY")
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
        if (hasViewId(root, "maintab_timeline") &&
            (hasViewId(root, "layoutSocialFeed") ||
                hasViewId(root, "swipe_refresh_layout") ||
                hasViewId(root, "feedItemFooterBarModule"))
        ) {
            return true
        }
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
     * Nav dưới Zalo đã vẽ (sau cold start / splash) — có tab quen thuộc.
     * Dùng trước khi tap Nhật ký / Danh bạ để tránh tap khi cây chỉ mới “mở app”.
     */
    fun hasZaloBottomNavigationPresent(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        if (hasZaloMainBottomTabs(root)) return true
        if (hasViewId(root, "maintab_timeline")) return true
        if (hasViewId(root, "layoutSocialFeed") || hasViewId(root, "swipe_refresh_layout")) return true
        if (isContactListScreen(root)) return true
        return false
    }

    /**
     * Bottom sheet bình luận nửa màn đè lên feed nhật ký (tap nhầm icon comment).
     * Khác [isFullScreenCommentScreen] (không có action_bar_title + ô dedicated theo heuristic cũ).
     * Cần `bottom_sheet_container` + `main_comment_view` và vẫn thấy Neo feed/tab để tránh false positive.
     */
    fun isCommentBottomSheetOverFeed(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        if (!hasViewId(root, "bottom_sheet_container")) return false
        if (!hasViewId(root, "main_comment_view")) return false
        val feedStillVisible =
            hasViewId(root, "layoutSocialFeed") ||
                hasViewId(root, "lv_media_store") ||
                hasViewId(root, "maintab_timeline") ||
                hasViewId(root, "swipe_refresh_layout") ||
                hasViewId(root, "feedItemFooterBarModule") ||
                hasZaloMainBottomTabs(root)
        return feedStillVisible
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

    /**
     * Feed SPEC: có ô bình luận trên item — ưu tiên [hasInlineCommentComposerNearLikeAnchor] (đã ổn ở 77ecf09).
     */
    fun hasCommentBoxOnFeedItemNearLike(likeNode: AccessibilityNodeInfo): Boolean {
        if (hasExpandedInlineCommentComposerNearLike(likeNode)) return true
        if (hasInlineCommentComposerNearLikeAnchor(likeNode)) return true
        val host = findFeedFooterHostNearLike(likeNode)
        findByViewId(host, "cmtinput_text").forEach { n ->
            if (n.isVisibleToUser && n.hasValidScreenBounds()) return true
        }
        if (findCommentInputPlaceholderNearLike(likeNode) != null) return true
        if (findCommentInputNearLike(likeNode) != null) return true
        val scope = findFeedItemScopeNearLike(likeNode)
        if (hasCommentRowBelowFooter(scope, host)) return true
        return false
    }

    /**
     * Zalo hay đặt ô «Nhập bình luận» là **anh em** dưới footer — quét parent item, không chỉ subtree footer.
     */
    private fun findFeedItemScopeNearLike(likeNode: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var footer: AccessibilityNodeInfo? = null
        var walk: AccessibilityNodeInfo? = likeNode
        repeat(20) {
            val w = walk ?: return@repeat
            val id = w.viewIdResourceName.orEmpty().lowercase()
            if (id.contains("feeditemfooterbarmodule")) {
                footer = w
                w.parent?.let { parent ->
                    if (parent.childCount >= 2) return parent
                }
            }
            if (id.contains("feeditem") ||
                id.contains("media_store_item") ||
                id.contains("layoutsocialfeed")
            ) {
                return w
            }
            walk = w.parent
        }
        footer?.parent?.let { parent ->
            if (parent.childCount >= 2) return parent
        }
        return footer ?: findFeedFooterHostNearLike(likeNode)
    }

    private fun feedCommentPhraseInText(raw: String?): Boolean {
        val low = raw?.trim()?.lowercase().orEmpty()
        if (low.isEmpty()) return false
        return inlineCommentPhrases.any { low.contains(it) }
    }

    private fun nodeLooksLikeFeedCommentBox(n: AccessibilityNodeInfo): Boolean {
        if (!n.isVisibleToUser || !n.hasValidScreenBounds()) return false
        if (isLikeControlNode(n)) return false
        if (isCommentPlaceholderNode(n) || isLikelyCommentInputNode(n)) return true
        if (Build.VERSION.SDK_INT >= 18 && n.isEditable) return true
        val id = n.viewIdResourceName.orEmpty().lowercase()
        if (id.contains("cmtinput") && !id.contains("send")) return true
        if (id.contains("comment") && (id.contains("input") || id.contains("edit") || id.contains("composer"))) {
            return true
        }
        if (feedCommentPhraseInText(n.hintText?.toString())) return true
        if (feedCommentPhraseInText(n.text?.toString())) return true
        if (feedCommentPhraseInText(n.contentDescription?.toString())) return true
        return false
    }

    private fun scanFeedItemScopeForCommentBox(
        scope: AccessibilityNodeInfo,
        footer: AccessibilityNodeInfo
    ): Boolean {
        for (suffix in listOf(
            "cmtinput_text",
            "cmtinput",
            "comment_input",
            "feed_comment",
            "quick_comment",
            "layout_comment",
            "view_comment"
        )) {
            findByViewId(scope, suffix).forEach { n ->
                if (nodeLooksLikeFeedCommentBox(n)) return true
            }
        }
        findFeedItemFooters(scope).forEach { bar ->
            val low = bar.text?.toString().orEmpty().lowercase()
            if (inlineCommentPhrases.any { low.contains(it) }) return true
        }
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(scope)
        var visited = 0
        while (stack.isNotEmpty() && visited < 1_400) {
            val n = stack.removeLast()
            visited++
            if (nodeLooksLikeFeedCommentBox(n)) return true
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.addLast(it) }
            }
        }
        findCommentInputInScope(scope)?.let { return true }
        return false
    }

    /** Hàng nhập BL ngay dưới footer (đã like — Zalo tách khỏi module footer). */
    private fun hasCommentRowBelowFooter(
        scope: AccessibilityNodeInfo,
        footer: AccessibilityNodeInfo
    ): Boolean {
        if (!footer.hasValidScreenBounds()) return false
        val fr = Rect().also { footer.getBoundsInScreen(it) }
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(scope)
        var visited = 0
        while (stack.isNotEmpty() && visited < 900) {
            val n = stack.removeLast()
            visited++
            if (!n.hasValidScreenBounds()) continue
            val r = Rect().also { n.getBoundsInScreen(it) }
            if (r.top < fr.bottom - 16 || r.top > fr.bottom + 360) {
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let { stack.addLast(it) }
                }
                continue
            }
            if (nodeLooksLikeFeedCommentBox(n)) return true
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return false
    }

    /**
     * Verify feed like sau tap — tìm anchor theo bounds/id; fallback quét footer trong vùng bài
     * (feed dịch sau animation, [findLikeAreaNodeAt] có thể miss).
     */
    fun hasCommentBoxOnFeedItemNearLikeAt(
        root: AccessibilityNodeInfo,
        origRect: Rect,
        origId: String?
    ): Boolean {
        findLikeAreaNodeAt(root, origRect, origId)?.let { anchor ->
            if (hasCommentBoxOnFeedItemNearLike(anchor)) return true
        }
        return hasCommentBoxInRectBand(root, origRect)
    }

    private fun hasCommentBoxInRectBand(root: AccessibilityNodeInfo, origRect: Rect): Boolean {
        val band = Rect(
            (origRect.left - 80).coerceAtLeast(0),
            (origRect.top - 120).coerceAtLeast(0),
            origRect.right + 400,
            origRect.bottom + 480
        )
        for (footer in findFeedItemFooters(root)) {
            if (!footer.isVisibleToUser || !footer.hasValidScreenBounds()) continue
            val fr = Rect().also { footer.getBoundsInScreen(it) }
            if (!Rect.intersects(band, fr)) continue
            val scope = footer.parent ?: footer
            if (scanFeedItemScopeForCommentBox(scope, footer)) return true
            if (hasCommentRowBelowFooter(scope, footer)) return true
            if (hasCommentBoxOnFeedItemNearLike(footer)) return true
        }
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 1_400) {
            val n = stack.removeLast()
            visited++
            if (!n.isVisibleToUser || !n.hasValidScreenBounds()) continue
            val r = Rect().also { n.getBoundsInScreen(it) }
            if (!Rect.intersects(band, r)) {
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let { stack.addLast(it) }
                }
                continue
            }
            if (nodeLooksLikeFeedCommentBox(n)) return true
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return false
    }

    fun hasExpandedInlineCommentComposerNearLike(likeNode: AccessibilityNodeInfo): Boolean {
        val host = findFeedFooterHostNearLike(likeNode)
        findByViewId(host, "cmtinput_text").forEach { n ->
            if (n.isVisibleToUser && n.hasValidScreenBounds() && isExpandedCommentField(n)) return true
        }
        findByViewId(host, "keyboard_frame_layout").forEach { frame ->
            if (!frame.isVisibleToUser || !frame.hasValidScreenBounds()) return@forEach
            val fr = Rect().also { frame.getBoundsInScreen(it) }
            if (fr.height() >= 48) return true
        }
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(host)
        var visited = 0
        while (stack.isNotEmpty() && visited < 900) {
            val n = stack.removeLast()
            visited++
            if (n.isVisibleToUser && n.hasValidScreenBounds()) {
                if (n.isFocused &&
                    (isLikelyCommentInputNode(n) || isCommentPlaceholderNode(n))
                ) {
                    return true
                }
                if (isExpandedCommentField(n)) return true
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return false
    }

    private fun isExpandedCommentField(node: AccessibilityNodeInfo): Boolean {
        if (!isLikelyCommentInputNode(node)) return false
        if (node.isFocused) return true
        val id = node.viewIdResourceName?.lowercase().orEmpty()
        if (id.contains("cmtinput_text")) return true
        val typed = node.text?.toString()?.trim().orEmpty()
        if (typed.isNotEmpty()) {
            val low = typed.lowercase()
            if (!inlineCommentPhrases.any { low == it || low.startsWith(it) }) return true
        }
        val r = Rect().also { node.getBoundsInScreen(it) }
        return r.height() >= 80
    }

    fun shouldLike(node: AccessibilityNodeInfo): Boolean {
        // Tập trung logic phân biệt đã like ở isAlreadyLiked() — không duplicate ở đây.
        if (isAlreadyLiked(node)) return false

        if (hasCommentBoxOnFeedItemNearLike(node)) return false

        if (hasExpandedInlineCommentComposerNearLike(node)) return false

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
     * RecyclerView danh sách **tin nhắn / hội thoại** — không dùng cho Visit (tránh tap nhầm vào chat).
     */
    fun isMessageThreadRecycler(node: AccessibilityNodeInfo): Boolean {
        val id = node.viewIdResourceName?.lowercase().orEmpty()
        if (id.contains("msglist") || id.contains("msg_list")) return true
        if (id.contains("conversation") && id.contains("list")) return true
        return false
    }

    /**
     * Đang ở danh sách bạn bè (tab Bạn bè trong Danh bạ / Contacts) — sau [backToContacts] để xác nhận đã về đúng màn.
     * Không nhầm với chat hay profile (loại trừ qua marker).
     */
    fun isContactListScreen(root: AccessibilityNodeInfo): Boolean {
        if (hasChatScreenMarkers(root) || hasProfileScreenMarkers(root)) return false
        if (hasViewId(root, "main_chat_view")) return false
        val onDanhBa = findByViewId(root, "maintab_contact").isNotEmpty() ||
            screenContainsTextOrDesc(root, "Danh bạ", 1200) ||
            screenContainsTextOrDesc(root, "Contacts", 1200)
        // Danh bạ + list tin nhắn (recycler_view_msgList) khi chưa chọn "Bạn bè" — không phải danh sách visit.
        if (hasViewId(root, "recycler_view_msgList") && onDanhBa) {
            val tvFriends = findByViewId(root, "tv_friends")
            if (tvFriends.isNotEmpty()) {
                if (!tvFriends.any { it.isSelected || it.isChecked }) return false
            } else {
                val fst = findByViewId(root, "first_tab_item").firstOrNull()
                val sec = findByViewId(root, "second_tab_item").firstOrNull()
                if (fst != null && sec != null) {
                    if (fst.isSelected || fst.isChecked) return false
                    if (!(sec.isSelected || sec.isChecked)) return false
                } else {
                    return false
                }
            }
        }
        val friendsTabSelected = findByViewId(root, "tv_friends").any { node ->
            val label = node.text?.toString().orEmpty()
            (label.contains("Bạn bè", ignoreCase = true) || label.contains("Friends", ignoreCase = true)) &&
                (node.isSelected || node.isChecked)
        }
        if (friendsTabSelected) return true
        val hasFriendsSubTab = screenContainsTextOrDesc(root, "Bạn bè", 1200) ||
            screenContainsTextOrDesc(root, "Friends", 1200)
        if (onDanhBa && hasFriendsSubTab) return true
        return hasViewId(root, "tv_friends") && hasFriendsSubTab
    }

    private fun hasChatScreenMarkers(root: AccessibilityNodeInfo): Boolean =
        hasViewId(root, "main_chat_view") &&
            (hasViewId(root, "chatinput_text") || hasViewId(root, "chat_drawer_layout"))

    /** Khối profile đầy đủ — Zalo có thể vẫn giữ node chat trong cây khi mở profile/sheet. */
    private fun hasStrongProfileUi(root: AccessibilityNodeInfo): Boolean =
        hasViewId(root, "rl_profile_bio_container") ||
            hasViewId(root, "profile_bottom_functions_layout")

    /** Footer bài trên timeline profile (không phải ô nhập chat). */
    private fun hasProfileFeedFooter(root: AccessibilityNodeInfo): Boolean =
        hasViewId(root, "feedItemFooterBarModule")

    private fun hasProfileScreenMarkers(root: AccessibilityNodeInfo): Boolean =
        hasViewId(root, "rl_profile_bio_container") ||
            hasViewId(root, "profile_avatar") ||
            hasViewId(root, "profile_bottom_functions_layout") ||
            (hasViewId(root, "feedItemFooterBarModule") && hasViewId(root, "layoutSendMessage"))

    fun isChatScreen(root: AccessibilityNodeInfo): Boolean {
        if (hasStrongProfileUi(root)) return false
        if (hasChatScreenMarkers(root) && hasProfileFeedFooter(root)) return false
        if (hasProfileScreenMarkers(root)) return false
        if (hasChatScreenMarkers(root)) return true
        if (!hasViewId(root, "chatinput_text")) return false
        if (isContactListScreen(root)) return false
        return hasViewId(root, "zalo_action_bar")
    }

    fun isProfileScreen(root: AccessibilityNodeInfo): Boolean {
        if (hasStrongProfileUi(root)) return true
        if (hasChatScreenMarkers(root)) {
            if (hasProfileFeedFooter(root)) return true
            return false
        }
        if (hasProfileScreenMarkers(root)) return true
        // Samsung/Zalo đổi id — vẫn là profile nếu có avatar, không còn ô chat
        return hasViewId(root, "profile_avatar") && !hasViewId(root, "chatinput_text")
    }

    /** Profile đang (hoặc sắp) có timeline bài — có footer feed hoặc nút Thích. */
    fun hasProfileTimelineContent(root: AccessibilityNodeInfo): Boolean =
        hasViewId(root, "feedItemFooterBarModule") || findProfileLikeButtons(root).isNotEmpty()

    /** Tab timeline trên profile (Bài viết / Nhật ký…) — cần tap nếu đang tab Ảnh/Giới thiệu. */
    fun findProfilePostsTabTapTarget(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val phrases = listOf(
            "bài viết", "posts", "nhật ký", "dòng thời gian", "timeline", "hoạt động"
        )
        for (phrase in phrases) {
            findNodeWithTextOrDesc(root, phrase, maxNodes = 2500)?.let { node ->
                if (node.isSelected || node.isChecked) return null
                findClickableAncestor(node)?.let { return it }
                if (node.isClickable) return node
            }
        }
        return null
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

    /**
     * Chat → profile: tap vùng action bar giữa (actionbar_txtTitle / zds_action_bar / …).
     *
     * Trả về **null** khi:
     * - Không có target kiểu chat **và** không phải profile → caller coi **lỗi** (cần màn khác).
     * - Đã **đang ở profile** (mở thẳng từ danh bạ, `zalo_action_bar`… không có bar chat) → **không cần tap**;
     *   [isProfileScreen] = true — script `tapProfileEntry` coi step thành công.
     */
    fun findProfileEntryNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val likelyChat = hasViewId(root, "chatinput_text") || hasViewId(root, "main_chat_view")

        if (likelyChat) {
            // Chat 1–1: KHÔNG tap tây cả zds_action_bar (0–209) — tâm Y hay rơi phía trên hàng tiêu đề → không mở profile (kẹt An Mit…).
            findByViewId(root, "actionbar_middle_container").firstOrNull()?.let { mid ->
                if (mid.hasValidScreenBounds()) {
                    logger.log(LogTag.SCAN, "findProfileEntryNode", "CHAT_TAP_MIDDLE_CONTAINER")
                    return mid
                }
            }
            findByViewId(root, "actionbar_txtTitle").firstOrNull()?.let { t ->
                val txt = t.text?.toString()?.trim().orEmpty()
                if (txt.isNotEmpty() && txt.length <= 96 &&
                    !txt.equals("tìm kiếm", ignoreCase = true) &&
                    !txt.equals("search", ignoreCase = true)
                ) {
                    findClickableAncestorForProfileTitleBar(t)?.let { return it }
                    if (t.isClickable && t.hasValidScreenBounds()) return t
                    if (t.hasValidScreenBounds()) return t
                }
            }
            findByViewId(root, "action_bar_title").firstOrNull()?.let { t ->
                val txt = t.text?.toString()?.trim().orEmpty()
                if (txt.isNotEmpty() && txt.length <= 96 &&
                    !txt.equals("tìm kiếm", ignoreCase = true) &&
                    !txt.equals("search", ignoreCase = true)
                ) {
                    findClickableAncestorForProfileTitleBar(t)?.let { return it }
                    if (t.isClickable && t.hasValidScreenBounds()) return t
                    if (t.hasValidScreenBounds()) return t
                }
            }
            findByViewId(root, "zalo_action_bar").firstOrNull()?.let { bar ->
                findWideClickableInActionBarForProfileEntry(bar)?.let { return it }
            }
            findByViewId(root, "zds_action_bar").firstOrNull { it.isClickable }?.let { return it }
        }

        findByViewId(root, "zds_action_bar").firstOrNull { it.isClickable }?.let { return it }
        findByViewId(root, "actionbar_middle_container").firstOrNull()?.let { middle ->
            var p: AccessibilityNodeInfo? = middle
            repeat(8) {
                if (p == null) return@repeat
                val pid = p.viewIdResourceName?.lowercase().orEmpty()
                if (pid.contains("zds_action_bar")) {
                    p = p.parent
                    return@repeat
                }
                if (p.isClickable && p.hasValidScreenBounds()) return p
                p = p.parent
            }
            return middle
        }
        findByViewId(root, "actionbar_txtTitle").firstOrNull()?.let { title ->
            return findClickableAncestorForProfileTitleBar(title) ?: title
        }
        if (isProfileScreen(root)) {
            logger.log(LogTag.SCAN, "findProfileEntryNode", "ALREADY_ON_PROFILE_NO_CHAT_BAR")
        }
        return null
    }

    /** Vùng giữa action bar (đầu chat) — tránh nút tìm kiếm / back. */
    private fun findWideClickableInActionBarForProfileEntry(bar: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val skipSuffixes = listOf(
            "action_bar_search_btn",
            "action_back",
            "btn_back",
            "navigation_icon",
            "chat_menu",
            "menu_button"
        )
        var best: AccessibilityNodeInfo? = null
        var bestW = 0
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.addLast(bar)
        var visited = 0
        while (q.isNotEmpty() && visited < 80) {
            val n = q.removeFirst()
            visited++
            val id = n.viewIdResourceName?.lowercase().orEmpty()
            if (skipSuffixes.any { id.endsWith(it) }) {
                for (i in 0 until n.childCount) n.getChild(i)?.let { q.addLast(it) }
                continue
            }
            if (n.isClickable && n.hasValidScreenBounds()) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                if (r.width() in 180..950 && r.height() in 36..220 && r.width() > bestW) {
                    bestW = r.width()
                    best = n
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.addLast(it) }
        }
        return best
    }

    /** Các footer bài trên feed / profile timeline. */
    fun findFeedItemFooters(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        findByViewId(root, "feedItemFooterBarModule").filter { it.isVisibleToUser && it.hasValidScreenBounds() }

    private fun footerAggregatedTextHasLikeAction(footer: AccessibilityNodeInfo): Boolean {
        val hay = buildString {
            append(footer.text?.toString().orEmpty())
            append(' ')
            append(footer.contentDescription?.toString().orEmpty())
        }.lowercase()
        if (hay.contains("đã thích") || hay.contains("liked")) return false
        return hay.contains(ZaloIDStore.TEXT_LIKE.lowercase())
    }

    /** Tap vùng dòng «Thích» trên footer text gộp (dump: childCount=0). */
    fun likeTapRectForAggregatedFooter(footer: AccessibilityNodeInfo): Rect? {
        if (!footer.hasValidScreenBounds()) return null
        val r = Rect().also { footer.getBoundsInScreen(it) }
        if (r.height() < 40) return null
        val bandTop = r.top + (r.height() * 0.55f).toInt()
        return Rect(r.left + 24, bandTop, r.right - 24, r.bottom - 8)
    }

    /** Like trên timeline profile — quét trong feedItemFooterBarModule trước; không dùng ID học từ feed. */
    fun findProfileLikeButtons(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val seen = LinkedHashSet<String>()
        fun addTarget(n: AccessibilityNodeInfo) {
            val r = Rect().also { n.getBoundsInScreen(it) }
            val key = "${r.centerX()}_${r.centerY()}_${n.viewIdResourceName ?: ""}"
            if (seen.add(key)) result.add(n)
        }
        for (footer in findFeedItemFooters(root)) {
            val inFooter = findLikeButtonsWithoutLearnedId(footer)
            if (inFooter.isNotEmpty()) {
                inFooter.forEach { addTarget(it) }
                continue
            }
            if (footerAggregatedTextHasLikeAction(footer) && !hasCommentBoxOnFeedItemNearLike(footer)) {
                addTarget(footer)
            }
        }
        if (result.isNotEmpty()) return result
        findLikeButtonsWithoutLearnedId(root).forEach { addTarget(it) }
        return result
    }

    data class FeedCommentPostAnchor(
        val footer: AccessibilityNodeInfo,
        val like: AccessibilityNodeInfo?
    )

    /**
     * Bài trên feed để COMMENT_ONLY — sắp trên → dưới; gồm bài đã thích.
     */
    fun findFeedCommentPostAnchors(root: AccessibilityNodeInfo): List<FeedCommentPostAnchor> {
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<FeedCommentPostAnchor>()
        fun boundsKey(n: AccessibilityNodeInfo): String {
            val r = Rect().also { n.getBoundsInScreen(it) }
            return "${r.left}_${r.top}_${r.right}_${r.bottom}"
        }
        fun add(footer: AccessibilityNodeInfo, like: AccessibilityNodeInfo?) {
            if (!footer.isVisibleToUser || !footer.hasValidScreenBounds()) return
            val r = Rect().also { footer.getBoundsInScreen(it) }
            if (r.width() < 40 || r.height() < 20) return
            val key = boundsKey(footer)
            if (seen.add(key)) out.add(FeedCommentPostAnchor(footer, like))
        }
        findFeedItemFooters(root).forEach { add(it, null) }
        for (like in findLikeAnchorsForFeedComment(root)) {
            add(findFeedFooterHostNearLike(like), like)
        }
        val commentIdSuffixes = listOf(
            "ui_feed_item_footer_comment_btn",
            "btn_comment",
            "ui_feed_item_footer_comment"
        )
        for (suffix in commentIdSuffixes) {
            findByViewId(root, suffix).forEach { n ->
                add(findFeedFooterHostNearLike(n), null)
            }
        }
        if (out.isNotEmpty()) {
            logger.log(LogTag.SCAN, "count=${out.size}", "FEED_COMMENT_POST_ANCHORS")
        } else {
            logger.log(LogTag.SCAN, "feed", "FEED_COMMENT_POST_ANCHORS_EMPTY")
        }
        return out.sortedBy { anchor ->
            Rect().also { anchor.footer.getBoundsInScreen(it) }.top
        }
    }

    /** @see findFeedCommentPostAnchors */
    fun findFeedCommentAnchors(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        findFeedCommentPostAnchors(root).map { it.footer }

    /**
     * Footer bài feed chứa [origRect] (MIX sau like) — SPEC comment neo footer, không neo Thích.
     */
    fun findFeedFooterAt(
        root: AccessibilityNodeInfo,
        origRect: Rect,
        origId: String?
    ): AccessibilityNodeInfo? {
        val band = Rect(
            (origRect.left - 120).coerceAtLeast(0),
            (origRect.top - 400).coerceAtLeast(0),
            origRect.right + 120,
            origRect.bottom + 120
        )
        var best: AccessibilityNodeInfo? = null
        var bestOverlap = 0
        for (footer in findFeedItemFooters(root)) {
            val fr = Rect().also { footer.getBoundsInScreen(it) }
            val overlap = Rect.intersects(band, fr)
            if (!overlap) continue
            val intersect = Rect(fr).apply { intersect(band) }
            val area = intersect.width().coerceAtLeast(0) * intersect.height().coerceAtLeast(0)
            if (area > bestOverlap) {
                bestOverlap = area
                best = footer
            }
        }
        if (best != null) return best
        findLikeAreaNodeAt(root, origRect, origId)?.let { like ->
            return findFeedFooterHostNearLike(like)
        }
        return null
    }

    /** Chat sau tap contact — thẻ «Bắt đầu chia sẻ…» / vùng có thể tap mở profile. */
    fun findChatIntroProfileTapTarget(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (!hasViewId(root, "chatinput_text") && !hasViewId(root, "main_chat_view")) return null
        val phrases = listOf(
            "bắt đầu chia sẻ",
            "start sharing",
            "interesting stories"
        )
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 1600) {
            val n = stack.removeLast()
            visited++
            if (!n.hasValidScreenBounds()) continue
            val t = n.text?.toString()?.trim().orEmpty()
            val low = t.lowercase()
            if (t.length in 12..200 && phrases.any { low.contains(it) }) {
                if (n.isClickable) return n
                var p: AccessibilityNodeInfo? = n
                repeat(6) {
                    val parent = p?.parent ?: return@repeat
                    if (parent.isClickable && parent.hasValidScreenBounds()) return parent
                    p = parent
                }
                return n
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return null
    }

    fun isProfileTimelineReady(root: AccessibilityNodeInfo): Boolean {
        if (isChatScreen(root) && !hasProfileFeedFooter(root)) return false
        return isProfileScreen(root) &&
            (hasProfileTimelineContent(root) || hasViewId(root, "profile_avatar"))
    }

    fun isLikeControlNode(node: AccessibilityNodeInfo): Boolean {
        if (LikeViewIdRules.isWhitelistedLikeResourceId(node.viewIdResourceName)) return true
        val t = node.text?.toString()?.trim().orEmpty()
        if (t == ZaloIDStore.TEXT_LIKE || t == ZaloIDStore.TEXT_LIKED) return true
        return false
    }

    fun isCommentControlNode(node: AccessibilityNodeInfo): Boolean {
        if (isLikeControlNode(node)) return false
        val id = node.viewIdResourceName.orEmpty().lowercase()
        if (id.contains("comment") && !id.contains("like") && !id.contains("send")) return true
        val t = "${node.text} ${node.contentDescription}".lowercase()
        return t.contains("bình luận") || t == "comment"
    }

    /** Chỉ vùng nút Thích trên footer — không bọc cả footer (comment icon nằm cạnh). */
    fun likeButtonRectInFooter(footer: AccessibilityNodeInfo): Rect? {
        for (suffix in listOf(
            "ui_feed_item_footer_like_btn",
            "btn_like_text",
            "btn_like_icon",
            "btn_like"
        )) {
            for (n in findByViewId(footer, suffix)) {
                if (!n.hasValidScreenBounds()) continue
                val r = Rect().also { n.getBoundsInScreen(it) }
                if (r.width() >= 24 && r.height() >= 24) return r
            }
        }
        for (like in findLikeButtonsWithoutLearnedId(footer)) {
            if (!like.hasValidScreenBounds()) continue
            return Rect().also { like.getBoundsInScreen(it) }
        }
        return null
    }

    fun nodeOverlapsRect(node: AccessibilityNodeInfo, zone: Rect, marginPx: Int = 16): Boolean {
        val r = Rect().also { node.getBoundsInScreen(it) }
        if (r.isEmpty) return false
        val z = Rect(zone).apply { inset(-marginPx, -marginPx) }
        return Rect.intersects(z, r)
    }

    private fun findFeedFooterHostNearLike(likeNode: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var walk: AccessibilityNodeInfo? = likeNode
        repeat(14) {
            val w = walk ?: return@repeat
            val id = w.viewIdResourceName.orEmpty().lowercase()
            if (id.contains("feeditemfooterbarmodule")) return w
            walk = w.parent
        }
        return likeNode.parent ?: likeNode
    }

    /**
     * Nút Thích và icon Bình luận quá sát — gesture/click dễ mở comment thay vì like.
     */
    fun isLikeTapLikelyToOpenComment(likeNode: AccessibilityNodeInfo): Boolean {
        val likeRect = Rect().also { likeNode.getBoundsInScreen(it) }
        if (likeRect.isEmpty) return false
        val commentBtn = findCommentButton(likeNode) ?: return false
        val cRect = Rect().also { commentBtn.getBoundsInScreen(it) }
        if (cRect.isEmpty) return false
        val gap = cRect.left - likeRect.right
        if (gap in -28..36) return true
        val expanded = Rect(likeRect).apply { inset(-20, -24) }
        return Rect.intersects(expanded, cRect)
    }

    sealed class FeedCommentTapTarget {
        data class Node(val node: AccessibilityNodeInfo) : FeedCommentTapTarget()
        data class Area(val rect: Rect, val reason: String) : FeedCommentTapTarget()
    }

    private fun isFeedFooterModule(node: AccessibilityNodeInfo): Boolean =
        node.viewIdResourceName.orEmpty().lowercase().contains("feeditemfooterbarmodule")

    /** Feed COMMENT_ONLY / MIX: nút hoặc vùng tap mở bình luận (kể cả footer text gộp). */
    fun findFeedCommentTapTarget(anchor: AccessibilityNodeInfo): FeedCommentTapTarget? {
        val footer = findFeedFooterHostNearLike(anchor)
        val likeRect = if (!isFeedFooterModule(anchor)) {
            Rect().also { anchor.getBoundsInScreen(it) }
        } else {
            null
        }

        val learnedId = idStore.getCommentButtonID()
        if (!learnedId.isNullOrBlank()) {
            for (n in findByViewId(footer, learnedId)) {
                resolveCommentTapNode(n)?.let { return FeedCommentTapTarget.Node(it) }
            }
        }

        val idSuffixes = listOf(
            "ui_feed_item_footer_comment_btn",
            "ui_feed_item_footer_comment",
            "btn_comment",
            "comment_btn",
            "btnComment",
            "feed_comment_btn",
            "img_comment",
            "btn_comment_text"
        )
        for (suffix in idSuffixes) {
            for (n in findByViewId(footer, suffix)) {
                resolveCommentTapNode(n)?.let { return FeedCommentTapTarget.Node(it) }
            }
        }

        findCommentIconInFooter(footer)?.let { return FeedCommentTapTarget.Node(it) }

        footer.let { bar ->
            findClickableWithPhrase(bar, "bình luận")?.let { btn ->
                if (isValidCommentTapNode(btn, likeRect)) return FeedCommentTapTarget.Node(btn)
            }
            findClickableWithPhrase(bar, "comment")?.let { btn ->
                if (isValidCommentTapNode(btn, likeRect)) return FeedCommentTapTarget.Node(btn)
            }
        }

        if (!isFeedFooterModule(anchor) && likeRect != null) {
            val parent = anchor.parent
            if (parent != null) {
                for (i in 0 until parent.childCount) {
                    val sib = parent.getChild(i) ?: continue
                    if (sib == anchor) continue
                    val clickable = if (sib.isClickable) sib else findClickableAncestor(sib) ?: continue
                    if (isLikeControlNode(clickable)) continue
                    val r = Rect().also { clickable.getBoundsInScreen(it) }
                    // Icon Bình luận thường bên TRÁI Thích — bỏ sibling nằm chủ yếu bên phải Like
                    if (r.left >= likeRect.left - 12) continue
                    if (isCommentControlNode(clickable) ||
                        r.right <= likeRect.left + 40
                    ) {
                        return FeedCommentTapTarget.Node(clickable)
                    }
                }
            }
        }

        commentTapRectForAggregatedFooter(footer)?.let { rect ->
            return FeedCommentTapTarget.Area(rect, "aggregated_footer")
        }
        return null
    }

    fun findCommentButton(likeNode: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        (findFeedCommentTapTarget(likeNode) as? FeedCommentTapTarget.Node)?.node

    private fun resolveCommentTapNode(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (!n.isVisibleToUser || !n.hasValidScreenBounds()) return null
        if (isLikeControlNode(n)) return null
        if (n.isClickable) return n
        return findClickableAncestor(n)?.takeIf { !isLikeControlNode(it) }
    }

    private fun isValidCommentTapNode(btn: AccessibilityNodeInfo, likeRect: Rect?): Boolean {
        if (isLikeControlNode(btn)) return false
        if (likeRect == null) return true
        if (isFeedFooterModule(btn)) return false
        return !nodeOverlapsRect(btn, likeRect, 24)
    }

    /** Icon / nút bình luận trong footer (thường nửa trái, cạnh Thích). */
    private fun findCommentIconInFooter(footer: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (!footer.hasValidScreenBounds()) return null
        val footerRect = Rect().also { footer.getBoundsInScreen(it) }
        val midX = footerRect.centerX()
        var best: AccessibilityNodeInfo? = null
        var bestDist = Int.MAX_VALUE
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(footer)
        var visited = 0
        while (stack.isNotEmpty() && visited < 100) {
            val n = stack.removeLast()
            visited++
            if (!n.isVisibleToUser || !n.hasValidScreenBounds()) {
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let { stack.addLast(it) }
                }
                continue
            }
            val clickable = if (n.isClickable) n else findClickableAncestor(n)
            if (clickable != null && !isLikeControlNode(clickable)) {
                val cr = Rect().also { clickable.getBoundsInScreen(it) }
                val idLow = n.viewIdResourceName.orEmpty().lowercase()
                val cls = n.className?.toString()?.lowercase().orEmpty()
                val t = "${n.text} ${n.contentDescription}".lowercase()
                val looksComment = idLow.contains("comment") ||
                    t.contains("bình luận") ||
                    t.contains("comment") ||
                    (cls.contains("imageview") && idLow.contains("comment"))
                if (looksComment && cr.centerX() <= midX + 80) {
                    val dist = kotlin.math.abs(cr.centerX() - footerRect.left)
                    if (dist < bestDist) {
                        bestDist = dist
                        best = clickable
                    }
                }
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return best
    }

    /**
     * Feed: icon Bình luận **bên trái** nút Thích — tọa độ cố định từ bounds Thích (luôn dùng gesture).
     */
    fun feedCommentIconTapRect(
        like: AccessibilityNodeInfo?,
        footer: AccessibilityNodeInfo?
    ): Rect? {
        val footerRect = footer?.takeIf { it.hasValidScreenBounds() }?.let {
            Rect().also { r -> it.getBoundsInScreen(r) }
        }
        val likeRect = when {
            like != null && like.hasValidScreenBounds() ->
                Rect().also { like.getBoundsInScreen(it) }
            footerRect != null -> likeButtonRectInFooter(footer!!)
            else -> null
        }
        footer?.let { commentTapRectForAggregatedFooter(it) }?.let { return it }
        if (likeRect != null && likeRect.width() > 0 && likeRect.height() > 0 && footerRect != null) {
            val fr = footerRect
            val bandTop = fr.top + (fr.height() * 0.2f).toInt()
            val bandBottom = fr.bottom - 8
            val tapY = likeRect.centerY().coerceIn(bandTop, bandBottom)
            val tapX = (likeRect.left - 44).coerceIn(fr.left + 12, fr.right - 56)
            return Rect(tapX - 28, tapY - 28, tapX + 28, tapY + 28)
        }
        return footer?.let { commentTapRectForAggregatedFooter(it) }
    }

    /** Vùng tap icon/text Bình luận trên footer (tránh vùng Thích bên phải). */
    fun commentTapRectForAggregatedFooter(footer: AccessibilityNodeInfo): Rect? {
        if (!footer.hasValidScreenBounds()) return null
        val r = Rect().also { footer.getBoundsInScreen(it) }
        if (r.height() < 28) return null
        var commentRight = r.left + (r.width() * 0.44f).toInt()
        for (suffix in listOf(
            "ui_feed_item_footer_like_btn",
            "btn_like_text",
            "btn_like_icon",
            "btn_like"
        )) {
            findByViewId(footer, suffix).forEach { like ->
                if (!like.hasValidScreenBounds()) return@forEach
                val lr = Rect().also { like.getBoundsInScreen(it) }
                commentRight = minOf(commentRight, lr.left - 10)
            }
        }
        if (commentRight <= r.left + 48) {
            commentRight = r.left + (r.width() * 0.44f).toInt()
        }
        val top = r.top + (r.height() * 0.12f).toInt()
        val bottom = r.bottom - (r.height() * 0.08f).toInt().coerceAtLeast(top + 24)
        return Rect(r.left + 12, top, commentRight, bottom)
    }

    /** Luôn có vùng tap khi đã neo được footer (tránh chỉ cuộn vì findFeedCommentTapTarget null). */
    fun resolveFeedCommentTapTarget(footer: AccessibilityNodeInfo): FeedCommentTapTarget? {
        findFeedCommentTapTarget(footer)?.let { return it }
        commentTapRectForAggregatedFooter(footer)?.let { rect ->
            return FeedCommentTapTarget.Area(rect, "footer_comment_rect")
        }
        val r = Rect().also { footer.getBoundsInScreen(it) }
        if (r.width() > 72 && r.height() > 24) {
            return FeedCommentTapTarget.Area(
                Rect(
                    r.left + 14,
                    r.centerY() - 36,
                    r.left + r.width() / 2,
                    r.centerY() + 36
                ),
                "footer_left_half"
            )
        }
        return null
    }

    /** Ô nhập bình luận — sheet feed (`bottom_sheet_container`) hoặc inline dưới bài. */
    fun findCommentInput(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        findByViewId(root, "feed_comment_input_container").forEach { container ->
            findCommentInputInScope(container)?.let { return it }
        }
        findByViewId(root, "cmtinput_text").firstOrNull { isLikelyCommentInputNode(it) }?.let { return it }
        commentInputSheetScopes(root).forEach { scope ->
            findCommentInputInScope(scope)?.let { return it }
        }
        for (phrase in inlineCommentPhrases) {
            findNodeWithHint(root, phrase, 2500)?.let { node ->
                if (isLikelyCommentInputNode(node) || isCommentPlaceholderNode(node)) return node
            }
        }
        findEditableCommentField(root, maxNodes = 2800, preferBottomHalf = true)?.let { return it }
        if (isCommentBottomSheetOverFeed(root)) {
            findCommentComposerFocusTargetInSheet(root)?.let { return it }
        }
        return null
    }

    /**
     * Sheet bình luận feed (dump: `keyboard_frame_layout` + `main_comment_view` nhưng không có `cmtinput_text`
     * trong cây cho đến khi tap vùng composer).
     */
    fun findCommentComposerFocusTargetInSheet(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (scopeId in listOf("keyboard_frame_layout", "bottom_sheet_container", "main_comment_view")) {
            for (scope in findByViewId(root, scopeId)) {
                findCommentInputInScope(scope)?.let { return it }
                findEditableCommentField(scope, maxNodes = 2400, preferBottomHalf = false)?.let { return it }
                findBottomComposerTapTarget(scope)?.let { return it }
            }
        }
        return null
    }

    /** Vùng tap dưới sheet khi không thấy node nhập — theo dump Zalo (h≈10% đáy `keyboard_frame_layout`). */
    fun getCommentSheetComposerTapRect(root: AccessibilityNodeInfo): Rect? {
        val frame = findByViewId(root, "keyboard_frame_layout").firstOrNull()
            ?: findByViewId(root, "bottom_sheet_container").firstOrNull()
            ?: return null
        val r = Rect().also { frame.getBoundsInScreen(it) }
        if (r.isEmpty || r.width() <= 0 || r.height() <= 0) return null
        val tapY = (r.bottom - r.height() * 0.10f).toInt().coerceIn(r.top, r.bottom)
        val marginX = (r.width() * 0.18f).toInt().coerceAtLeast(48)
        return Rect(
            r.left + marginX,
            (tapY - 48).coerceAtLeast(r.top),
            r.right - marginX,
            (tapY + 48).coerceAtMost(r.bottom)
        )
    }

    private fun findBottomComposerTapTarget(scope: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val scopeRect = Rect().also { scope.getBoundsInScreen(it) }
        if (scopeRect.isEmpty) return null
        val barTop = scopeRect.top + (scopeRect.height() * 0.68f).toInt()
        var best: AccessibilityNodeInfo? = null
        var bestBottom = Int.MIN_VALUE
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(scope)
        var visited = 0
        while (stack.isNotEmpty() && visited < 1800) {
            val n = stack.removeLast()
            visited++
            if (n.hasValidScreenBounds() && (n.isClickable || n.isFocusable || isLikelyCommentInputNode(n))) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                if (r.bottom >= barTop && !isLikeControlNode(n)) {
                    if (r.bottom > bestBottom) {
                        bestBottom = r.bottom
                        best = n
                    }
                }
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return best
    }

    /** Ô nhập gần nút Thích (inline trên feed) — tránh mở sheet khi đã có composer dưới bài. */
    fun findCommentInputNearLike(likeNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val host = findFeedFooterHostNearLike(likeNode)
        findCommentInputInScope(host)?.let { return it }
        for (phrase in inlineCommentPhrases) {
            findNodeWithHint(host, phrase, 1200)?.let { node ->
                if (isLikelyCommentInputNode(node) || isCommentPlaceholderNode(node)) return node
            }
        }
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(host)
        var visited = 0
        while (stack.isNotEmpty() && visited < 1100) {
            val n = stack.removeLast()
            visited++
            if (isCommentPlaceholderNode(n) || isLikelyCommentInputNode(n)) return n
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return findEditableCommentField(host, maxNodes = 900, preferBottomHalf = false)
    }

    fun findCommentInputPlaceholderNearLike(likeNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val host = findFeedFooterHostNearLike(likeNode)
        val likeRect = Rect().also { likeNode.getBoundsInScreen(it) }
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(host)
        var visited = 0
        while (stack.isNotEmpty() && visited < 1100) {
            val n = stack.removeLast()
            visited++
            if (isCommentPlaceholderNode(n) && !isLikeControlNode(n) && !nodeOverlapsRect(n, likeRect, 20)) {
                return n
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return null
    }

    /** Placeholder "Nhập bình luận" (clickable) — tap để hiện `cmtinput_text` / bàn phím. */
    fun findCommentInputPlaceholder(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val scopes = commentInputSheetScopes(root).ifEmpty { listOf(root) }
        for (scope in scopes) {
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.addLast(scope)
            var visited = 0
            while (stack.isNotEmpty() && visited < 1400) {
                val n = stack.removeLast()
                visited++
                if (isCommentPlaceholderNode(n)) {
                    resolveClickableTapTarget(n)?.let { return it }
                    if (n.isClickable && n.hasValidScreenBounds()) return n
                }
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let { stack.addLast(it) }
                }
            }
        }
        return null
    }

    fun findCommentSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        findByViewId(root, "cmtinput_send").firstOrNull { it.isClickable && it.isEnabled }?.let { return it }
        findByViewId(root, "cmtinput_send").firstOrNull()?.let { return it }
        findByViewId(root, "btn_send_comment").firstOrNull { it.isClickable }?.let { return it }
        val scopes = commentInputSheetScopes(root).ifEmpty { listOf(root) }
        for (scope in scopes) {
            findNodeWithTextOrDesc(scope, "Gửi", maxNodes = 900)?.let { node ->
                resolveClickableTapTarget(node)?.let { return it }
            }
            findNodeWithTextOrDesc(scope, "Send", maxNodes = 600)?.let { node ->
                resolveClickableTapTarget(node)?.let { return it }
            }
        }
        return findBottomRightSendIcon(root)
    }

    private fun commentInputSheetScopes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = LinkedHashSet<AccessibilityNodeInfo>()
        for (suffix in listOf(
            "bottom_sheet_container",
            "fl_bottom_container",
            "keyboard_frame_layout"
        )) {
            findByViewId(root, suffix).forEach { out.add(it) }
        }
        findByViewId(root, "main_comment_view").forEach { mc ->
            val r = Rect().also { mc.getBoundsInScreen(it) }
            val rootR = Rect().also { root.getBoundsInScreen(it) }
            if (r.top > rootR.top + rootR.height() / 4) out.add(mc)
        }
        return out.toList()
    }

    /** Ô nhập / placeholder chỉ trong footer một bài — không quét cả feed. */
    fun findCommentInputInFooter(footer: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val host = findFeedFooterHostNearLike(footer)
        findCommentInputInScope(host)?.let { return it }
        findCommentInputPlaceholderNearLike(footer)?.let { return it }
        return null
    }

    fun nodeCenterIsAboveFeedFooter(node: AccessibilityNodeInfo, footer: AccessibilityNodeInfo): Boolean {
        if (!node.hasValidScreenBounds() || !footer.hasValidScreenBounds()) return false
        val fr = Rect().also { footer.getBoundsInScreen(it) }
        val nr = Rect().also { node.getBoundsInScreen(it) }
        return nr.centerY() < fr.top - 10
    }

    private fun findCommentInputInScope(scope: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        findByViewId(scope, "cmtinput_text").firstOrNull { isLikelyCommentInputNode(it) }?.let { return it }
        for (suffix in listOf("cmtinput_text", "edt_comment", "comment_input_text", "input_comment")) {
            findByViewId(scope, suffix).firstOrNull { isLikelyCommentInputNode(it) }?.let { return it }
        }
        for (phrase in inlineCommentPhrases) {
            findNodeWithHint(scope, phrase, 1000)?.let { node ->
                if (isLikelyCommentInputNode(node)) return node
            }
        }
        return findEditableCommentField(scope, maxNodes = 1200, preferBottomHalf = true)
    }

    private fun isLikelyCommentInputNode(node: AccessibilityNodeInfo): Boolean {
        if (!node.isEnabled || !node.hasValidScreenBounds()) return false
        val cls = node.className?.toString()?.lowercase().orEmpty()
        if (cls.contains("edittext")) return true
        if (Build.VERSION.SDK_INT >= 18 && node.isEditable) return true
        val id = node.viewIdResourceName?.lowercase().orEmpty()
        if (id.contains("cmtinput") && !id.contains("send")) return true
        if (id.contains("comment") && id.contains("input") && !id.contains("send")) return true
        return false
    }

    private fun isCommentPlaceholderNode(node: AccessibilityNodeInfo): Boolean {
        if (!node.isEnabled || !node.hasValidScreenBounds()) return false
        if (isLikeControlNode(node)) return false
        if (isLikelyCommentInputNode(node)) return true
        val fields = listOf(
            node.hintText?.toString(),
            node.text?.toString(),
            node.contentDescription?.toString()
        )
        for (raw in fields) {
            val low = raw?.trim()?.lowercase().orEmpty()
            if (low.isEmpty()) continue
            if (node.viewIdResourceName.orEmpty().lowercase().contains("chatinput")) continue
            if (inlineCommentPhrases.any { low.contains(it) }) return true
        }
        return false
    }

    private fun findEditableCommentField(
        root: AccessibilityNodeInfo,
        maxNodes: Int,
        preferBottomHalf: Boolean
    ): AccessibilityNodeInfo? {
        val rootRect = Rect().also { root.getBoundsInScreen(it) }
        val minTop = if (preferBottomHalf) rootRect.top + rootRect.height() / 3 else rootRect.top
        var best: AccessibilityNodeInfo? = null
        var bestBottom = Int.MIN_VALUE
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < maxNodes) {
            val n = stack.removeLast()
            visited++
            if (isLikelyCommentInputNode(n)) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                if (r.top >= minTop && r.bottom > bestBottom) {
                    bestBottom = r.bottom
                    best = n
                }
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return best
    }

    private fun findBottomRightSendIcon(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val rootRect = Rect().also { root.getBoundsInScreen(it) }
        val minY = rootRect.top + (rootRect.height() * 0.55f).toInt()
        var best: AccessibilityNodeInfo? = null
        var bestRight = Int.MIN_VALUE
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 1600) {
            val n = stack.removeLast()
            visited++
            if (n.isClickable && n.isEnabled && n.hasValidScreenBounds()) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                if (r.bottom >= minY && r.right > bestRight && r.width() in 36..220 && r.height() in 36..220) {
                    val id = n.viewIdResourceName?.lowercase().orEmpty()
                    val label = listOf(n.text, n.contentDescription)
                        .mapNotNull { it?.toString()?.trim()?.lowercase() }
                        .joinToString(" ")
                    if (id.contains("send") || label == "gửi" || label == "send") {
                        bestRight = r.right
                        best = n
                    }
                }
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return best
    }

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
        var listId = idStore.getContactListID()
        if (!listId.isNullOrBlank() && isBadLearnedContactListId(listId)) {
            idStore.clearContactListID()
            listId = null
        }
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

    private fun isBadLearnedContactListId(fullId: String): Boolean {
        val low = fullId.lowercase()
        return low.contains("msglist") || low.contains("msg_list")
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
        if (isMessageThreadRecycler(recycler)) return
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
                if (!isMessageThreadRecycler(n)) {
                    collectContactRowsFromRecycler(n, tabBottom, navTop, skipPhrases, out)
                }
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
        if (low.contains("bạn:") || low.contains("ban:")) return true
        if (low.contains("[file]") || low.contains("[ảnh]") || low.contains("[photo]")) return true
        if (Regex("""\d+\s*(phút|giây|giờ|min|mins|hour|hours)\b""").containsMatchIn(low)) return true
        if (low.contains("hôm qua") || low.contains("yesterday")) return true
        if (Regex("""(^|\n)\s*T[2-7]\b""").containsMatchIn(low)) return true
        if (low.contains("[sticker]")) return true
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

    /**
     * Tương tự [findClickableAncestor] nhưng bỏ qua `zds_action_bar` — tap đó không mở profile (full width).
     */
    private fun findClickableAncestorForProfileTitleBar(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var p: AccessibilityNodeInfo? = node.parent
        repeat(8) {
            if (p == null) return null
            val id = p.viewIdResourceName?.lowercase().orEmpty()
            if (id.contains("zds_action_bar")) {
                p = p.parent
                return@repeat
            }
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

    /**
     * Cách nhận ô BL đã chạy ổn (77ecf09): leo ~6 cấp parent từ nút Thích, quét subtree — không chỉ footer module.
     */
    fun hasInlineCommentComposerNearLikeAnchor(likeNode: AccessibilityNodeInfo): Boolean {
        if (findCommentInputNearLike(likeNode) != null) return true
        if (findCommentInputPlaceholderNearLike(likeNode) != null) return true
        fun matchesPhrase(s: String?): Boolean {
            val t = s?.trim()?.lowercase().orEmpty()
            if (t.isEmpty()) return false
            return inlineCommentPhrases.any { t.contains(it) }
        }
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
            if (matchesPhrase(n.text?.toString()) ||
                matchesPhrase(n.contentDescription?.toString()) ||
                matchesPhrase(n.hintText?.toString())
            ) {
                return true
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2 &&
                n.isEditable &&
                !isLikeControlNode(n)
            ) {
                return true
            }
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

    fun resolveClickableTapTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findClickableAncestor(node) ?: node.takeIf { it.isClickable && it.hasValidScreenBounds() }

    /** Tab feed dưới cùng (`maintab_timeline` / «Tường nhà» / «Nhật ký») — null nếu đã ở feed. */
    fun findTimelineTabTapTarget(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        findByViewId(root, "maintab_timeline").forEach { tab ->
            if (tab.isSelected || tab.isChecked) return null
            resolveClickableTapTarget(tab)?.let { return it }
        }
        val tab = findTimelineTab(root)
        if (tab != null) {
            if (!tab.isSelected && !tab.isChecked) {
                resolveClickableTapTarget(tab)?.let { return it }
            }
        }
        findClickableWithPhrase(root, "tường nhà")?.let { return it }
        findClickableWithPhrase(root, "timeline")?.let { return it }
        return null
    }

    /** Tab Danh bạ (bottom nav) — null nếu đã ở Danh bạ. */
    fun findContactMainTabTapTarget(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        findByViewId(root, "maintab_contact").forEach { tab ->
            if (tab.isSelected || tab.isChecked) return null
            resolveClickableTapTarget(tab)?.let { return it }
        }
        findClickableWithPhrase(root, "danh bạ")?.let { return it }
        findClickableWithPhrase(root, "contacts")?.let { return it }
        return null
    }

    /** Sub-tab Bạn bè trên màn Danh bạ. */
    fun findFriendsSubTabTapTarget(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val fst = findByViewId(root, "first_tab_item").firstOrNull()
        val sec = findByViewId(root, "second_tab_item").firstOrNull()
        if (fst != null && sec != null) {
            if (fst.isSelected || fst.isChecked) {
                resolveClickableTapTarget(sec)?.let { return it }
            } else if (!sec.isSelected && !sec.isChecked && !fst.isSelected && !fst.isChecked) {
                resolveClickableTapTarget(sec)?.let { return it }
            }
        }
        val tabs = findByViewId(root, "tv_friends")
        val pick = tabs.filter { !it.isSelected && !it.isChecked }.ifEmpty { tabs }
        for (node in pick) {
            resolveClickableTapTarget(node)?.let { return it }
        }
        if (tabs.any { it.isSelected || it.isChecked }) return null
        findClickableWithPhrase(root, "bạn bè")?.let { return it }
        findClickableWithPhrase(root, "friends")?.let { return it }
        return null
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
