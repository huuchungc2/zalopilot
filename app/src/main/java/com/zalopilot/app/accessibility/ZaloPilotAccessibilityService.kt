package com.zalopilot.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.zalopilot.app.util.FeedMode
import com.zalopilot.app.util.InteractMode
import com.zalopilot.app.util.LikeProgressManager
import com.zalopilot.app.util.LikeSettings
import com.zalopilot.app.util.DebugHighlightPrefs
import com.zalopilot.app.util.LikeSettingsManager
import com.zalopilot.app.util.LogTag
import com.zalopilot.app.util.Logger
import com.zalopilot.app.util.isZaloRelatedPackage
import com.zalopilot.app.util.random
import com.zalopilot.app.util.randomDelay
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.coroutines.resume

@AndroidEntryPoint
class ZaloPilotAccessibilityService : AccessibilityService() {

    @Volatile
    private var startAutoLikeInProgress = false

    @Inject lateinit var nodeFinder: NodeFinder
    @Inject lateinit var uiScanner: ZaloUIScanner
    @Inject lateinit var progressManager: LikeProgressManager
    @Inject lateinit var settingsManager: LikeSettingsManager
    @Inject lateinit var logger: Logger
    @Inject lateinit var debugHighlightPrefs: DebugHighlightPrefs

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var foregroundPollJob: Job? = null

    var isRunning = false
    private var likeJob: Job? = null
    private var sessionLikeCount = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var isZaloForeground = false
    private var consecutiveNullCount = 0
    private var consecutivePollRootNull = 0
    private val likedAuthorsThisSession = mutableSetOf<String>()
    private var lastDebugDumpMs = 0L
    private var lastContentEventLogMs = 0L
    private var lastRootNullWallLogMs = 0L

    /** Tránh spam click cùng vùng bounds khi Zalo chưa kịp cập nhật UI. */
    private var lastLikeClickBoundsKey: String? = null
    private var lastLikeClickAttemptAtMs: Long = 0L

    private var windowManager: WindowManager? = null
    private var statusView: LinearLayout? = null
    private var statusText: TextView? = null
    private var isOverlayShowing = false

    /** Viền debug nút like — tách khỏi status overlay. */
    private var nodeHighlightView: NodeHighlightOverlayView? = null

    companion object {
        var instance: ZaloPilotAccessibilityService? = null
        var isActive = false

        /** Broadcast: foreground notification "📋 Dump UI" → [performDumpUiTreeFromActiveWindow]. */
        const val ACTION_DUMP_UI_TREE = "com.zalopilot.DUMP_UI_TREE"

        /** Áp dụng ngay khi bật/tắt trong Cài đặt (không cần khởi động lại service). */
        fun syncDebugHighlightFromPrefs() {
            instance?.syncDebugHighlightFromPrefsInternal()
        }

        private const val POLL_MS_MIN = 1_000L
        private const val POLL_MS_MAX = 2_000L
        private const val ROOT_RETRY_DEFAULT = 5
        private const val POLL_ROOT_ASSUME_BACKGROUND = 6
        private const val CONTENT_EVENT_LOG_THROTTLE_MS = 3_000L
        private const val ROOT_NULL_WALL_LOG_MS = 8_000L
        private const val DUPLICATE_LIKE_CLICK_SUPPRESS_MS = 2_500L
    }

