package com.zalopilot.app.accessibility

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

    private fun scanLikeButton(root: AccessibilityNodeInfo): Boolean {
        val byThich = root.findAccessibilityNodeInfosByText(ZaloIDStore.TEXT_LIKE)
        val byDaThich = root.findAccessibilityNodeInfosByText(ZaloIDStore.TEXT_LIKED)
        val byTraversal = collectTraversalLikeHints(root)
        val candidates = mergeUniqueNodes(byThich + byDaThich + byTraversal)

        for (node in candidates) {
            if (LikeViewIdRules.shouldRejectNodeForLike(node) &&
                !LikeViewIdRules.isWhitelistedLikeResourceId(node.viewIdResourceName)
            ) {
                continue
            }
            val id = findWhitelistedLikeIdNear(node) ?: continue
            if (LikeViewIdRules.isBlacklistedResourceId(id)) continue
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

    /** Chỉ id whitelist trong subtree / vài cấp cha — không lấy [vpager] / layout feed. */
    private fun findWhitelistedLikeIdNear(node: AccessibilityNodeInfo): String? {
        node.viewIdResourceName?.let { id ->
            if (LikeViewIdRules.isWhitelistedLikeResourceId(id)) return id
        }
        var host: AccessibilityNodeInfo? = node
        repeat(6) {
            val h = host ?: return null
            val best = findBestWhitelistedIdInSubtree(h, maxDepth = 10)
            if (best != null) return best
            host = h.parent
        }
        return null
    }

    private fun findBestWhitelistedIdInSubtree(root: AccessibilityNodeInfo, maxDepth: Int): String? {
        var bestId: String? = null
        var bestP = Int.MAX_VALUE
        val q = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        q.addLast(root to 0)
        while (q.isNotEmpty()) {
            val (n, d) = q.removeFirst()
            if (d > maxDepth) continue
            val id = n.viewIdResourceName
            if (LikeViewIdRules.isWhitelistedLikeResourceId(id) &&
                !LikeViewIdRules.shouldRejectNodeForLike(n)
            ) {
                val p = LikeViewIdRules.likeClickPriority(id)
                if (p < bestP) {
                    bestP = p
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
        val nodes = mergeUniqueNodes(
            root.findAccessibilityNodeInfosByText(ZaloIDStore.TEXT_TIMELINE) +
                collectNodesWithTextOrDesc(root, ZaloIDStore.TEXT_TIMELINE, maxNodes = 1600)
        )
        for (node in nodes) {
            val id = findIdFromNodeOrParent(node) ?: continue
            if (id.contains("zalo", ignoreCase = true)) {
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
        val likeNodes = mergeUniqueNodes(
            root.findAccessibilityNodeInfosByText(ZaloIDStore.TEXT_LIKE) + collectTraversalLikeHints(root)
        )
        for (likeNode in likeNodes) {
            val id = findWhitelistedLikeIdNear(likeNode) ?: continue
            if (!id.contains("zalo", ignoreCase = true)) continue

            val feedItem = findFeedItemParent(likeNode, depth = 4) ?: continue
            val authorNode = findFirstTextNode(feedItem) ?: continue
            val authorId = authorNode.viewIdResourceName ?: continue
            if (authorId.contains("zalo", ignoreCase = true)) {
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
        val recycler = findRecyclerView(root) ?: return false
        val id = recycler.viewIdResourceName ?: return false
        if (id.contains("zalo", ignoreCase = true)) {
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
}
