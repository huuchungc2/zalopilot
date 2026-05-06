package com.zalopilot.app.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import com.zalopilot.app.data.model.ZaloIDStore
import com.zalopilot.app.util.Logger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ZaloUIScanner @Inject constructor(
    private val idStore: ZaloIDStore,
    private val logger: Logger
) {
    private var scanCount = 0
    private var lastScanTime = 0L
    private val SCAN_COOLDOWN_MS = 30_000L // Không scan liên tục — tối thiểu 30 giây/lần

    fun hasScannedRecently(): Boolean {
        return System.currentTimeMillis() - lastScanTime < SCAN_COOLDOWN_MS
    }

    fun scan(root: AccessibilityNodeInfo) {
        var foundAny = false

        foundAny = scanLikeButton(root) || foundAny
        foundAny = scanTabTimeline(root) || foundAny
        foundAny = scanAuthorName(root) || foundAny
        foundAny = scanFeedRecycler(root) || foundAny

        if (foundAny) {
            scanCount++
            lastScanTime = System.currentTimeMillis()
            if (scanCount == 1 || scanCount % 50 == 0) {
                logger.log("SCANNER", "Scan #$scanCount", "IDs_UPDATED")
            }
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
        val candidates = (byThich + byDaThich)

        for (node in candidates) {
            val id = node.viewIdResourceName ?: continue
            if (id.contains("zalo", ignoreCase = true) && id.isNotBlank()) {
                val current = idStore.getLikeButtonID()
                if (current != id) {
                    idStore.saveLikeButtonID(id)
                    logger.log("SCANNER", "like_button = $id", "ID_SAVED")
                }
                return true
            }
        }
        return false
    }

    private fun scanTabTimeline(root: AccessibilityNodeInfo): Boolean {
        val nodes = root.findAccessibilityNodeInfosByText(ZaloIDStore.TEXT_TIMELINE)
        for (node in nodes) {
            val id = node.viewIdResourceName ?: continue
            if (id.contains("zalo", ignoreCase = true)) {
                val current = idStore.getTabTimelineID()
                if (current != id) {
                    idStore.saveTabTimelineID(id)
                    logger.log("SCANNER", "tab_timeline = $id", "ID_SAVED")
                }
                return true
            }
        }
        return false
    }

    private fun scanAuthorName(root: AccessibilityNodeInfo): Boolean {
        val likeNodes = root.findAccessibilityNodeInfosByText(ZaloIDStore.TEXT_LIKE)
        for (likeNode in likeNodes) {
            val id = likeNode.viewIdResourceName ?: continue
            if (!id.contains("zalo", ignoreCase = true)) continue

            val feedItem = findFeedItemParent(likeNode, depth = 4) ?: continue
            val authorNode = findFirstTextNode(feedItem) ?: continue
            val authorId = authorNode.viewIdResourceName ?: continue
            if (authorId.contains("zalo", ignoreCase = true)) {
                val current = idStore.getAuthorNameID()
                if (current != authorId) {
                    idStore.saveAuthorNameID(authorId)
                    logger.log("SCANNER", "author_name = $authorId", "ID_SAVED")
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
                logger.log("SCANNER", "feed_recycler = $id", "ID_SAVED")
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