    private val dumpUiTreeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_DUMP_UI_TREE) return
            performDumpUiTreeFromActiveWindow()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isActive = true
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(
                    dumpUiTreeReceiver,
                    IntentFilter(ACTION_DUMP_UI_TREE),
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(dumpUiTreeReceiver, IntentFilter(ACTION_DUMP_UI_TREE))
            }
        }.onFailure { e -> logger.logError("registerDumpUiReceiver", e) }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        progressManager.resetDailyIfNeeded()
        logger.log(LogTag.STATE, "service poll=${POLL_MS_MIN}-${POLL_MS_MAX}ms", "CONNECTED")
        showToast("✅ ZaloPilot đã kết nối")

        foregroundPollJob?.cancel()
        foregroundPollJob = scope.launch {
            while (isActive) {
                delay((POLL_MS_MIN..POLL_MS_MAX).random())
                runCatching { pollOnce() }.onFailure { e ->
                    logger.logError("pollLoop", e)
                }
            }
        }
    }

    /**
     * Poll định kỳ: nguồn sự thật chính cho root + package (không phụ thuộc event).
     */
    private suspend fun pollOnce() {
        val root = acquireRootOrNull(
            maxAttempts = ROOT_RETRY_DEFAULT,
            delayRangeMs = 100L..320L,
            logTag = LogTag.POLL
        )

        if (root == null) {
            consecutivePollRootNull++
            val now = System.currentTimeMillis()
            if (now - lastRootNullWallLogMs > ROOT_NULL_WALL_LOG_MS) {
                lastRootNullWallLogMs = now
                logger.log(LogTag.POLL, "consecutiveNull=$consecutivePollRootNull", "ROOT_NULL_AFTER_RETRIES")
            }
            if (consecutivePollRootNull >= POLL_ROOT_ASSUME_BACKGROUND && isZaloForeground) {
                logger.log(LogTag.STATE, "root persistently null", "BACKGROUND_ASSUMED")
                applyZaloBackground(reason = "poll_root_exhausted")
            }
            return
        }

        consecutivePollRootNull = 0
        val pkg = root.packageName?.toString() ?: ""
        logger.setForegroundPackage(pkg)
        val inZalo = isZaloRelatedPackage(pkg)

        if (inZalo) {
            if (!isZaloForeground) {
                logger.log(LogTag.STATE, "pkg=$pkg", "FOREGROUND_POLL")
                sendBroadcast(Intent("com.zalopilot.ZALO_STATE").putExtra("foreground", true))
                isZaloForeground = true
                if (settingsManager.isAutoStart() && !isRunning
                    && !progressManager.isLimitReached()
                    && !settingsManager.isQuietHour()) {
                    mainHandler.post { startAutoLike() }
                }
            }
            uiScanner.scan(root)
        } else {
            if (isZaloForeground) {
                logger.log(LogTag.STATE, "pkg=$pkg", "BACKGROUND_POLL")
                applyZaloBackground(reason = "poll_other_package")
            }
        }
    }

    private fun applyZaloBackground(reason: String) {
        isZaloForeground = false
        sendBroadcast(Intent("com.zalopilot.ZALO_STATE").putExtra("foreground", false))
        if (isRunning) {
            stopAutoLike()
            logger.log(LogTag.STATE, "reason=$reason", "PAUSED_ZALO_CLOSED")
        }
        hideStatusOverlay()
    }

    /**
     * Lấy [rootInActiveWindow] với vài lần thử; không nuốt lỗi — luôn log từng bước.
     */
    private suspend fun acquireRootOrNull(
        maxAttempts: Int = ROOT_RETRY_DEFAULT,
        delayRangeMs: LongRange = 100L..300L,
        logTag: LogTag = LogTag.POLL
    ): AccessibilityNodeInfo? {
        repeat(maxAttempts) { attempt ->
            val root = rootInActiveWindow
            if (root != null) {
                val pkg = root.packageName?.toString() ?: ""
                logger.setForegroundPackage(pkg)
                logger.log(logTag, "attempt=${attempt + 1}/$maxAttempts pkg=$pkg", "ROOT_OK")
                return root
            }
            logger.log(logTag, "attempt=${attempt + 1}/$maxAttempts", "ROOT_NULL_RETRY")
            if (attempt < maxAttempts - 1) {
                delay(delayRangeMs.random())
            }
        }
        logger.log(logTag, "attempts=$maxAttempts", "ROOT_GIVEUP")
        return null
    }

    private fun eventTypeLabel(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW_STATE"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "WINDOW_CONTENT"
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "VIEW_CLICKED"
        AccessibilityEvent.TYPE_VIEW_FOCUSED -> "VIEW_FOCUSED"
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "VIEW_SCROLLED"
        else -> "TYPE_$type"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: run {
            logger.log(LogTag.ERROR, "onAccessibilityEvent", "NULL_EVENT")
            return
        }
        val pkg = event.packageName?.toString() ?: ""
        logger.setForegroundPackage(pkg)
        val label = eventTypeLabel(event.eventType)
        val className = event.className?.toString() ?: ""

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val now = System.currentTimeMillis()
                if (now - lastContentEventLogMs > CONTENT_EVENT_LOG_THROTTLE_MS) {
                    lastContentEventLogMs = now
                    logger.log(LogTag.EVENT_HINT, "type=$label pkg=$pkg class=$className", "CONTENT_THROTTLED")
                }
            }
            else -> {
                logger.log(LogTag.EVENT_HINT, "type=$label pkg=$pkg class=$className", "HINT")
            }
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (isZaloRelatedPackage(pkg)) {
                logger.setForegroundPackage(pkg)
                uiScanner.requestHintRescan()
            } else if (isZaloForeground) {
                logger.log(LogTag.STATE, "pkg=$pkg", "BACKGROUND_EVENT")
                applyZaloBackground(reason = "window_state_non_zalo")
            }
        }
    }

    override fun onInterrupt() {
        stopAutoLike()
        logger.log(LogTag.STATE, "service", "INTERRUPTED")
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(dumpUiTreeReceiver) }
            .onFailure { e -> logger.logError("unregisterDumpUiReceiver", e) }
        super.onDestroy()
        foregroundPollJob?.cancel()
        foregroundPollJob = null
        stopAutoLike()
        hideStatusOverlay()
        detachNodeHighlightOverlay()
        instance = null
        isActive = false
        scope.cancel()
    }

    fun startAutoLike() {
        if (isRunning) {
            logger.log(LogTag.STATE, "autoLike", "ALREADY_RUNNING")
            showToast("ℹ️ Bot đang chạy sẵn")
            return
        }
        if (startAutoLikeInProgress) {
            logger.log(LogTag.STATE, "autoLike", "START_IN_PROGRESS")
            showToast("ℹ️ Đang khởi động bot…")
            return
        }

        if (settingsManager.isQuietHour()) {
            logger.log(LogTag.STATE, "autoLike", "BLOCKED_QUIET_HOUR")
            showToast("🌙 Đang trong giờ nghỉ — không chạy")
            return
        }

        if (progressManager.isLimitReached()) {
            val count = progressManager.load().todayLikeCount
            logger.log(LogTag.STATE, "todayLikeCount=$count", "BLOCKED_DAILY_LIMIT")
            showToast("✅ Đã đủ giới hạn hôm nay ($count)")
            sendBroadcast(Intent("com.zalopilot.DAILY_LIMIT"))
            return
        }

        startAutoLikeInProgress = true
        scope.launch {
            try {
                val root = acquireRootOrNull(
                    maxAttempts = 5,
                    delayRangeMs = 120L..350L,
                    logTag = LogTag.STATE
                )
                val pkg = root?.packageName?.toString() ?: ""

                if (root == null) {
                    logger.log(LogTag.STATE, "pkg=$pkg", "BLOCKED_NO_ROOT")
                    showToast("⚠️ Không đọc được màn hình — thử lại sau vài giây")
                    return@launch
                }

                if (!isZaloRelatedPackage(pkg)) {
                    logger.log(LogTag.STATE, "pkg=$pkg", "BLOCKED_NOT_IN_ZALO")
                    showToast("⚠️ Hãy mở Zalo (tab Nhật ký) rồi bấm Start")
                    return@launch
                }

                isZaloForeground = true
                logger.setForegroundPackage(pkg)
                logger.log(LogTag.STATE, "pkg=$pkg", "START_CLICKED")
                uiScanner.forceScan(root)

                isRunning = true
                sessionLikeCount = 0
                consecutiveNullCount = 0
                consecutivePollRootNull = 0
                likedAuthorsThisSession.clear()

                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZaloPilot:AutoLike")
                wakeLock?.acquire(10 * 60 * 60 * 1000L)

                sendBroadcast(Intent("com.zalopilot.STATUS_UPDATE").putExtra("running", true))
                logger.log(LogTag.STATE, "FEED", "STARTED")
                showToast("▶ Bắt đầu auto like")
                showStatusOverlay("▶ Đang khởi động...")

                likeJob?.cancel()
                likeJob = scope.launch { autoLikeLoop() }
            } finally {
                startAutoLikeInProgress = false
            }
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
        logger.log(LogTag.STATE, "autoLike", "STOPPED")
        showToast("■ Đã dừng")
        hideStatusOverlay()
        detachNodeHighlightOverlay()
    }

    private suspend fun autoLikeLoop() {
        while (isRunning) {
            if (settingsManager.isQuietHour()) {
                updateStatus("🌙 Giờ nghỉ — dừng lại")
                showToast("🌙 Giờ nghỉ — ZaloPilot tạm dừng")
                logger.log(LogTag.STATE, "autoLike", "QUIET_HOUR_PAUSE")
                stopAutoLike()
                return
            }

            if (progressManager.isLimitReached()) {
                val count = progressManager.load().todayLikeCount
                updateStatus("✅ Đã like đủ $count bài hôm nay")
                showToast("✅ Đã like đủ $count bài hôm nay!")
                logger.log(LogTag.STATE, "autoLike", "DAILY_LIMIT_REACHED")
                sendBroadcast(Intent("com.zalopilot.DAILY_LIMIT"))
                stopAutoLike()
                return
            }

            if (!isZaloForeground) {
                logger.log(LogTag.STATE, "autoLike", "ZALO_NOT_FOREGROUND")
                stopAutoLike()
                return
            }

            val settings = settingsManager.load()

            if (sessionLikeCount >= settings.sessionLimit) {
                val restMs = ((settings.restMinMinutes * 60_000L)..(settings.restMaxMinutes * 60_000L)).random()
                val restMin = restMs / 60000
                updateStatus("😴 Nghỉ $restMin phút (đã like $sessionLikeCount bài)")
                showToast("😴 Nghỉ $restMin phút cho tự nhiên")
                logger.log(LogTag.STATE, "Nghỉ $restMin phút", "SESSION_REST")
                sessionLikeCount = 0
                likedAuthorsThisSession.clear()
                delay(restMs)
                continue
            }

            val root = acquireRootOrNull(
                maxAttempts = 5,
                delayRangeMs = 100L..280L,
                logTag = LogTag.STATE
            )

            if (root == null) {
                consecutiveNullCount++
                updateStatus("⚠️ Zalo không phản hồi ($consecutiveNullCount lần)")
                logger.log(LogTag.STATE, "consecutiveNull=$consecutiveNullCount", "ROOT_NULL_LOOP")
                if (consecutiveNullCount >= 3) {
                    showToast("⚠️ Không đọc được màn hình Zalo — đang thử lại")
                    logger.log(LogTag.STATE, "root null streak", "ZALO_SLEEP")
                }
                delay((1_000L..2_000L).random())
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
                val feedMode = settingsManager.getFeedMode()
                when (feedMode) {
                    FeedMode.MANUAL -> {
                        updateStatus("👆 Chế độ tay — cuộn để tìm bài (${progress.todayLikeCount}/${settings.dailyLimit})")
                        delay((800..1500).random().toLong())
                    }
                    FeedMode.MIX -> {
                        updateStatus("📜 Kết hợp... (đã like ${progress.todayLikeCount}/${settings.dailyLimit})")
                        delay((200..600).random().toLong())
                        if (listOf(true, false).random()) {
                            scrollDown()
                            logger.log(LogTag.SCROLL, "gesture swipe up", "MIX_DISPATCHED")
                        } else {
                            logger.log(LogTag.SCROLL, "mix", "SKIP_SCROLL_WAIT")
                        }
                        delay((1200..2500).random().toLong())
                    }
                    FeedMode.SCROLL -> {
                        updateStatus("📜 Cuộn xuống... (đã like ${progress.todayLikeCount}/${settings.dailyLimit})")
                        delay((200..600).random().toLong())
                        val scrollMode = settingsManager.getInteractMode()
                        if (scrollMode == InteractMode.TAP) {
                            val scrolled = root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                            logger.log(LogTag.SCROLL, "ACTION_SCROLL_FORWARD", if (scrolled) "OK" else "FAILED")
                        } else {
                            scrollDown()
                            logger.log(LogTag.SCROLL, "gesture swipe up", "DISPATCHED")
                        }
                        delay((1200..2500).random().toLong())
                    }
                }
            }
        }
    }

    private suspend fun runFeedMode(
        root: AccessibilityNodeInfo,
        settings: LikeSettings
    ): Boolean {
        val likeNodes = nodeFinder.findLikeButtons(root)
        var likedThisScan = false

        if (likeNodes.isEmpty()) {
            updateDebugNodeHighlights(emptyList(), null)
            updateStatus("🔍 Không thấy nút Thích trên màn hình này")
            logger.log(LogTag.SCAN, "feed like buttons", "EMPTY")
            val now = System.currentTimeMillis()
            if (now - lastDebugDumpMs > 30_000) {
                lastDebugDumpMs = now
                nodeFinder.debugDump(root, maxNodes = 500)
            }
            return false
        }

        updateDebugNodeHighlights(likeNodes, null)

        for (node in likeNodes) {
            if (!isRunning) break
            if (!nodeFinder.shouldLike(node)) {
                logger.log(LogTag.STATE, "Đã like / không hợp lệ — ${boundsSummary(node)}", "SKIP")
                continue
            }

            val author = nodeFinder.getAuthorName(node)
            if (author != null && likedAuthorsThisSession.contains(author)) {
                logger.log(LogTag.STATE, author, "AUTHOR_ALREADY_LIKED")
                continue
            }

            updateStatus("👍 Đang like bài của ${author ?: "..."}...")
            delay((800..2000).random().toLong())

            updateDebugNodeHighlights(likeNodes, node)

            val clicked = performLikeClickWithFallbacks(node)

            updateDebugNodeHighlights(likeNodes, null)

            if (clicked) {
                val progress = progressManager.incrementAndSave()
                sessionLikeCount++
                likedThisScan = true
                if (author != null) likedAuthorsThisSession.add(author)
                logger.log(LogTag.CLICK, author ?: "unknown", "SUCCESS")
                updateStatus("✅ Like #${progress.todayLikeCount} — ${author ?: "unknown"}")
                sendBroadcast(Intent("com.zalopilot.PROGRESS_UPDATE"))
                randomDelay(settings.delayMinMs, settings.delayMaxMs)
            } else {
                updateStatus("❌ Click thất bại — thử lại lần sau")
                logger.log(LogTag.CLICK, author ?: "unknown", "CLICK_FAILED:${boundsSummary(node)}")
            }

            if (progressManager.isLimitReached()) {
                logger.log(LogTag.STATE, "autoLike", "DAILY_LIMIT_REACHED")
                stopAutoLike()
                return true
            }
        }

        return likedThisScan
    }

    private fun boundsSummary(node: AccessibilityNodeInfo): String {
        val r = Rect()
        node.getBoundsInScreen(r)
        return "bounds=[${r.left},${r.top},${r.right},${r.bottom}] clickable=${node.isClickable}"
    }

    private fun roundTo16(v: Int) = (v / 16) * 16

    private fun boundsDedupeKey(node: AccessibilityNodeInfo): String {
        val r = Rect()
        node.getBoundsInScreen(r)
        return "${roundTo16(r.left)}_${roundTo16(r.top)}_${node.viewIdResourceName ?: ""}"
    }

    private fun shouldSuppressDuplicateBoundsClick(node: AccessibilityNodeInfo): Boolean {
        val key = boundsDedupeKey(node)
        val now = System.currentTimeMillis()
        val prev = lastLikeClickBoundsKey
        if (prev != null && key == prev &&
            (now - lastLikeClickAttemptAtMs) < DUPLICATE_LIKE_CLICK_SUPPRESS_MS
        ) {
            logger.log(
                LogTag.CLICK,
                "bounds=$key deltaMs=${now - lastLikeClickAttemptAtMs}",
                "DUPLICATE_BOUNDS_SKIP"
            )
            return true
        }
        return false
    }

    private fun markLikeClickAttemptForBounds(node: AccessibilityNodeInfo) {
        lastLikeClickBoundsKey = boundsDedupeKey(node)
        lastLikeClickAttemptAtMs = System.currentTimeMillis()
    }

    private fun nodeSnapshotForLog(node: AccessibilityNodeInfo): String {
        val r = Rect()
        node.getBoundsInScreen(r)
        val text = node.text?.toString()?.replace("\n", "↵") ?: ""
        val desc = node.contentDescription?.toString()?.replace("\n", "↵") ?: ""
        val cls = node.className?.toString() ?: ""
        val id = node.viewIdResourceName ?: ""
        return "text=\"$text\" desc=\"$desc\" class=\"$cls\" id=\"$id\" " +
            "clickable=${node.isClickable} enabled=${node.isEnabled} " +
            "boundsInScreen=[${r.left},${r.top},${r.right},${r.bottom}]"
    }

    private suspend fun performLikeClickWithFallbacks(node: AccessibilityNodeInfo): Boolean {
        if (shouldSuppressDuplicateBoundsClick(node)) return false
        if (!node.isVisibleToUser) {
            logger.log(LogTag.CLICK, boundsSummary(node), "SKIP_NOT_VISIBLE")
            return false
        }
        markLikeClickAttemptForBounds(node)
        logger.log(LogTag.CLICK, nodeSnapshotForLog(node), "CLICK_CANDIDATE")
        if (performClickWithFallback(node)) return true
        val gestureOk = tapNodeByCoordinate(node)
        logger.log(
            LogTag.CLICK,
            boundsSummary(node),
            if (gestureOk) "GESTURE_FALLBACK_OK" else "GESTURE_FALLBACK_FAIL"
        )
        return gestureOk
    }

    private fun showStatusOverlay(msg: String) {
        mainHandler.post {
            try {
                if (isOverlayShowing) {
                    updateStatus(msg)
                    return@post
                }
                val wm = windowManager ?: run {
                    logger.log(LogTag.STATE, "overlay", "WINDOW_MANAGER_NULL")
                    return@post
                }

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
                logger.logError("showStatusOverlay", e)
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
            } catch (e: Exception) {
                logger.logError("hideStatusOverlay", e)
            }
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

    /**
     * [rootInActiveWindow] + [NodeFinder.dumpToFile] (cùng JSON [Logger.saveUiTree]).
     */
    private fun performDumpUiTreeFromActiveWindow() {
        scope.launch(Dispatchers.Default) {
            val root = rootInActiveWindow
            if (root == null) {
                logger.log(LogTag.SCAN, "dump UI action", "ROOT_NULL")
                return@launch
            }
            try {
                nodeFinder.dumpToFile(root)
                mainHandler.post { showToast("✅ Đã lưu UI tree") }
            } finally {
                runCatching { root.recycle() }
            }
        }
    }

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

    private suspend fun tapNodeByCoordinate(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) {
            logger.log(LogTag.CLICK, "tap", "ABORT_EMPTY_BOUNDS")
            return false
        }

        val x = (rect.centerX() + (-4..4).random()).toFloat()
        val y = (rect.centerY() + (-4..4).random()).toFloat()

        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 150))
            .build()

        return withTimeoutOrNull(600L) {
            suspendCancellableCoroutine { cont ->
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription) {
                        logger.log(LogTag.CLICK, "x=$x y=$y", "GESTURE_TAP_CANCELLED")
                        if (cont.isActive) cont.resume(false)
                    }
                }
                val dispatched = dispatchGesture(gesture, callback, null)
                if (!dispatched) {
                    logger.log(LogTag.CLICK, "x=$x y=$y", "GESTURE_DISPATCH_REJECTED")
                    cont.resume(false)
                }
            }
        } ?: run {
            logger.log(LogTag.CLICK, "x=$x y=$y rect=$rect", "GESTURE_TAP_TIMEOUT")
            false
        }
    }

    private fun performClickWithFallback(node: AccessibilityNodeInfo): Boolean {
        val snap = nodeSnapshotForLog(node)
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            logger.log(LogTag.CLICK, "target=self $snap", "ACTION_CLICK_OK")
            return true
        }
        logger.log(LogTag.CLICK, "target=self $snap", "ACTION_CLICK_FAIL")

        var parent: AccessibilityNodeInfo? = node.parent
        for (level in 1..6) {
            val p = parent ?: break
            val pSnap = nodeSnapshotForLog(p)
            if (p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                logger.log(LogTag.CLICK, "target=parent$level $pSnap", "ACTION_CLICK_OK")
                return true
            }
            logger.log(LogTag.CLICK, "target=parent$level $pSnap", "ACTION_CLICK_FAIL")
            parent = p.parent
        }
        logger.log(LogTag.CLICK, snap, "ACTION_CLICK_CHAIN_FAIL")
        return false
    }

    private fun syncDebugHighlightFromPrefsInternal() {
        mainHandler.post {
            if (!debugHighlightPrefs.isNodeHighlightEnabled()) {
                detachNodeHighlightOverlay()
            }
        }
    }

    private fun updateDebugNodeHighlights(
        candidates: List<AccessibilityNodeInfo>,
        clickTarget: AccessibilityNodeInfo?
    ) {
        if (!debugHighlightPrefs.isNodeHighlightEnabled()) {
            mainHandler.post { detachNodeHighlightOverlay() }
            return
        }
        val rects = ArrayList<Rect>(candidates.size)
        for (n in candidates) {
            val r = Rect()
            n.getBoundsInScreen(r)
            if (!r.isEmpty) rects.add(Rect(r))
        }
        val clickRect = clickTarget?.let { t ->
            val r = Rect()
            t.getBoundsInScreen(r)
            if (r.isEmpty) null else Rect(r)
        }
        mainHandler.post {
            try {
                val wm = windowManager ?: return@post
                if (nodeHighlightView == null) {
                    nodeHighlightView = NodeHighlightOverlayView(this)
                    val lp = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.START
                    }
                    wm.addView(nodeHighlightView, lp)
                }
                nodeHighlightView?.setHighlights(rects, clickRect)
            } catch (e: Exception) {
                logger.logError("updateDebugNodeHighlights", e)
            }
        }
    }

    private fun detachNodeHighlightOverlay() {
        mainHandler.post {
            val v = nodeHighlightView ?: return@post
            nodeHighlightView = null
            try {
                v.clearHighlights()
                windowManager?.removeView(v)
            } catch (e: Exception) {
                logger.logError("detachNodeHighlightOverlay", e)
            }
        }
    }
}
