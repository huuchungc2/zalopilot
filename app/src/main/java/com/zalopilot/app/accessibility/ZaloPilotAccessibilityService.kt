package com.zalopilot.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
    private val mainHandler = Handler(Looper.getMainLooper())
    var isRunning = false
    private var likeJob: Job? = null
    private var sessionLikeCount = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var isZaloForeground = false
    private var consecutiveNullCount = 0
    private val likedAuthorsThisSession = mutableSetOf<String>()
    private var lastDebugDumpMs = 0L

    // Status overlay
    private var windowManager: WindowManager? = null
    private var statusView: LinearLayout? = null
    private var statusText: TextView? = null
    private var isOverlayShowing = false

    companion object {
        var instance: ZaloPilotAccessibilityService? = null
        var isActive = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isActive = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        progressManager.resetDailyIfNeeded()
        logger.log("SERVICE", "", "CONNECTED")
        showToast("✅ ZaloPilot đã kết nối")
    }

    private fun isZaloPkg(pkg: String): Boolean {
        // Bắt tất cả package liên quan Zalo: activity phụ, webview, mini app
        return pkg.startsWith("com.zing.zalo") || pkg.startsWith("com.vng.zalo")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: ""

        // Lọc bằng code thay vì XML packageNames — bắt được webview/activity phụ
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (isZaloPkg(pkg)) {
                if (!isZaloForeground) {
                    isZaloForeground = true
                    logger.log("ZALO", "pkg=$pkg", "FOREGROUND")
                    sendBroadcast(Intent("com.zalopilot.ZALO_STATE").putExtra("foreground", true))
                    scope.launch(Dispatchers.Main) {
                        // Delay 1200ms thay vì 800ms — đợi Zalo render xong UI
                        delay(1200)
                        scanWithRetry()
                    }
                    if (settingsManager.isAutoStart() && !isRunning
                        && !progressManager.isLimitReached()
                        && !settingsManager.isQuietHour()) {
                        startAutoLike()
                    }
                }
            } else if (!isZaloPkg(pkg)) {
                if (isZaloForeground) {
                    isZaloForeground = false
                    logger.log("ZALO", "pkg=$pkg", "BACKGROUND")
                    sendBroadcast(Intent("com.zalopilot.ZALO_STATE").putExtra("foreground", false))
                    if (isRunning) {
                        stopAutoLike()
                        logger.log("AUTO_LIKE", "", "PAUSED_ZALO_CLOSED")
                    }
                    hideStatusOverlay()
                }
            }
        }
    }

    // Fix 4: retry khi rootInActiveWindow chưa render xong
    private suspend fun scanWithRetry() {
        repeat(5) { attempt ->
            val root = rootInActiveWindow
            if (root != null) {
                logger.log("SCAN", "attempt=$attempt", "ROOT_OK")
                uiScanner.scan(root)
                return
            }
            logger.log("SCAN", "attempt=$attempt", "ROOT_NULL_RETRY")
            delay(600)
        }
        logger.log("SCAN", "all attempts failed", "ROOT_NEVER_READY")
    }

    override fun onInterrupt() {
        stopAutoLike()
        logger.log("SERVICE", "", "INTERRUPTED")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAutoLike()
        hideStatusOverlay()
        instance = null
        isActive = false
        scope.cancel()
    }

    // ─── Public API ──────────────────────────────────────────────

    fun startAutoLike() {
        if (isRunning) {
            logger.log("AUTO_LIKE", "", "ALREADY_RUNNING")
            showToast("ℹ️ Bot đang chạy sẵn")
            return
        }

        if (settingsManager.isQuietHour()) {
            logger.log("AUTO_LIKE", "", "BLOCKED_QUIET_HOUR")
            showToast("🌙 Đang trong giờ nghỉ — không chạy")
            return
        }

        if (progressManager.isLimitReached()) {
            val count = progressManager.load().todayLikeCount
            logger.log("AUTO_LIKE", "todayLikeCount=$count", "BLOCKED_DAILY_LIMIT")
            showToast("✅ Đã đủ giới hạn hôm nay ($count)")
            sendBroadcast(Intent("com.zalopilot.DAILY_LIMIT"))
            return
        }

        // Người dùng có thể bấm Start khi đang ở sẵn trong Zalo nhưng chưa có event TYPE_WINDOW_STATE_CHANGED,
        // nên cần tự detect foreground để tránh loop dừng ngay.
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString() ?: ""
        if (isZaloPkg(pkg)) {
            isZaloForeground = true
        } else {
            logger.log("AUTO_LIKE", "pkg=$pkg", "BLOCKED_NOT_IN_ZALO")
            showToast("⚠️ Hãy mở Zalo (tab Nhật ký) rồi bấm Start")
            return
        }

        if (root == null) {
            logger.log("AUTO_LIKE", "root=null", "BLOCKED_NO_ROOT")
            showToast("⚠️ Không đọc được màn hình — kiểm tra Accessibility")
            return
        }

        logger.log("AUTO_LIKE", "pkg=$pkg", "START_CLICKED")
        uiScanner.forceScan(root)
        isRunning = true
        sessionLikeCount = 0
        consecutiveNullCount = 0
        likedAuthorsThisSession.clear()

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZaloPilot:AutoLike")
        wakeLock?.acquire(10 * 60 * 60 * 1000L)

        sendBroadcast(Intent("com.zalopilot.STATUS_UPDATE").putExtra("running", true))
        logger.log("AUTO_LIKE", "FEED", "STARTED")
        showToast("▶ Bắt đầu auto like")
        showStatusOverlay("▶ Đang khởi động...")

        likeJob = scope.launch { autoLikeLoop() }
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
        showToast("■ Đã dừng")
        hideStatusOverlay()
    }

    // ─── Main Loop ───────────────────────────────────────────────

    private suspend fun autoLikeLoop() {
        while (isRunning) {
            if (settingsManager.isQuietHour()) {
                updateStatus("🌙 Giờ nghỉ — dừng lại")
                showToast("🌙 Giờ nghỉ — ZaloPilot tạm dừng")
                logger.log("AUTO_LIKE", "", "QUIET_HOUR_PAUSE")
                stopAutoLike()
                return
            }

            if (progressManager.isLimitReached()) {
                val count = progressManager.load().todayLikeCount
                updateStatus("✅ Đã like đủ $count bài hôm nay")
                showToast("✅ Đã like đủ $count bài hôm nay!")
                logger.log("AUTO_LIKE", "", "DAILY_LIMIT_REACHED")
                sendBroadcast(Intent("com.zalopilot.DAILY_LIMIT"))
                stopAutoLike()
                return
            }

            if (!isZaloForeground) {
                logger.log("AUTO_LIKE", "", "ZALO_NOT_FOREGROUND")
                stopAutoLike()
                return
            }

            val settings = settingsManager.load()

            if (sessionLikeCount >= settings.sessionLimit) {
                val restMs = ((settings.restMinMinutes * 60_000L)..(settings.restMaxMinutes * 60_000L)).random()
                val restMin = restMs / 60000
                updateStatus("😴 Nghỉ $restMin phút (đã like $sessionLikeCount bài)")
                showToast("😴 Nghỉ $restMin phút cho tự nhiên")
                logger.log("AUTO_LIKE", "Nghỉ $restMin phút", "SESSION_REST")
                sessionLikeCount = 0
                likedAuthorsThisSession.clear()
                delay(restMs)
                continue
            }

            val root = rootInActiveWindow ?: run { delay(500); rootInActiveWindow }
            if (root == null) {
                consecutiveNullCount++
                updateStatus("⚠️ Zalo không phản hồi ($consecutiveNullCount lần)")
                if (consecutiveNullCount >= 3) {
                    showToast("⚠️ Không đọc được màn hình Zalo — kiểm tra Accessibility")
                    logger.log("AUTO_LIKE", "Zalo không phản hồi", "ZALO_SLEEP")
                }
                delay(5000)
                continue
            }

            consecutiveNullCount = 0

            if (!uiScanner.hasScannedRecently()) {
                updateStatus("🔍 Đang scan giao diện Zalo...")
                uiScanner.scan(root)
            }

            val liked = runFeedMode(root, settings)

            if (!liked) {
                val progress = progressManager.load()
                updateStatus("📜 Cuộn xuống... (đã like ${progress.todayLikeCount}/${settings.dailyLimit})")
                // Random scroll speed: đôi khi scroll nhanh, đôi khi chậm
                delay((200..600).random().toLong())
                scrollDown()
                // Đợi feed load + giả lập mắt đọc
                delay((1200..2500).random().toLong())
            }
        }
    }

    private suspend fun runFeedMode(
        root: android.view.accessibility.AccessibilityNodeInfo,
        settings: LikeSettings
    ): Boolean {
        val likeNodes = nodeFinder.findLikeButtons(root)
        var likedThisScan = false

        if (likeNodes.isEmpty()) {
            updateStatus("🔍 Không thấy nút Thích trên màn hình này")
            logger.log("SCAN", "Không thấy nút Thích", "EMPTY")
            val now = System.currentTimeMillis()
            if (now - lastDebugDumpMs > 30_000) {
                lastDebugDumpMs = now
                nodeFinder.debugDump(root, maxNodes = 500)
            }
            return false
        }

        for (node in likeNodes) {
            if (!isRunning) break
            if (!nodeFinder.shouldLike(node)) {
                logger.log("LIKE_SKIP", "Đã like rồi", "SKIP")
                continue
            }

            val author = nodeFinder.getAuthorName(node)
            if (author != null && likedAuthorsThisSession.contains(author)) {
                logger.log("LIKE_SKIP", author, "AUTHOR_ALREADY_LIKED")
                continue
            }

            updateStatus("👍 Đang like bài của ${author ?: "..."}...")
            // Delay ngẫu nhiên 0.8-2s giả lập đọc bài trước khi like
            delay((800..2000).random().toLong())

            // Tap tọa độ thật trước, fallback ACTION_CLICK nếu cần
            val clicked = tapNodeByCoordinate(node).let { tapped ->
                if (tapped) true else performClickWithFallback(node)
            }

            if (clicked) {
                val progress = progressManager.incrementAndSave()
                sessionLikeCount++
                likedThisScan = true
                if (author != null) likedAuthorsThisSession.add(author)
                logger.log("LIKE", author ?: "unknown", "SUCCESS")
                updateStatus("✅ Like #${progress.todayLikeCount} — ${author ?: "unknown"}")
                sendBroadcast(Intent("com.zalopilot.PROGRESS_UPDATE"))
                randomDelay(settings.delayMinMs, settings.delayMaxMs)
            } else {
                updateStatus("❌ Click thất bại — thử lại lần sau")
                logger.log("LIKE", author ?: "unknown", "CLICK_FAILED")
            }

            if (progressManager.isLimitReached()) {
                logger.log("AUTO_LIKE", "", "DAILY_LIMIT_REACHED")
                stopAutoLike()
                return true
            }
        }

        return likedThisScan
    }

    // ─── Status Overlay ──────────────────────────────────────────

    private fun showStatusOverlay(msg: String) {
        mainHandler.post {
            try {
                if (isOverlayShowing) { updateStatus(msg); return@post }
                val wm = windowManager ?: return@post

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = 80
                }

                val layout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(24, 12, 24, 12)
                    setBackgroundColor(Color.parseColor("#CC000000"))
                }

                val tv = TextView(this).apply {
                    text = msg
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    maxLines = 2
                }

                layout.addView(tv)
                statusView = layout
                statusText = tv
                wm.addView(layout, params)
                isOverlayShowing = true
            } catch (e: Exception) {
                logger.log("OVERLAY", e.message ?: "", "ERROR")
            }
        }
    }

    private fun updateStatus(msg: String) {
        mainHandler.post {
            if (!isOverlayShowing) {
                showStatusOverlay(msg)
                return@post
            }
            statusText?.text = msg
        }
    }

    private fun hideStatusOverlay() {
        mainHandler.post {
            try {
                statusView?.let { windowManager?.removeView(it) }
            } catch (e: Exception) {}
            statusView = null
            statusText = null
            isOverlayShowing = false
        }
    }

    private fun showToast(msg: String) {
        mainHandler.post {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
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

    /**
     * Tap vào tọa độ thật của node trên màn hình.
     * Giống ngón tay chạm thật — tự nhiên hơn ACTION_CLICK.
     */
    private suspend fun tapNodeByCoordinate(node: android.view.accessibility.AccessibilityNodeInfo): Boolean {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return false

        // Tap vào giữa node, lệch random ±4px cho tự nhiên
        val x = (rect.centerX() + (-4..4).random()).toFloat()
        val y = (rect.centerY() + (-4..4).random()).toFloat()

        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80)) // 80ms giống tap thật
            .build()

        var result = false
        val latch = java.util.concurrent.CountDownLatch(1)
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                result = true
                latch.countDown()
            }
            override fun onCancelled(gestureDescription: GestureDescription) {
                latch.countDown()
            }
        }, null)
        latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        return result
    }

    private fun performClickWithFallback(node: android.view.accessibility.AccessibilityNodeInfo): Boolean {
        if (node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) return true
        var parent = node.parent
        repeat(4) {
            if (parent == null) return false
            if (parent!!.isClickable &&
                parent!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            parent = parent!!.parent
        }
        return false
    }
}