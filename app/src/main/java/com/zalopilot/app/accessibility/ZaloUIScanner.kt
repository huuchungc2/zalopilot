package com.zalopilot.app.accessibility

import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.zalopilot.app.data.model.ZaloIDStore
import com.zalopilot.app.util.LikeSettingsManager
import com.zalopilot.app.util.LogTag
import com.zalopilot.app.util.Logger
import com.zalopilot.app.util.hasValidScreenBounds
import java.util.ArrayList
import java.util.LinkedHashSet
import kotlin.collections.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ZaloUIScanner @Inject constructor(
    private val idStore: ZaloIDStore,
    private val settingsManager: LikeSettingsManager,
    private val logger: Logger
) {
    private var scanCount = 0
    private var lastScanTime = 0L
    @Volatile private var hintRescanPending = false

    private fun minScanGapMs(): Long = if (settingsManager.isEcoMode()) 3_400L else 1_800L

    fun hasScannedRecently(): Boolean {
        return System.currentTimeMillis() - lastScanTime < minScanGapMs()
    }

    /** Accessibility event chỉ là gợi ý: lần scan tới bỏ throttle một nhịp. */
    fun requestHintRescan() {
        hintRescanPending = true
    }

    /** Sau Clear Logs — reset gợi ý rescan. */
    fun resetTransientState() {
        hintRescanPending = false
    }

    fun scan(root: AccessibilityNodeInfo) {
        val t0 = SystemClock.elapsedRealtime()
        var outcome = "THROTTLED"
        try {
            if (hintRescanPending) {
                hintRescanPending = false
                lastScanTime = 0L
            }
            val now = System.currentTimeMillis()
            if (now - lastScanTime < minScanGapMs()) {
                return
            }
            lastScanTime = now

            var foundAny = false
            foundAny = scanLikeButton(root) || foundAny
            foundAny = scanTabTimeline(root) || foundAny
            foundAny = scanAuthorName(root) || foundAny
            foundAny = scanFeedRecycler(root) || foundAny
            foundAny = scanContactList(root) || foundAny

            if (foundAny) {
                scanCount++
                if (scanCount == 1 || scanCount % 50 == 0) {
                    logger.log(LogTag.SCAN, "scanCount=$scanCount", "IDS_UPDATED")
                }
                outcome = "MATCH"
            } else {
                outcome = "NO_MATCH"
            }
        } catch (e: Exception) {
            logger.logError("ZaloUIScanner.scan", e)
            outcome = "EXCEPTION"
        } finally {
            val elapsed = SystemClock.elapsedRealtime() - t0
            logger.log(LogTag.SCAN, "ui_scan", outcome, durationMs = elapsed)
        }
    }

    // Force scan bỏ qua cooldown — dùng khi Zalo vừa mở
    fun forceScan(root: AccessibilityNodeInfo) {
        lastScanTime = 0L
        scan(root)
    }

    private data class ScanEnv(
        val screenW: Int,
        val screenH: Int,
        val screenArea: Int,
        val maxNodeArea: Int,
    )

    private fun scanEnvFromRoot(root: AccessibilityNodeInfo): ScanEnv {
        val r = Rect()
        root.getBoundsInScreen(r)
        val w = r.width().coerceAtLeast(1)
        val h = r.height().coerceAtLeast(1)
        val area = (w * h).coerceAtLeast(1)
        return ScanEnv(
            screenW = w,
            screenH = h,
            screenArea = area,
            maxNodeArea = (area * 0.20f).toInt().coerceAtLeast(1)
        )
    }

    private fun shouldRejectScanNode(node: AccessibilityNodeInfo, env: ScanEnv): Boolean {
        // Reject: blacklist class/id
        if (LikeViewIdRules.shouldRejectNodeForLike(node)) return true

        // Reject: package khác com.zing.zalo
        val id = node.viewIdResourceName
        if (!id.isNullOrBlank()) {
            val pkg = id.substringBefore(":id/", missingDelimiterValue = "")
            if (pkg.isNotBlank() && pkg != "com.zing.zalo") return true
        }

        // Reject: bounds quá lớn (hay là container feed / layout)
        val r = Rect()
        node.getBoundsInScreen(r)
        val area = (r.width().coerceAtLeast(0) * r.height().coerceAtLeast(0)).coerceAtLeast(0)
        if (area > env.maxNodeArea) return true

        return false
    }

    /** Tránh học RecyclerView tin nhắn làm danh sách bạn bè. */
    private fun isLikelyMessageListRecyclerForContacts(n: AccessibilityNodeInfo): Boolean {
        val id = n.viewIdResourceName?.lowercase().orEmpty()
        if (id.contains("msglist") || id.contains("msg_list")) return true
        if (id.contains("conversation") && id.contains("list")) return true
        return false
    }

    private fun scanLikeButton(root: AccessibilityNodeInfo): Boolean {
        val env = scanEnvFromRoot(root)
        val byThich = root.findAccessibilityNodeInfosByText(ZaloIDStore.TEXT_LIKE)
        val byDaThich = root.findAccessibilityNodeInfosByText(ZaloIDStore.TEXT_LIKED)
        val byTraversal = collectTraversalLikeHints(root)
        val candidates = mergeUniqueNodes(byThich + byDaThich + byTraversal)

        for (node in candidates) {
            if (shouldRejectScanNode(node, env)) continue
            val id = findLikeIdNear(node, env) ?: continue
            val current = idStore.getLikeButtonID()
            if (current != id) {
                idStore.saveLikeButtonID(id)
                logger.log(LogTag.SCAN, "like_button = $id", "ID_SAVED")
            }
            return true
        }

        logger.log(LogTag.SCAN, "candidates=${candidates.size}", "LIKE_ID_MISS")
        return false
    }

    /**
     * Tìm id nút like gần vùng text "Thích/Đã thích" (subtree + vài cấp cha).
     * Không check whitelist; chỉ reject theo blacklist class/id, package != com.zing.zalo, bounds > 20% màn hình.
     */
    private fun findLikeIdNear(node: AccessibilityNodeInfo, env: ScanEnv): String? {
        node.viewIdResourceName?.let { id ->
            if (!shouldRejectScanNode(node, env) && id.startsWith("com.zing.zalo:id/")) return id
        }
        var host: AccessibilityNodeInfo? = node
        repeat(6) {
            val h = host ?: return null
            val best = findBestLikeIdInSubtree(h, env, maxDepth = 10)
            if (best != null) return best
            host = h.parent
        }
        return null
    }

    private fun findBestLikeIdInSubtree(root: AccessibilityNodeInfo, env: ScanEnv, maxDepth: Int): String? {
        var bestId: String? = null
        var bestP = Int.MAX_VALUE
        var bestArea = Int.MAX_VALUE
        val q = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        q.addLast(root to 0)
        while (q.isNotEmpty()) {
            val (n, d) = q.removeFirst()
            if (d > maxDepth) continue
            val id = n.viewIdResourceName
            if (!id.isNullOrBlank() &&
                id.startsWith("com.zing.zalo:id/") &&
                !shouldRejectScanNode(n, env)
            ) {
                val p = LikeViewIdRules.likeClickPriority(id)
                val r = Rect()
                n.getBoundsInScreen(r)
                val area = (r.width().coerceAtLeast(0) * r.height().coerceAtLeast(0)).coerceAtLeast(0)
                if (p < bestP || (p == bestP && area < bestArea)) {
                    bestP = p
                    bestArea = area
                    bestId = id
                }
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { child -> q.addLast(child to (d + 1)) }
            }
        }
        return bestId
    }

    /** Gợi ý nút like từ contentDescription / text khi API theo chuỗi không đủ. */
    private fun collectTraversalLikeHints(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 2400) {
            val node = stack.removeLast()
            visited++
            if (!node.hasValidScreenBounds()) continue
            val t = node.text?.toString() ?: ""
            val cd = node.contentDescription?.toString() ?: ""
            val hay = "$t $cd"
            if (hay.contains("Đã thích", ignoreCase = true)) {
                // skip
            } else if (hay.contains(ZaloIDStore.TEXT_LIKE, ignoreCase = true)) {
                out.add(node)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return out
    }

    private fun mergeUniqueNodes(nodes: List<AccessibilityNodeInfo>): List<AccessibilityNodeInfo> {
        val seen = LinkedHashSet<String>()
        val out = ArrayList<AccessibilityNodeInfo>()
        for (n in nodes) {
            if (!n.hasValidScreenBounds()) continue
            val key = nodeBoundsKey(n)
            if (key !in seen) {
                seen.add(key)
                out.add(n)
            }
        }
        return out
    }

    private fun nodeBoundsKey(n: AccessibilityNodeInfo): String {
        val r = android.graphics.Rect()
        n.getBoundsInScreen(r)
        return "${r.centerX()}_${r.centerY()}_${n.viewIdResourceName ?: ""}"
    }

    private fun findIdFromNodeOrParent(node: AccessibilityNodeInfo): String? {
        node.viewIdResourceName?.takeIf { it.isNotBlank() }?.let { return it }
        var parent: AccessibilityNodeInfo? = node.parent
        repeat(4) {
            val id = parent?.viewIdResourceName?.takeIf { it.isNotBlank() }
            if (id != null) return id
            parent = parent?.parent
        }
        return null
    }

    private fun scanTabTimeline(root: AccessibilityNodeInfo): Boolean {
        val env = scanEnvFromRoot(root)
        val nodes = mergeUniqueNodes(
            root.findAccessibilityNodeInfosByText(ZaloIDStore.TEXT_TIMELINE) +
                collectNodesWithTextOrDesc(root, ZaloIDStore.TEXT_TIMELINE, maxNodes = 1600)
        )
        for (node in nodes) {
            if (shouldRejectScanNode(node, env)) continue
            val id = findIdFromNodeOrParent(node) ?: continue
            if (id.startsWith("com.zing.zalo:id/")) {
                val current = idStore.getTabTimelineID()
                if (current != id) {
                    idStore.saveTabTimelineID(id)
                    logger.log(LogTag.SCAN, "tab_timeline = $id", "ID_SAVED")
                }
                return true
            }
        }
        return false
    }

    private fun collectNodesWithTextOrDesc(
        root: AccessibilityNodeInfo,
        needle: String,
        maxNodes: Int
    ): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < maxNodes) {
            val node = stack.removeLast()
            visited++
            if (!node.hasValidScreenBounds()) continue
            val t = node.text?.toString() ?: ""
            val d = node.contentDescription?.toString() ?: ""
            if (t.contains(needle, ignoreCase = true) || d.contains(needle, ignoreCase = true)) {
                out.add(node)
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return out
    }

    private fun scanAuthorName(root: AccessibilityNodeInfo): Boolean {
        val env = scanEnvFromRoot(root)
        val likeNodes = mergeUniqueNodes(
            root.findAccessibilityNodeInfosByText(ZaloIDStore.TEXT_LIKE) + collectTraversalLikeHints(root)
        )
        for (likeNode in likeNodes) {
            val id = findLikeIdNear(likeNode, env) ?: continue

            val feedItem = findFeedItemParent(likeNode, depth = 4) ?: continue
            val authorNode = findFirstTextNode(feedItem) ?: continue
            val authorId = authorNode.viewIdResourceName ?: continue
            if (authorId.startsWith("com.zing.zalo:id/")) {
                val current = idStore.getAuthorNameID()
                if (current != authorId) {
                    idStore.saveAuthorNameID(authorId)
                    logger.log(LogTag.SCAN, "author_name = $authorId", "ID_SAVED")
                }
                return true
            }
        }
        return false
    }

    private fun scanFeedRecycler(root: AccessibilityNodeInfo): Boolean {
        val env = scanEnvFromRoot(root)
        val recycler = findRecyclerView(root) ?: return false
        if (shouldRejectScanNode(recycler, env)) return false
        val id = recycler.viewIdResourceName ?: return false
        if (id.startsWith("com.zing.zalo:id/")) {
            val current = idStore.getFeedRecyclerID()
            if (current != id) {
                idStore.saveFeedRecyclerID(id)
                logger.log(LogTag.SCAN, "feed_recycler = $id", "ID_SAVED")
            }
            return true
        }
        return false
    }

    private fun findFeedItemParent(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(depth) {
            current = current?.parent ?: return null
        }
        return current
    }

    private fun findFirstTextNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (!root.text.isNullOrBlank() && root.viewIdResourceName != null) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findFirstTextNode(child)
            if (found != null) return found
        }
        return null
    }

    private fun findRecyclerView(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.className?.toString() == ZaloIDStore.CLASS_RECYCLER) return root
        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val found = findRecyclerView(child)
            if (found != null) return found
        }
        return null
    }

    /** Danh bạ → Bạn bè: học RecyclerView danh sách + id hàng bạn (cho Visit script). */
    private fun scanContactList(root: AccessibilityNodeInfo): Boolean {
        if (!looksLikeContactListScreen(root)) return false
        val env = scanEnvFromRoot(root)
        val recycler = findContactsRecyclerView(root, env) ?: return false
        if (shouldRejectScanNode(recycler, env)) return false
        val listId = recycler.viewIdResourceName ?: return false
        if (!listId.startsWith("com.zing.zalo:id/")) return false

        var savedAny = false
        val currentList = idStore.getContactListID()
        if (currentList != listId) {
            idStore.saveContactListID(listId)
            logger.log(LogTag.SCAN, "contact_list = $listId", "ID_SAVED")
            savedAny = true
        }

        val rootRect = Rect().also { root.getBoundsInScreen(it) }
        val tabBottom = rootRect.top + (rootRect.height() / 7)
        val navTop = rootRect.top + (rootRect.height() * 0.86f).toInt()
        val itemIdCounts = LinkedHashMap<String, Int>()
        for (i in 0 until recycler.childCount) {
            val child = recycler.getChild(i) ?: continue
            val row = pickContactRowNode(child, tabBottom, navTop) ?: continue
            val rowId = row.viewIdResourceName ?: continue
            if (!rowId.startsWith("com.zing.zalo:id/")) continue
            itemIdCounts[rowId] = (itemIdCounts[rowId] ?: 0) + 1
        }
        val bestItemId = itemIdCounts.maxByOrNull { it.value }?.key
        if (bestItemId != null) {
            val currentItem = idStore.getContactItemID()
            if (currentItem != bestItemId) {
                idStore.saveContactItemID(bestItemId)
                logger.log(LogTag.SCAN, "contact_item = $bestItemId", "ID_SAVED")
                savedAny = true
            }
        }
        return savedAny || currentList == listId
    }

    private fun looksLikeContactListScreen(root: AccessibilityNodeInfo): Boolean {
        if (hasViewIdSuffix(root, "main_chat_view")) return false
        val onContacts = hasViewIdSuffix(root, "maintab_contact") ||
            containsTextOrDesc(root, "Danh bạ", 800) ||
            containsTextOrDesc(root, "Contacts", 800)
        val hasFriends = containsTextOrDesc(root, "Bạn bè", 800) ||
            containsTextOrDesc(root, "Friends", 800)
        return onContacts && hasFriends
    }

    private fun findContactsRecyclerView(
        root: AccessibilityNodeInfo,
        env: ScanEnv
    ): AccessibilityNodeInfo? {
        val rootRect = Rect().also { root.getBoundsInScreen(it) }
        val minY = rootRect.top + (rootRect.height() * 0.12f).toInt()
        val maxY = rootRect.top + (rootRect.height() * 0.88f).toInt()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        while (stack.isNotEmpty() && visited < 2200) {
            val n = stack.removeLast()
            visited++
            val cls = n.className?.toString().orEmpty()
            if (cls.contains("RecyclerView", ignoreCase = true)) {
                if (!shouldRejectScanNode(n, env) && !isLikelyMessageListRecyclerForContacts(n)) {
                    val r = Rect().also { n.getBoundsInScreen(it) }
                    val cy = r.centerY()
                    val area = (r.width().coerceAtLeast(0) * r.height().coerceAtLeast(0))
                    if (cy in minY..maxY && area > bestArea && !n.viewIdResourceName.isNullOrBlank()) {
                        bestArea = area
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

    private fun pickContactRowNode(
        node: AccessibilityNodeInfo,
        tabBottom: Int,
        navTop: Int
    ): AccessibilityNodeInfo? {
        if (isContactRowForScan(node, tabBottom, navTop)) {
            return if (node.isClickable) node else findClickableDescendant(node, 4)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            pickContactRowNode(child, tabBottom, navTop)?.let { return it }
        }
        return null
    }

    private fun isContactRowForScan(
        n: AccessibilityNodeInfo,
        tabBottom: Int,
        navTop: Int
    ): Boolean {
        if (!n.hasValidScreenBounds()) return false
        val r = Rect().also { n.getBoundsInScreen(it) }
        if (r.height() !in 80..340 || r.width() < 180) return false
        if (r.centerY() < tabBottom || r.centerY() > navTop) return false
        val label = (n.text?.toString() ?: n.contentDescription?.toString()).orEmpty().trim()
        if (label.length !in 2..120) return false
        val low = label.lowercase()
        val skip = listOf(
            "friend request", "lời mời", "birthday", "sinh nhật",
            "tất cả", "all ", "bạn bè", "friends", "nhóm", "tìm kiếm", "search"
        )
        if (skip.any { low.contains(it) }) return false
        return true
    }

    private fun findClickableDescendant(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (node.isClickable && node.hasValidScreenBounds()) return node
        if (depth <= 0) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findClickableDescendant(child, depth - 1)?.let { return it }
        }
        return null
    }

    private fun hasViewIdSuffix(root: AccessibilityNodeInfo, suffix: String): Boolean {
        val fullId = "com.zing.zalo:id/$suffix"
        return !(root.findAccessibilityNodeInfosByViewId(fullId).isNullOrEmpty())
    }

    private fun containsTextOrDesc(root: AccessibilityNodeInfo, needle: String, maxNodes: Int): Boolean {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < maxNodes) {
            val n = stack.removeLast()
            visited++
            val hay = listOf(n.text, n.contentDescription)
                .mapNotNull { it?.toString()?.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" ")
            if (hay.contains(needle, ignoreCase = true)) return true
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return false
    }
}
