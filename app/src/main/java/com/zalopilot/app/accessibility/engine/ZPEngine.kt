package com.zalopilot.app.accessibility.engine

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.zalopilot.app.accessibility.NodeFinder
import com.zalopilot.app.accessibility.ZaloPilotAccessibilityService
import com.zalopilot.app.util.LikeProgressManager
import com.zalopilot.app.util.LikeSettingsManager
import com.zalopilot.app.util.LogTag
import com.zalopilot.app.util.Logger
import com.zalopilot.app.util.hasValidScreenBounds
import kotlinx.coroutines.delay

enum class ScrollDirection { UP, DOWN }

class ZPEngine(
    private val service: ZaloPilotAccessibilityService,
    private val nodeFinder: NodeFinder,
    private val settingsManager: LikeSettingsManager,
    private val progressManager: LikeProgressManager,
    private val logger: Logger
) {
    var lastContactItems: List<AccessibilityNodeInfo> = emptyList()
    var lastLikeNode: AccessibilityNodeInfo? = null
    var lastTapTarget: AccessibilityNodeInfo? = null

    suspend fun acquireRoot(retries: Int = 5): AccessibilityNodeInfo? =
        service.scriptAcquireRoot(retries)

    suspend fun tap(node: AccessibilityNodeInfo): Boolean =
        service.scriptTapNode(node)

    suspend fun tapCenter(rect: Rect): Boolean =
        service.scriptTapCenter(rect)

    suspend fun swipeUp(screenH: Int): Boolean =
        service.scriptSwipeUp(screenH)

    suspend fun back(): Boolean = service.scriptBack()

    suspend fun findText(
        root: AccessibilityNodeInfo,
        text: String,
        ignoreCase: Boolean = true
    ): AccessibilityNodeInfo? = nodeFinder.findNodeWithTextOrDesc(root, text, ignoreCase)

    suspend fun findHint(root: AccessibilityNodeInfo, hint: String): AccessibilityNodeInfo? =
        nodeFinder.findNodeWithHint(root, hint)

    suspend fun inputText(node: AccessibilityNodeInfo, text: String): Boolean {
        if (text.isBlank()) return false
        if (!node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        delay(120)
        val args = android.os.Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    suspend fun waitUntil(
        timeoutMs: Long,
        intervalMs: Long = 300L,
        condition: suspend () -> Boolean
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(intervalMs)
        }
        return condition()
    }

    suspend fun ensureScreen(
        detect: suspend () -> Boolean,
        navigate: suspend () -> Unit,
        timeoutMs: Long = 3000L
    ): Boolean {
        if (waitUntil(timeoutMs, condition = detect)) return true
        navigate()
        return waitUntil(timeoutMs, condition = detect)
    }

    suspend fun exists(root: AccessibilityNodeInfo, text: String): Boolean =
        findText(root, text) != null

    suspend fun scroll(
        node: AccessibilityNodeInfo,
        direction: ScrollDirection = ScrollDirection.DOWN
    ): Boolean {
        val action = when (direction) {
            ScrollDirection.DOWN -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            ScrollDirection.UP -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return node.performAction(action)
    }

    suspend fun tapNodeAt(nodes: List<AccessibilityNodeInfo>, index: Int): Boolean {
        if (index !in nodes.indices) return false
        return tap(nodes[index])
    }

    suspend fun tapSend(root: AccessibilityNodeInfo): Boolean {
        findText(root, "Gửi")?.let { if (tap(it)) return true }
        findText(root, "Send")?.let { if (tap(it)) return true }
        val dm = service.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 1200) {
            val n = stack.removeLast()
            visited++
            if (n.isClickable && n.hasValidScreenBounds()) {
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.right > w * 0.8f && r.bottom > h * 0.85f) {
                    return tap(n)
                }
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return false
    }

    suspend fun incrementVar(key: String): Int {
        val k = normalizeVarKey(key)
        return if (k == "visitIndex") {
            progressManager.incrementVisitIndexAndSave()
        } else {
            logger.log(LogTag.STATE, "unknown var=$key", "INCREMENT_VAR_SKIP")
            0
        }
    }

    suspend fun resolveVar(key: String): Any {
        val raw = key.trim()
        if (raw.startsWith("$")) {
            return when (normalizeVarKey(raw)) {
                "visitIndex" -> progressManager.getVisitIndex()
                "visitLikeCount" -> settingsManager.getVisitLikeCount()
                "visitCommentCount" -> settingsManager.getVisitCommentCount()
                else -> raw.removePrefix("$").toIntOrNull() ?: 0
            }
        }
        return raw.toIntOrNull() ?: 0
    }

    suspend fun resolveVarInt(key: String?): Int {
        if (key.isNullOrBlank()) return 0
        return when (val v = resolveVar(key)) {
            is Int -> v
            is Number -> v.toInt()
            else -> v.toString().toIntOrNull() ?: 0
        }
    }

    suspend fun findAndTapLike(root: AccessibilityNodeInfo): Boolean {
        val buttons = nodeFinder.findLikeButtons(root)
        val target = buttons.firstOrNull() ?: return false
        lastLikeNode = target
        if (!tap(target)) return false
        delay(1200)
        return verifyLiked(root)
    }

    suspend fun verifyLiked(root: AccessibilityNodeInfo): Boolean {
        val like = lastLikeNode ?: return false
        val rect = Rect().also { like.getBoundsInScreen(it) }
        val id = like.viewIdResourceName
        suspend fun check(): Boolean {
            val fresh = acquireRoot() ?: return false
            return try {
                val resolved = nodeFinder.findLikeAreaNodeAt(fresh, rect, id)
                resolved != null && nodeFinder.isAlreadyLiked(resolved)
            } finally {
                runCatching { fresh.recycle() }
            }
        }
        if (check()) return true
        delay(800)
        return check()
    }

    suspend fun inputRandomComment(root: AccessibilityNodeInfo): Boolean {
        val text = nodeFinder.getRandomComment()
        if (text.isBlank()) return false
        val input = findHint(root, "Nhập bình luận")
            ?: findHint(root, "Write a comment")
            ?: return false
        return inputText(input, text)
    }

    fun detectScreen(root: AccessibilityNodeInfo, screen: String): Boolean {
        return when (screen.lowercase()) {
            "contacts" -> nodeFinder.isContactListScreen(root)
            "chat" -> nodeFinder.isChatScreen(root)
            "profile" -> nodeFinder.isProfileScreen(root)
            "comments" -> nodeFinder.isFullScreenCommentScreen(root)
            else -> false
        }
    }

    private fun normalizeVarKey(key: String): String =
        key.trim().removePrefix("$")
}
