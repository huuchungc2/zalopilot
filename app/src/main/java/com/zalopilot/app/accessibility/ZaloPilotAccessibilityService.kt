package com.zalopilot.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import com.zalopilot.app.data.model.SelectorConfig
import com.zalopilot.app.util.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class ZaloPilotAccessibilityService : AccessibilityService() {

    @Inject lateinit var nodeFinder: NodeFinder
    @Inject lateinit var selectorConfig: SelectorConfig
    @Inject lateinit var progressManager: LikeProgressManager
    @Inject lateinit var settingsManager: LikeSettingsManager
    @Inject lateinit var logger: Logger

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isRunning = false
    private var likeJob: Job? = null
    private var sessionLikeCount = 0

    companion object {
        var instance: ZaloPilotAccessibilityService? = null
        var isActive = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isActive = true
        progressManager.resetDailyIfNeeded()
        logger.log("SERVICE", "ZaloPilotAccessibilityService", "CONNECTED")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName != "com.zing.zalo") return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (!isRunning && !progressManager.isLimitReached() && !settingsManager.isQuietHour()) {
                startAutoLike()
            }
        }
    }

    override fun onInterrupt() {
        stopAutoLike()
        logger.log("SERVICE", "ZaloPilotAccessibilityService", "INTERRUPTED")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAutoLike()
        instance = null
        isActive = false
        scope.cancel()
    }

    fun startAutoLike() {
        if (isRunning) return
        isRunning = true
        sessionLikeCount = 0
        sendBroadcast(Intent("com.zalopilot.STATUS_UPDATE").putExtra("running", true))
        logger.log("AUTO_LIKE", "", "STARTED")

        likeJob = scope.launch {
            autoLikeLoop()
        }
    }

    fun stopAutoLike() {
        isRunning = false
        likeJob?.cancel()
        likeJob = null
        sendBroadcast(Intent("com.zalopilot.STATUS_UPDATE").putExtra("running", false))
        logger.log("AUTO_LIKE", "", "STOPPED")
    }

    private suspend fun autoLikeLoop() {
        while (isRunning) {
            // Kiểm tra giờ nghỉ
            if (settingsManager.isQuietHour()) {
                logger.log("AUTO_LIKE", "", "QUIET_HOUR_PAUSE")
                stopAutoLike()
                return
            }

            // Kiểm tra đã đủ limit chưa
            if (progressManager.isLimitReached()) {
                logger.log("AUTO_LIKE", "", "DAILY_LIMIT_REACHED")
                stopAutoLike()
                return
            }

            val settings = settingsManager.load()

            // Kiểm tra session limit → nghỉ giữa chừng
            if (sessionLikeCount >= settings.sessionLimit) {
                val restMs = ((settings.restMinMinutes * 60_000L)..(settings.restMaxMinutes * 60_000L)).random()
                logger.log("AUTO_LIKE", "Nghỉ ${restMs / 60000} phút", "SESSION_REST")
                sessionLikeCount = 0
                delay(restMs)
                continue
            }

            val root = rootInActiveWindow
            if (root == null) {
                delay(1000)
                continue
            }

            // Tìm và click các nút Thích
            val likeNodes = nodeFinder.findLikeButtons(root)
            var likedThisScan = false

            for (node in likeNodes) {
                if (!isRunning) break
                if (!nodeFinder.shouldLike(node)) {
                    logger.log("LIKE_SKIP", node.text?.toString() ?: "", "ALREADY_LIKED")
                    continue
                }

                val clicked = node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                if (clicked) {
                    val progress = progressManager.incrementAndSave()
                    sessionLikeCount++
                    likedThisScan = true
                    logger.log("LIKE", "Index ${progress.lastLikedIndex}", "SUCCESS")
                    sendBroadcast(Intent("com.zalopilot.PROGRESS_UPDATE"))
                    randomDelay(settings.delayMinMs, settings.delayMaxMs)
                }

                if (progressManager.isLimitReached()) {
                    logger.log("AUTO_LIKE", "", "DAILY_LIMIT_REACHED")
                    stopAutoLike()
                    return
                }
            }

            // Nếu không like được gì → scroll xuống
            if (!likedThisScan) {
                scrollDown()
                delay(1500)
            }
        }
    }

    private fun scrollDown() {
        val swipe = selectorConfig.getSwipeConfig()
        val path = Path().apply {
            moveTo(swipe.fromX.toFloat(), swipe.fromY.toFloat())
            lineTo(swipe.toX.toFloat(), swipe.toY.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, swipe.durationMs))
            .build()
        dispatchGesture(gesture, null, null)
    }
}
