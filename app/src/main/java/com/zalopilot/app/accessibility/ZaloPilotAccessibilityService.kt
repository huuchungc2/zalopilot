package com.zalopilot.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import com.zalopilot.app.util.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class ZaloPilotAccessibilityService : AccessibilityService() {

    @Inject lateinit var nodeFinder: NodeFinder
    @Inject lateinit var uiScanner: ZaloUIScanner
    @Inject lateinit var progressManager: LikeProgressManager
    @Inject lateinit var settingsManager: LikeSettingsManager
    @Inject lateinit var logger: Logger

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isRunning = false
    private var likeJob: Job? = null
    private var sessionLikeCount = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var isZaloForeground = false
    private var consecutiveNullCount = 0

    private val likedAuthorsThisSession = mutableSetOf<String>()

    companion object {
        var instance: ZaloPilotAccessibilityService? = null
        var isActive = false
        private const val MAX_NULL_BEFORE_WARN = 3
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isActive = true
        progressManager.resetDailyIfNeeded()
        logger.log("SERVICE", "", "CONNECTED")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: ""

            if (pkg == "com.zing.zalo") {
                if (!isZaloForeground) {
                    isZaloForeground = true
                    logger.log("ZALO", "", "FOREGROUND")
                    sendBroadcast(Intent("com.zalopilot.ZALO_STATE").putExtra("foreground", true))

                    // Scan UI ngay khi Zalo lên foreground để học ID mới nhất
                    scope.launch(Dispatchers.Main) {
                        delay(500) // Chờ UI load xong
                        rootInActiveWindow?.let { uiScanner.scan(it) }
                    }

                    // Auto start nếu được bật
                    if (settingsManager.isAutoStart()
                        && !isRunning
                        && !progressManager.isLimitReached()
                        && !settingsManager.isQuietHour()
                    ) {
                        startAutoLike()
                    }
                }
            } else {
                // App khác lên foreground → Zalo bị đẩy xuống
                if (isZaloForeground) {
                    isZaloForeground = false
                    logger.log("ZALO", "", "BACKGROUND")
                    sendBroadcast(Intent("com.zalopilot.ZALO_STATE").putExtra("foreground", false))
                    if (isRunning) {
                        stopAutoLike()
                        logger.log("AUTO_LIKE", "", "PAUSED_ZALO_CLOSED")
                    }
                }
            }
        }
    }

    override fun onInterrupt() {
        stopAutoLike()
        logger.log("SERVICE", "", "INTERRUPTED")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAutoLike()
        instance = null
        isActive = false
        scope.cancel()
    }

    // ─── Public API ──────────────────────────────────────────────

    fun startAutoLike() {
        if (isRunning) return

        // Scan UI trước khi bắt đầu
        rootInActiveWindow?.let { uiScanner.scan(it) }

        isRunning = true
        sessionLikeCount = 0
        consecutiveNullCount = 0
        likedAuthorsThisSession.clear()

        // Bật WakeLock giữ CPU chạy kể cả tắt màn hình
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZaloPilot:AutoLike")
        wakeLock?.acquire(10 * 60 * 60 * 1000L) // Tối đa 10 tiếng

        sendBroadcast(Intent("com.zalopilot.STATUS_UPDATE").putExtra("running", true))
        logger.log("AUTO_LIKE", settingsManager.getLikeMode().name, "STARTED")

        likeJob = scope.launch {
            autoLikeLoop()
        }
    }

    fun stopAutoLike() {
        if (!isRunning) return
        isRunning = false
        likeJob?.cancel()
        likeJob = null

        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null

        sendBroadcast(Intent("com.zalopilot.STATUS_UPDATE").putExtra("running", false))
        logger.log("AUTO_LIKE", "", "STOPPED")
    }

    // ─── Main Loop ───────────────────────────────────────────────

    private suspend fun autoLikeLoop() {
        while (isRunning) {
            if (settingsManager.isQuietHour()) {
                logger.log("AUTO_LIKE", "", "QUIET_HOUR_PAUSE")
                stopAutoLike()
                return
            }

            if (progressManager.isLimitReached()) {
                logger.log("AUTO_LIKE", "", "DAILY_LIMIT_REACHED")
                stopAutoLike()
                return
            }

            // Kiểm tra Zalo vẫn foreground
            if (!isZaloForeground) {
                logger.log("AUTO_LIKE", "", "ZALO_NOT_FOREGROUND")
                stopAutoLike()
                return
            }

            val settings = settingsManager.load()

            // Nghỉ giữa session
            if (sessionLikeCount >= settings.sessionLimit) {
                val restMs = ((settings.restMinMinutes * 60_000L)..(settings.restMaxMinutes * 60_000L)).random()
                logger.log("AUTO_LIKE", "Nghỉ ${restMs / 60000} phút", "SESSION_REST")
                sessionLikeCount = 0
                likedAuthorsThisSession.clear()
                delay(restMs)
                continue
            }

            val root = rootInActiveWindow
            if (root == null) {
                consecutiveNullCount++
                if (consecutiveNullCount >= MAX_NULL_BEFORE_WARN) {
                    logger.log("AUTO_LIKE", "Zalo không phản hồi", "ZALO_SLEEP")
                }
                delay(10_000)
                continue
            }

            consecutiveNullCount = 0

            // Scan UI để cập nhật ID mới nhất nếu cần
            if (!uiScanner.hasScannedRecently()) {
                uiScanner.scan(root)
            }

            val liked = runFeedMode(root, settings)

            if (!liked) {
                scrollDown()
                delay(1500)
            }
        }
    }

    // ─── Feed Mode ───────────────────────────────────────────────

    private suspend fun runFeedMode(
        root: android.view.accessibility.AccessibilityNodeInfo,
        settings: LikeSettings
    ): Boolean {
        val likeNodes = nodeFinder.findLikeButtons(root)
        var likedThisScan = false

        for (node in likeNodes) {
            if (!isRunning) break
            if (!nodeFinder.shouldLike(node)) continue

            val author = nodeFinder.getAuthorName(node)
            if (author != null && likedAuthorsThisSession.contains(author)) {
                logger.log("LIKE_SKIP", author, "AUTHOR_ALREADY_LIKED")
                continue
            }

            val clicked = node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
            if (clicked) {
                progressManager.incrementAndSave()
                sessionLikeCount++
                likedThisScan = true
                if (author != null) likedAuthorsThisSession.add(author)
                logger.log("LIKE", author ?: "unknown", "SUCCESS")
                sendBroadcast(Intent("com.zalopilot.PROGRESS_UPDATE"))
                randomDelay(settings.delayMinMs, settings.delayMaxMs)
            }

            if (progressManager.isLimitReached()) {
                logger.log("AUTO_LIKE", "", "DAILY_LIMIT_REACHED")
                stopAutoLike()
                return true
            }
        }

        return likedThisScan
    }

    // ─── Scroll ──────────────────────────────────────────────────

    private fun scrollDown() {
        val metrics = resources.displayMetrics
        val fromX = metrics.widthPixels / 2f
        val fromY = metrics.heightPixels * 0.75f
        val toY = metrics.heightPixels * 0.25f

        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(fromX, toY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
            .build()
        dispatchGesture(gesture, null, null)
    }
}
