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
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import java.io.File
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.zalopilot.app.data.model.ZaloIDStore
import com.zalopilot.app.util.LikeProgressManager
import com.zalopilot.app.util.LikeSettings
import com.zalopilot.app.util.DebugHighlightPrefs
import com.zalopilot.app.util.FeedMode
import com.zalopilot.app.util.InteractMode
import com.zalopilot.app.util.LikeSettingsManager
import com.zalopilot.app.util.LogTag
import com.zalopilot.app.util.Logger
import com.zalopilot.app.util.isZaloRelatedPackage
import com.zalopilot.app.util.hasValidScreenBounds
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.random.Random

@AndroidEntryPoint
class ZaloPilotAccessibilityService : AccessibilityService() {

    @Volatile
    private var startAutoLikeInProgress = false

    @Inject lateinit var nodeFinder: NodeFinder
    @Inject lateinit var idStore: ZaloIDStore
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
    /** Lần gần nhất poll thấy package thuộc Zalo (dùng để bỏ qua overlay tạm như heads-up). */
    private var lastZaloForegroundAtElapsedMs: Long = 0L
    private var consecutiveNullCount = 0
    private var consecutivePollRootNull = 0
    private val likedAuthorsThisSession = mutableSetOf<String>()
    private val clickedPositionsThisSession = mutableSetOf<String>()
    /** Đã like xong trong phiên — tránh like/unlike cùng bài khi rescan ngay. */
    private val processedPosts = mutableSetOf<String>()
    private var lastClickedPostKey = ""
    private var lastClickedPostAt = 0L
    /** Cuộn không đổi nội dung (đo bằng anchor / scroll action) — LIKED / ALL_SKIPPED. */
    private var consecutiveScrollNoProgress = 0
    /** Không thấy nút Thích (cây rỗng / lazy-load) — tách khỏi scroll-stuck, ngưỡng dừng riêng. */
    private var consecutiveEmptyLikeScanStreak = 0
    /** Vào nhầm màn full-screen "Bình luận" — đếm số lần liên tục BACK 2 lượt vẫn không thoát. */
    private var consecutiveStuckCommentScreen = 0

    /** Cập nhật từ `ACTION_BATTERY_CHANGED` — dùng cho toggle "chỉ chạy khi sạc" / "pause pin thấp". */
    @Volatile private var isCharging: Boolean = true
    @Volatile private var batteryPercent: Int = 100
    private var lastBatteryStatusLogMs: Long = 0L

    /** Lần đầu vào loop sau Start — chờ neo feed + settle. */
    private var initialFeedSettled = false
    /** Một lần dump ngắn khi scan fail (không spam mỗi vòng). */
    private var noButtonsDiagnosticDumpDone = false
    private var lastContentEventLogMs = 0L
    private var lastRootNullWallLogMs = 0L

    /** Tránh spam click cùng vùng bounds khi Zalo chưa kịp cập nhật UI. */
    private var lastLikeClickBoundsKey: String? = null
    private var lastLikeClickAttemptAtMs: Long = 0L

    /** Tránh dừng nhầm ngay sau GLOBAL_ACTION_BACK (transition animation). */
    private var lastGlobalBackAtElapsedMs: Long = 0L

    private var windowManager: WindowManager? = null
    private var statusView: LinearLayout? = null
    private var statusText: TextView? = null
    private var isOverlayShowing = false

    /** Viền debug nút like — tách khỏi status overlay. */
    private var nodeHighlightView: NodeHighlightOverlayView? = null

    private val powerManager: PowerManager
        get() = getSystemService(Context.POWER_SERVICE) as PowerManager

    companion object {
        var instance: ZaloPilotAccessibilityService? = null
        var isActive = false

        /** Broadcast: foreground notification "📋 Dump UI" → [performDumpUiTreeFromActiveWindow]. */
        const val ACTION_DUMP_UI_TREE = "com.zalopilot.DUMP_UI_TREE"

        /** MainActivity "Clear Logs" — xóa state debug tạm trong service. */
        const val ACTION_CLEAR_DEBUG_STATE = "com.zalopilot.CLEAR_DEBUG_STATE"

        /** Áp dụng ngay khi bật/tắt trong Cài đặt (không cần khởi động lại service). */
        fun syncDebugHighlightFromPrefs() {
            instance?.syncDebugHighlightFromPrefsInternal()
        }

        private const val POLL_MS_MIN = 1_000L
        private const val POLL_MS_MAX = 2_000L
        private const val ROOT_RETRY_DEFAULT = 5
        private const val POLL_ROOT_ASSUME_BACKGROUND = 6
        /** Màn tắt + bot chạy: root hay null nhưng Zalo vẫn foreground — đừng dừng sớm. */
        private const val POLL_ROOT_ASSUME_BACKGROUND_SCREEN_OFF = 22
        private const val CONTENT_EVENT_LOG_THROTTLE_MS = 3_000L
        private const val BOT_EVENT_LOG_THROTTLE_MS = 10_000L
        private const val ROOT_NULL_WALL_LOG_MS = 8_000L
        /** Heads-up/SystemUI overlay: không coi là rời Zalo ngay. */
        private const val TRANSIENT_OVERLAY_GRACE_MS = 6_000L
        /** Pause-rời-Zalo: chờ 5s rồi mới chuyển sang slow poll 10–20s (Fix #2 — option 2). */
        private const val ZALO_AWAY_SLOW_POLL_GRACE_MS = 5_000L
        /** Cooldown / chống double-click cùng bài (Zalo không cập nhật isChecked đáng tin). */
        private const val POST_CLICK_COOLDOWN_MS = 5_000L
        private const val DUPLICATE_LIKE_CLICK_SUPPRESS_MS = POST_CLICK_COOLDOWN_MS
        /** Dừng sau N lần cuộn mà feed không dịch (like / all-skipped). */
        private const val FEED_END_STOP_STREAK = 5
        /** NO_BUTTONS: nhiều lần thử trước khi coi hết feed (lazy-load / RecyclerView). */
        private const val NO_BUTTONS_END_STOP_STREAK = 24
        /** Kẹt màn "Bình luận" — sau N lần BACK liên tục không thoát thì dừng bot, nhờ user xử lý. */
        private const val STUCK_COMMENT_SCREEN_STOP_STREAK = 3
    }

    private enum class GestureScrollProfile {
        /** Cuộn ngắn, mượt */
        SMALL,
        NORMAL,
        /** Kẹt feed — vuốt dài hơn */
        LARGE
    }

    private fun combinedStuckLevel(): Int =
        maxOf(consecutiveScrollNoProgress, consecutiveEmptyLikeScanStreak / 4)

    private fun isTransientOverlayPackage(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.contains("systemui") ||
            p == "android" ||
            p.contains("launcher")
    }

    /**
     * Scope broadcast về **chính package** của app — vì receiver đăng ký với
     * `RECEIVER_NOT_EXPORTED` (Android 13+), không setPackage thì broadcast không tới được
     * `MainActivity` / `FloatingMenuService` → counter "Đã like" không tự update.
     */
    private fun sendInternalBroadcast(intent: Intent) {
        sendBroadcast(intent.setPackage(packageName))
    }

    private fun gestureProfileForStreak(): GestureScrollProfile = when {
        combinedStuckLevel() >= 3 -> GestureScrollProfile.LARGE
        combinedStuckLevel() >= 1 -> GestureScrollProfile.NORMAL
        else -> GestureScrollProfile.SMALL
    }

    /**
     * Cuộn feed có nên ưu tiên **vuốt tay (gesture)** thay vì `ACTION_SCROLL_FORWARD` không.
     *  - `humanLikeScroll = true` (toggle Cài đặt) → luôn vuốt tay.
     *  - InteractMode TAP → vuốt tay (đồng nhất với "Touch" cho click like).
     *  - MIX → random 50/50.
     */
    private fun preferGestureScroll(settings: LikeSettings, interactMode: InteractMode): Boolean =
        settings.humanLikeScroll || when (interactMode) {
            InteractMode.TAP -> true
            InteractMode.SWIPE -> false
            InteractMode.MIX -> (1..2).random() == 1
        }

    private val dumpUiTreeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_DUMP_UI_TREE) return
            performDumpUiTreeFromActiveWindow()
        }
    }

    private val clearDebugStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_CLEAR_DEBUG_STATE) return
            lastContentEventLogMs = 0L
            lastRootNullWallLogMs = 0L
            uiScanner.resetTransientState()
        }
    }

    /** `ACTION_BATTERY_CHANGED` là sticky broadcast — Android tự gửi mỗi khi pin/sạc đổi. */
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateBatteryStateFromIntent(intent)
        }
    }

    private fun updateBatteryStateFromIntent(intent: Intent?) {
        if (intent == null) return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) {
            batteryPercent = (level * 100f / scale).toInt().coerceIn(0, 100)
        }
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        isCharging = plugged != 0
        val now = SystemClock.elapsedRealtime()
        if (now - lastBatteryStatusLogMs > 30_000L) {
            lastBatteryStatusLogMs = now
            logger.log(LogTag.STATE, "battery=$batteryPercent% charging=$isCharging", "BATTERY_STATE")
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
                registerReceiver(
                    clearDebugStateReceiver,
                    IntentFilter(ACTION_CLEAR_DEBUG_STATE),
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(dumpUiTreeReceiver, IntentFilter(ACTION_DUMP_UI_TREE))
                @Suppress("DEPRECATION")
                registerReceiver(clearDebugStateReceiver, IntentFilter(ACTION_CLEAR_DEBUG_STATE))
            }
        }.onFailure { e -> logger.logError("registerDumpUiReceiver", e) }

        // Battery receiver — `ACTION_BATTERY_CHANGED` là sticky, không cần khai báo flag NOT_EXPORTED
        // (system broadcast). Đăng ký với null cũng trả về intent sticky hiện tại để init state.
        runCatching {
            val sticky = registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            updateBatteryStateFromIntent(sticky)
        }.onFailure { e -> logger.logError("registerBatteryReceiver", e) }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        progressManager.resetDailyIfNeeded()
        logger.log(LogTag.STATE, "service poll=${POLL_MS_MIN}-${POLL_MS_MAX}ms", "CONNECTED")
        showToast("✅ ZaloPilot đã kết nối")

        foregroundPollJob?.cancel()
        foregroundPollJob = scope.launch {
            while (isActive) {
                delay(computePollDelayMs())
                runCatching { pollOnce() }.onFailure { e ->
                    logger.logError("pollLoop", e)
                }
            }
        }
    }

    /** Poll chậm hơn khi Eco / màn tắt + bot chạy → ít thức CPU, bớt nóng. */
    private fun computePollDelayMs(): Long {
        val eco = settingsManager.isEcoMode()
        val screenOff = !powerManager.isInteractive
        // Grace 5s sau khi rời Zalo: nếu user vô tình mở thông báo / app khác rồi quay lại,
        // poll vẫn fast trong 5s đầu để bot resume ngay; quá 5s mới chuyển sang slow poll 10–20s.
        val sinceZaloMs = SystemClock.elapsedRealtime() - lastZaloForegroundAtElapsedMs
        val zaloAwayLong = !isZaloForeground &&
            isRunning &&
            settingsManager.isPauseWhenZaloAway() &&
            sinceZaloMs > ZALO_AWAY_SLOW_POLL_GRACE_MS
        val r = when {
            zaloAwayLong -> 10_000L..20_000L
            screenOff && isRunning ->
                if (eco) 5200L..9000L else 4000L..7000L
            eco -> 2200L..4000L
            else -> POLL_MS_MIN..POLL_MS_MAX
        }
        return Random.nextLong(r.first, r.last + 1)
    }

    private fun scaleEcoRange(range: LongRange): LongRange {
        if (!settingsManager.isEcoMode()) return range
        val a = (range.first * 3L + 1L) / 2L
        val b = (range.last * 3L + 1L) / 2L
        return a.coerceAtLeast(1L)..maxOf(a, b)
    }

    private fun pickScaled(range: LongRange): Long {
        val s = scaleEcoRange(range)
        return Random.nextLong(s.first, s.last + 1)
    }

    private suspend fun delayEco(range: LongRange) {
        delay(pickScaled(range))
    }

    private fun ecoVerifyMs(base: Long): Long =
        if (settingsManager.isEcoMode()) (base * 115L / 100L) else base

    /** Sau cuộn/gesture: chờ feed RecyclerView ổn định trước khi quét lại (lazy-load). */
    private suspend fun delayFeedSettleAfterScroll() {
        delayEco(800L..1_500L)
    }

    /**
     * Trước khi quét like: nếu cây chưa có nút Thích, chờ có giới hạn + lấy root mới — tránh empty scan sớm.
     * [first] được recycle nếu trả về root khác (caller gán lại biến `root`).
     */
    private suspend fun awaitFeedLikeScanRoot(first: AccessibilityNodeInfo): AccessibilityNodeInfo {
        if (nodeFinder.findLikeButtons(first).isNotEmpty()) return first
        // Tăng retry từ 4 → 8, delay dài hơn để chờ RecyclerView lazy-load của Zalo.
        // Tổng chờ tối đa ~7s thay vì ~2.2s — đủ cho màn hình yếu / feed nặng.
        for (attempt in 0 until 8) {
            val waitRange = if (attempt < 3) 500L..800L else 700L..900L
            delayEco(waitRange)
            val next = acquireRootOrNull(
                maxAttempts = 4,
                delayRangeMs = 80L..220L,
                logTag = LogTag.STATE,
                quietLog = true
            ) ?: continue
            if (nodeFinder.findLikeButtons(next).isNotEmpty()) {
                runCatching { first.recycle() }
                logger.log(LogTag.SCAN, "lazy_load_wait attempt=$attempt", "FEED_READY")
                return next
            }
            runCatching { next.recycle() }
        }
        logger.log(LogTag.SCAN, "awaitFeedLikeScanRoot", "TIMEOUT_NO_BUTTONS")
        return first
    }

    /**
     * Nếu [root] đang là màn **full-screen Bình luận**, BACK tối đa 2 lượt để về feed.
     * Trả về `true` nếu đã xử lý (caller nên `continue` vòng và lấy root mới).
     * Đạt [STUCK_COMMENT_SCREEN_STOP_STREAK] → stop bot để user thoát thủ công.
     */
    private suspend fun tryEscapeCommentScreen(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        if (!nodeFinder.isFullScreenCommentScreen(root)) {
            consecutiveStuckCommentScreen = 0
            return false
        }
        logger.log(LogTag.STATE, "comment_screen", "DETECTED_BACK")
        updateStatus("↩️ Phát hiện màn Bình luận — back về feed")

        suspend fun pressBackAndCheck(): Boolean {
            performGlobalAction(GLOBAL_ACTION_BACK)
            lastGlobalBackAtElapsedMs = SystemClock.elapsedRealtime()
            delayEco(700L..1100L)
            val r = acquireRootOrNull(4, 80L..220L, LogTag.STATE, quietLog = true)
            return try {
                r != null && nodeFinder.isFullScreenCommentScreen(r)
            } finally {
                runCatching { r?.recycle() }
            }
        }

        var stillStuck = pressBackAndCheck()
        if (stillStuck) stillStuck = pressBackAndCheck()

        if (stillStuck) {
            consecutiveStuckCommentScreen++
            logger.log(
                LogTag.STATE,
                "stuckStreak=$consecutiveStuckCommentScreen",
                "STUCK_COMMENT_SCREEN"
            )
            if (consecutiveStuckCommentScreen >= STUCK_COMMENT_SCREEN_STOP_STREAK) {
                showToast("⚠️ Kẹt màn Bình luận — vui lòng thoát thủ công")
                logger.log(LogTag.STATE, "autoLike", "STOP_STUCK_COMMENT_SCREEN")
                stopAutoLike()
            }
        } else {
            consecutiveStuckCommentScreen = 0
            logger.log(LogTag.STATE, "comment_screen", "ESCAPED_BACK_OK")
        }
        return true
    }

    /**
     * Battery-aware pause: rút sạc (`requireCharging`) hoặc pin tụt dưới ngưỡng
     * (`lowBatteryPauseEnabled` + `lowBatteryThreshold`) thì **chờ tại chỗ** rồi chạy tiếp.
     * Không stop bot — để cắm lại/pin sạc lên là loop tự đi tiếp.
     *
     * @return `true` nếu vừa pause (caller `continue`), `false` nếu OK chạy.
     */
    private suspend fun waitForBatteryConditionsIfNeeded(): Boolean {
        val s = settingsManager.load()
        val needCharger = s.requireCharging && !isCharging
        val lowBattery = s.lowBatteryPauseEnabled && batteryPercent in 0 until s.lowBatteryThreshold
        if (!needCharger && !lowBattery) return false

        val reason = when {
            needCharger && lowBattery ->
                "🔌 Pin ${batteryPercent}% + chưa sạc — chờ cắm sạc lại"
            needCharger ->
                "🔌 Đã rút sạc — chờ cắm lại để chạy tiếp"
            else ->
                "🔋 Pin ${batteryPercent}% < ${s.lowBatteryThreshold}% — chờ pin lên / cắm sạc"
        }
        updateStatus(reason)
        logger.log(
            LogTag.STATE,
            "battery=$batteryPercent% charging=$isCharging needCharger=$needCharger lowBattery=$lowBattery",
            "BATTERY_PAUSE"
        )
        // Sleep dài (15s) giữa các lần check — đỡ tốn pin trong lúc đang pause vì pin.
        delay(15_000L)
        return true
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
            val nullStreakLimit = when {
                !powerManager.isInteractive && isRunning && isZaloForeground ->
                    POLL_ROOT_ASSUME_BACKGROUND_SCREEN_OFF
                else -> POLL_ROOT_ASSUME_BACKGROUND
            }
            if (consecutivePollRootNull >= nullStreakLimit && isZaloForeground) {
                logger.log(LogTag.STATE, "root persistently null limit=$nullStreakLimit", "BACKGROUND_ASSUMED")
                applyZaloBackground(reason = "poll_root_exhausted")
            }
            return
        }

        consecutivePollRootNull = 0
        val pkg = root.packageName?.toString() ?: ""
        logger.setForegroundPackage(pkg)
        val inZalo = isZaloRelatedPackage(pkg)

        if (inZalo) {
            lastZaloForegroundAtElapsedMs = SystemClock.elapsedRealtime()
            if (!isZaloForeground) {
                logger.log(LogTag.STATE, "pkg=$pkg", "FOREGROUND_POLL")
                sendInternalBroadcast(Intent("com.zalopilot.ZALO_STATE").putExtra("foreground", true))
                isZaloForeground = true
                // Đang resume sau pause-rời-Zalo: bật lại KEEP_SCREEN_ON để Accessibility đọc feed ổn định.
                if (isRunning) {
                    updateStatus("▶ Tiếp tục — Zalo đã quay lại")
                    applyKeepScreenOnToStatusOverlayIfNeeded()
                }
                if (settingsManager.isAutoStart() && !isRunning
                    && !progressManager.isLimitReached()
                    && !settingsManager.isQuietHour()) {
                    mainHandler.post { startAutoLike() }
                }
            }
            // Bot đang chạy: vòng autoLikeLoop tự scan + settle sau cuộn — tránh scan sớm khi lazy-load.
            if (!isRunning) {
                uiScanner.scan(root)
            }
        } else {
            if (isZaloForeground) {
                // Grace period 1s sau BACK: tránh false-positive "Zalo closed" khi transition về feed.
                val sinceBack = SystemClock.elapsedRealtime() - lastGlobalBackAtElapsedMs
                if (lastGlobalBackAtElapsedMs > 0L && sinceBack in 0L..1_000L) {
                    logger.log(LogTag.STATE, "pkg=$pkg sinceBackMs=$sinceBack", "BACKGROUND_POLL_GRACE")
                    return
                }
                // Heads-up notification / SystemUI overlay: giữ running, đừng stop/pause ngay.
                val sinceZalo = SystemClock.elapsedRealtime() - lastZaloForegroundAtElapsedMs
                if (isRunning && isTransientOverlayPackage(pkg) && sinceZalo in 0L..TRANSIENT_OVERLAY_GRACE_MS) {
                    logger.log(LogTag.STATE, "pkg=$pkg sinceZaloMs=$sinceZalo", "TRANSIENT_OVERLAY_IGNORE")
                    return
                }
                logger.log(LogTag.STATE, "pkg=$pkg", "BACKGROUND_POLL")
                applyZaloBackground(reason = "poll_other_package")
            }
        }
    }

    private fun applyZaloBackground(reason: String) {
        // Grace period 1s sau GLOBAL_ACTION_BACK: tránh stop nhầm khi transition/animation làm window-state nhảy sang launcher.
        val sinceBack = SystemClock.elapsedRealtime() - lastGlobalBackAtElapsedMs
        if (lastGlobalBackAtElapsedMs > 0L && sinceBack in 0L..1_000L) {
            logger.log(LogTag.STATE, "reason=$reason sinceBackMs=$sinceBack", "BACKGROUND_IGNORED_GRACE")
            return
        }
        isZaloForeground = false
        sendInternalBroadcast(Intent("com.zalopilot.ZALO_STATE").putExtra("foreground", false))
        if (isRunning) {
            if (settingsManager.isPauseWhenZaloAway()) {
                // Bot vẫn chạy nhưng autoLikeLoop sẽ tự chờ Zalo về (slow poll qua computePollDelayMs).
                logger.log(LogTag.STATE, "reason=$reason", "PAUSED_ZALO_AWAY_KEEP_BOT")
                updateStatus("⏸ Đã rời Zalo — chờ mở lại")
                // Quan trọng: bỏ KEEP_SCREEN_ON để màn được tự tắt — đúng tinh thần "tiết kiệm khi rời Zalo".
                removeKeepScreenOnFromStatusOverlay()
                return
            }
            stopAutoLike()
            logger.log(LogTag.STATE, "reason=$reason", "PAUSED_ZALO_CLOSED")
        }
        hideStatusOverlay()
    }

    /**
     * Lấy [rootInActiveWindow] với vài lần thử.
     * @param quietLog khi true (vòng bot): bớt log từng attempt — chỉ ROOT_GIVEUP.
     */
    private suspend fun acquireRootOrNull(
        maxAttempts: Int = ROOT_RETRY_DEFAULT,
        delayRangeMs: LongRange = 100L..300L,
        logTag: LogTag = LogTag.POLL,
        quietLog: Boolean = false
    ): AccessibilityNodeInfo? {
        repeat(maxAttempts) { attempt ->
            val root = rootInActiveWindow
            if (root != null) {
                val pkg = root.packageName?.toString() ?: ""
                logger.setForegroundPackage(pkg)
                if (!quietLog) {
                    logger.log(logTag, "attempt=${attempt + 1}/$maxAttempts pkg=$pkg", "ROOT_OK")
                }
                return root
            }
            if (!quietLog) {
                logger.log(logTag, "attempt=${attempt + 1}/$maxAttempts", "ROOT_NULL_RETRY")
            }
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

        val eventLogThrottleMs =
            if (isRunning) BOT_EVENT_LOG_THROTTLE_MS else CONTENT_EVENT_LOG_THROTTLE_MS
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val now = System.currentTimeMillis()
                if (now - lastContentEventLogMs > eventLogThrottleMs) {
                    lastContentEventLogMs = now
                    logger.log(LogTag.EVENT_HINT, "type=$label pkg=$pkg class=$className", "CONTENT_THROTTLED")
                }
            }
            else -> {
                val now = System.currentTimeMillis()
                if (!isRunning || now - lastContentEventLogMs > BOT_EVENT_LOG_THROTTLE_MS) {
                    if (isRunning) lastContentEventLogMs = now
                    logger.log(LogTag.EVENT_HINT, "type=$label pkg=$pkg class=$className", "HINT")
                }
            }
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (isZaloRelatedPackage(pkg)) {
                logger.setForegroundPackage(pkg)
                uiScanner.requestHintRescan()
            } else if (isZaloForeground) {
                val sinceZalo = SystemClock.elapsedRealtime() - lastZaloForegroundAtElapsedMs
                if (isRunning && isTransientOverlayPackage(pkg) && sinceZalo in 0L..TRANSIENT_OVERLAY_GRACE_MS) {
                    logger.log(LogTag.STATE, "pkg=$pkg sinceZaloMs=$sinceZalo", "TRANSIENT_OVERLAY_EVENT_IGNORE")
                    return
                }
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
        runCatching { unregisterReceiver(clearDebugStateReceiver) }
            .onFailure { e -> logger.logError("unregisterClearDebugReceiver", e) }
        runCatching { unregisterReceiver(batteryReceiver) }
            .onFailure { e -> logger.logError("unregisterBatteryReceiver", e) }
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
            sendInternalBroadcast(Intent("com.zalopilot.DAILY_LIMIT"))
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

                withContext(Dispatchers.IO) {
                    logger.beginAutomationSession()
                }

                logger.log(LogTag.STATE, "pkg=$pkg", "START_CLICKED")
                uiScanner.forceScan(root)

                isRunning = true
                sessionLikeCount = 0
                consecutiveNullCount = 0
                consecutivePollRootNull = 0
                likedAuthorsThisSession.clear()
                clickedPositionsThisSession.clear()
                processedPosts.clear()
                lastClickedPostKey = ""
                lastClickedPostAt = 0L
                consecutiveScrollNoProgress = 0
                consecutiveEmptyLikeScanStreak = 0
                initialFeedSettled = false
                noButtonsDiagnosticDumpDone = false

                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZaloPilot:AutoLike")
                wakeLock?.acquire(10 * 60 * 60 * 1000L)

                sendInternalBroadcast(Intent("com.zalopilot.STATUS_UPDATE").putExtra("running", true))
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
        sendInternalBroadcast(Intent("com.zalopilot.STATUS_UPDATE").putExtra("running", false))
        logger.log(LogTag.STATE, "autoLike", "STOPPED")
        showToast("■ Đã dừng")
        hideStatusOverlay()
        detachNodeHighlightOverlay()
    }

    private suspend fun autoLikeLoop() {
        mainLoop@ while (isRunning) {
            // Battery-aware pause: rút sạc / pin thấp → đứng tại chỗ chờ, không stop để
            // khi cắm lại / pin sạc lên là chạy tiếp luôn.
            if (waitForBatteryConditionsIfNeeded()) {
                continue@mainLoop
            }

            if (progressManager.isLimitReached()) {
                val count = progressManager.load().todayLikeCount
                updateStatus("✅ Đã like đủ $count bài hôm nay")
                showToast("✅ Đã like đủ $count bài hôm nay!")
                logger.log(LogTag.STATE, "autoLike", "DAILY_LIMIT_REACHED")
            sendInternalBroadcast(Intent("com.zalopilot.DAILY_LIMIT"))
            stopAutoLike()
            return
        }

        if (!isZaloForeground) {
                if (settingsManager.isPauseWhenZaloAway()) {
                    updateStatus("⏸ Đã rời Zalo — chờ mở lại")
                    logger.log(LogTag.STATE, "autoLike", "ZALO_AWAY_PAUSED")
                    delay(8_000L)
                    continue@mainLoop
                }
                logger.log(LogTag.STATE, "autoLike", "ZALO_NOT_FOREGROUND")
                stopAutoLike()
                return
            }

            val settings = settingsManager.load()

            if (sessionLikeCount >= settings.sessionLimit) {
                val restMs = pickScaled(
                    (settings.restMinMinutes * 60_000L)..(settings.restMaxMinutes * 60_000L)
                )
                val restMin = restMs / 60000
                updateStatus("😴 Nghỉ $restMin phút (đã like $sessionLikeCount bài)")
                showToast("😴 Nghỉ $restMin phút cho tự nhiên")
                logger.log(LogTag.STATE, "Nghỉ $restMin phút", "SESSION_REST")
                sessionLikeCount = 0
                likedAuthorsThisSession.clear()
                delay(restMs)
                continue
            }

            var root: AccessibilityNodeInfo? = acquireRootOrNull(
                maxAttempts = 5,
                delayRangeMs = 100L..280L,
                logTag = LogTag.STATE,
                quietLog = true
            )

            if (root == null) {
                consecutiveNullCount++
                updateStatus("⚠️ Zalo không phản hồi ($consecutiveNullCount lần)")
                logger.log(LogTag.STATE, "consecutiveNull=$consecutiveNullCount", "ROOT_NULL_LOOP")
                if (consecutiveNullCount >= 3) {
                    showToast("⚠️ Không đọc được màn hình Zalo — đang thử lại")
                    logger.log(LogTag.STATE, "root null streak", "ZALO_SLEEP")
                }
                delayEco(1_000L..2_000L)
                continue
            }

            consecutiveNullCount = 0

            try {
                if (!initialFeedSettled) {
                    if (rootContainsFeedAnchor(root!!)) {
                        delayEco(1_000L..2_000L)
                    } else {
                        waitForFeedLayoutRendered()
                    }
                    runCatching { root!!.recycle() }
                    val refreshed = acquireRootOrNull(
                        maxAttempts = 5,
                        delayRangeMs = 120L..300L,
                        logTag = LogTag.STATE,
                        quietLog = true
                    )
                    if (refreshed == null) {
                        delayEco(400L..800L)
                        root = null
                        continue@mainLoop
                    }
                    root = refreshed
                    initialFeedSettled = true
                }

                root = awaitFeedLikeScanRoot(root!!)
                val liveRoot = root!!

                // Vào nhầm full-screen "Bình luận" (vd. tap nhầm icon comment) → BACK + reload root.
                if (tryEscapeCommentScreen(liveRoot)) {
                    if (!isRunning) return
                    runCatching { root?.recycle() }
                    root = null
                    delayEco(400L..800L)
                    continue@mainLoop
                }

                if (!uiScanner.hasScannedRecently()) {
                    updateStatus("🔍 Đang scan giao diện Zalo...")
                    uiScanner.scan(liveRoot)
                }

                val scrollProf = gestureProfileForStreak()
                val scanResult = runFeedMode(liveRoot, settings)
                val feedMode = settingsManager.getFeedMode()
                val interactMode = settingsManager.getInteractMode()
                val preferGesture = preferGestureScroll(settings, interactMode)

                when (scanResult) {
                    FeedScanResult.LIKED -> {
                        consecutiveEmptyLikeScanStreak = 0
                        delayEco(1_000L..1_800L)
                        var feedMoved = true
                        var didAutoScroll = false
                        when (feedMode) {
                            FeedMode.SCROLL -> {
                                feedMoved = scrollFeedWithVerification(liveRoot, scrollProf, preferGesture = preferGesture)
                                didAutoScroll = true
                            }
                            FeedMode.MANUAL -> {
                                updateStatus("✋ Manual mode — vuốt tay để tiếp tục")
                                logger.log(LogTag.SCROLL, "manual mode, skip auto scroll", "MANUAL")
                            }
                            FeedMode.MIX -> {
                                if ((1..10).random() <= 6) {
                                    feedMoved = scrollFeedWithVerification(liveRoot, scrollProf, preferGesture = preferGesture)
                                    didAutoScroll = true
                                } else {
                                    updateStatus("✋ Mix mode — chờ vuốt tay...")
                                    logger.log(LogTag.SCROLL, "mix mode, skip this scroll", "MIX_SKIP")
                                    delayEco(800L..1_500L)
                                }
                            }
                        }
                        processedPosts.clear()
                        if (!feedMoved) {
                            consecutiveScrollNoProgress++
                            logger.log(
                                LogTag.SCROLL,
                                "consecutiveNoProgress=$consecutiveScrollNoProgress",
                                "AFTER_LIKED"
                            )
                            if (consecutiveScrollNoProgress >= FEED_END_STOP_STREAK) {
                                showToast("✅ Đã đến cuối feed — dừng tự động")
                                logger.log(LogTag.STATE, "autoLike", "STOP_END_FEED_NO_SCROLL")
                                stopAutoLike()
                                return
                            }
                        } else {
                            consecutiveScrollNoProgress = 0
                        }
                        if (didAutoScroll) {
                            delayFeedSettleAfterScroll()
                        } else {
                            delayEco(400L..1_000L)
                        }
                        val dMin = if (settingsManager.isEcoMode()) {
                            (settings.delayMinMs * 3L + 1L) / 2L
                        } else settings.delayMinMs
                        val dMax = if (settingsManager.isEcoMode()) {
                            (settings.delayMaxMs * 3L + 1L) / 2L
                        } else settings.delayMaxMs
                        randomDelay(dMin, dMax)
                        logger.log(LogTag.STATE, "feedMode=$feedMode interactMode=$interactMode", "LOOP_PARAMS")
                    }
                    FeedScanResult.ALL_SKIPPED -> {
                        consecutiveEmptyLikeScanStreak = 0
                        logger.log(LogTag.SCROLL, "all skipped, fast scroll feedMode=$feedMode", "ALL_SKIPPED")
                        updateStatus("⏩ Bài đã like — cuộn tiếp...")
                        val feedMoved = scrollFeedWithVerification(liveRoot, scrollProf, preferGesture = preferGesture)
                        processedPosts.clear()
                        if (!feedMoved) {
                            consecutiveScrollNoProgress++
                            logger.log(
                                LogTag.SCROLL,
                                "consecutiveNoProgress=$consecutiveScrollNoProgress",
                                "ALL_SKIPPED"
                            )
                            if (consecutiveScrollNoProgress >= FEED_END_STOP_STREAK) {
                                showToast("✅ Đã đến cuối feed — dừng tự động")
                                logger.log(LogTag.STATE, "autoLike", "STOP_END_FEED_NO_SCROLL")
                                stopAutoLike()
                                return
                            }
                        } else {
                            consecutiveScrollNoProgress = 0
                        }
                        delayFeedSettleAfterScroll()
                    }
                    FeedScanResult.NO_BUTTONS -> {
                        updateStatus("🔍 Chưa thấy nút Thích — cuộn nhẹ, sẽ quét lại...")
                        var feedMoved = scrollFeedWithVerification(liveRoot, scrollProf, preferGesture = preferGesture)
                        if (!feedMoved) {
                            delayEco(240L..420L)
                            feedMoved = scrollDownByGesture(GestureScrollProfile.SMALL)
                            delay(ecoVerifyMs(450L))
                        }
                        processedPosts.clear()
                        if (!feedMoved) {
                            consecutiveEmptyLikeScanStreak++
                            logger.log(
                                LogTag.SCROLL,
                                "emptyLikeStreak=$consecutiveEmptyLikeScanStreak",
                                "NO_BUTTONS_NO_SCROLL"
                            )
                            if (consecutiveEmptyLikeScanStreak >= NO_BUTTONS_END_STOP_STREAK) {
                                showToast("✅ Không còn nội dung mới — dừng tự động")
                                logger.log(LogTag.STATE, "autoLike", "STOP_END_FEED_NO_NEW_CONTENT")
                                stopAutoLike()
                                return
                            }
                        } else {
                            consecutiveEmptyLikeScanStreak = 0
                        }
                        delayFeedSettleAfterScroll()
                    }
                }
            } finally {
                runCatching { root?.recycle() }
            }
        }
    }

    // Kết quả 1 lần scan feed
    private enum class FeedScanResult {
        LIKED,          // Đã like được ít nhất 1 bài
        ALL_SKIPPED,    // Thấy nút Thích nhưng tất cả đã like → scroll nhanh, không đếm empty
        NO_BUTTONS      // Không thấy nút Thích nào → có thể hết feed hoặc sai tab
    }

    private suspend fun runFeedMode(
        root: AccessibilityNodeInfo,
        @Suppress("UNUSED_PARAMETER") settings: LikeSettings
    ): FeedScanResult {
        var scanRoot = root
        var acquiredExtra: AccessibilityNodeInfo? = null
        try {
            var likeNodes: List<AccessibilityNodeInfo> = emptyList()
            repeat(4) { attempt ->
                likeNodes = nodeFinder.findLikeButtons(scanRoot)
                if (likeNodes.isNotEmpty()) return@repeat
                if (attempt < 3) {
                    delayEco(500L..900L)
                    val next = acquireRootOrNull(
                        4,
                        80L..240L,
                        LogTag.SCAN,
                        quietLog = true
                    )
                    if (next != null) {
                        acquiredExtra?.recycle()
                        acquiredExtra = next
                        scanRoot = next
                    }
                }
            }

            // Side-effect debug dump for feed items (1-time per file) — không đổi logic like.
            runCatching { maybeDumpFeedItemTrees(scanRoot) }
                .onFailure { e -> logger.logError("maybeDumpFeedItemTrees", e) }

            if (likeNodes.isEmpty()) {
                updateDebugNodeHighlights(emptyList(), null)
                if (nodeFinder.hasVisibleSelfAlreadyLikedLikeControl(scanRoot)) {
                    updateStatus("⏩ Bạn đã thích các bài trên màn hình — cuộn tiếp...")
                    logger.log(LogTag.SCAN, "feed", "EMPTY_BUT_SELF_ALREADY_LIKED_TREAT_AS_ALL_SKIPPED")
                    return FeedScanResult.ALL_SKIPPED
                }
                updateStatus("🔍 Không thấy nút Thích trên màn hình này")
                logger.log(LogTag.SCAN, "feed like buttons", "EMPTY_AFTER_RETRY")
                when {
                    debugHighlightPrefs.isVerboseUiTreeLoggingEnabled() ->
                        nodeFinder.debugDump(scanRoot, maxNodes = 400)
                    !noButtonsDiagnosticDumpDone -> {
                        noButtonsDiagnosticDumpDone = true
                        logger.log(LogTag.SCAN, "scan_fail", "ONE_SHOT_DIAGNOSTIC_DUMP")
                        nodeFinder.debugDump(scanRoot, maxNodes = 220)
                    }
                }
                return FeedScanResult.NO_BUTTONS
            }

            updateDebugNodeHighlights(likeNodes, null)

            var skippedShouldLike = 0
            for (node in likeNodes) {
                if (!isRunning) break
                if (!nodeFinder.shouldLike(node)) {
                    skippedShouldLike++
                    progressManager.incrementPostsHandledAndSave()
                    sendInternalBroadcast(Intent("com.zalopilot.PROGRESS_UPDATE"))
                    continue
                }

                val author = nodeFinder.getAuthorName(node)
                if (author != null && likedAuthorsThisSession.contains(author)) {
                    logger.log(LogTag.STATE, author, "SKIP_AUTHOR_ALREADY_LIKED")
                    progressManager.incrementPostsHandledAndSave()
                    sendInternalBroadcast(Intent("com.zalopilot.PROGRESS_UPDATE"))
                    continue
                }

                val postKey = makePostKey(node)
                val rect = Rect()
                node.getBoundsInScreen(rect)

                if (postKey in processedPosts) {
                    logger.log(LogTag.STATE, postKey, "SKIP_ALREADY_PROCESSED")
                    continue
                }

                val nowMs = System.currentTimeMillis()
                if (postKey == lastClickedPostKey && nowMs - lastClickedPostAt < POST_CLICK_COOLDOWN_MS) {
                    logger.log(
                        LogTag.STATE,
                        "postKey=$postKey deltaMs=${nowMs - lastClickedPostAt}",
                        "SKIP_COOLDOWN"
                    )
                    continue
                }

                logAutoBeforeClick(postKey, node, rect)

                updateStatus("👍 Đang like bài của ${author ?: "..."}...")
                delayEco(200L..500L)

                updateDebugNodeHighlights(likeNodes, node)

                var microRoot: AccessibilityNodeInfo? =
                    acquireRootOrNull(3, 80L..200L, LogTag.SCAN, quietLog = true)
                val nodeForClick = microRoot?.let { nodeFinder.reResolveLikeNodeForClick(it, node) }
                    ?: nodeFinder.reResolveLikeNodeForClick(scanRoot, node)
                    ?: node
                if (nodeForClick !== node) {
                    logger.log(LogTag.STATE, boundsSummary(nodeForClick), "RE_RESOLVED_BEFORE_CLICK")
                }
                // Snapshot composer TRƯỚC click — dùng so delta sau click để chống unlike nhầm
                // khi UI Zalo không lộ trạng thái "đã thích" (isAlreadyLiked sai).
                val composerBefore = runCatching {
                    nodeFinder.hasInlineCommentComposerNearLikeAnchor(nodeForClick)
                }.getOrDefault(false)
                logger.log(LogTag.CLICK, "composerBefore=$composerBefore", "COMPOSER_SNAPSHOT")

                try {
                    val clicked = performLikeClickWithFallbacks(nodeForClick)

                    updateDebugNodeHighlights(likeNodes, null)

                    if (clicked) {
                        delayEco(240L..420L)
                        val peekRoot = acquireRootOrNull(3, 60L..180L, LogTag.CLICK, quietLog = true)
                        if (peekRoot != null) {
                            try {
                                val dm = resources.displayMetrics
                                if (nodeFinder.isLikelyZaloImageViewer(
                                        peekRoot,
                                        dm.widthPixels,
                                        dm.heightPixels
                                    )
                                ) {
                                    logger.log(
                                        LogTag.CLICK,
                                        author ?: "unknown",
                                        "IMAGE_VIEWER_BACK_SKIP_POST"
                                    )
                                    updateStatus("🖼 Mở nhầm viewer — bỏ qua bài này")
                                    progressManager.incrementPostsHandledAndSave()
                                    sendInternalBroadcast(Intent("com.zalopilot.PROGRESS_UPDATE"))
                                    delayEco(400L..700L)
                                    continue
                                }

                                // Click rơi vào full-screen "Bình luận" (tap nhầm icon comment) → BACK + skip.
                                if (nodeFinder.isFullScreenCommentScreen(peekRoot)) {
                                    logger.log(
                                        LogTag.CLICK,
                                        author ?: "unknown",
                                        "COMMENT_SCREEN_BACK_SKIP_POST"
                                    )
                                    updateStatus("💬 Mở nhầm Bình luận — back & bỏ qua bài này")
                                    tryEscapeCommentScreen(peekRoot)
                                    progressManager.incrementPostsHandledAndSave()
                                    sendInternalBroadcast(Intent("com.zalopilot.PROGRESS_UPDATE"))
                                    delayEco(400L..700L)
                                    continue
                                }
                            } finally {
                                runCatching { peekRoot.recycle() }
                            }
                        }

                        // Đợi Zalo animate / cập nhật state (checked/selected), không tin mỗi text "Thích".
                        delay(ecoVerifyMs(1500L))

                        // Snapshot bounds + id của node trước click để verify ở fresh root mà KHÔNG bị
                        // findLikeButtons lọc mất (sau like nút thành "Đã thích" → findLikeButtons bỏ qua).
                        val origRectForVerify = Rect().apply { nodeForClick.getBoundsInScreen(this) }
                        val origIdForVerify = nodeForClick.viewIdResourceName

                        // Verify trên root mới — finder mới KHÔNG lọc theo isAlreadyLiked, dùng id whitelist + bounds.
                        suspend fun resolvedLikeAreaOnFreshRoot(): AccessibilityNodeInfo? {
                            val fresh = acquireRootOrNull(4, 80L..220L, LogTag.CLICK, quietLog = true) ?: return null
                            return try {
                                nodeFinder.findLikeAreaNodeAt(fresh, origRectForVerify, origIdForVerify)
                            } finally {
                                runCatching { fresh.recycle() }
                            }
                        }

                        suspend fun verifyLikedOnFreshRoot(): Boolean {
                            val fresh = acquireRootOrNull(4, 80L..220L, LogTag.CLICK, quietLog = true) ?: return false
                            return try {
                                val resolved = nodeFinder.findLikeAreaNodeAt(fresh, origRectForVerify, origIdForVerify)
                                if (resolved != null) nodeFinder.isAlreadyLiked(resolved) else false
                            } finally {
                                runCatching { fresh.recycle() }
                            }
                        }

                        // Pass 1 — verify ngay sau 1500ms.
                        var confirmedLiked = verifyLikedOnFreshRoot()
                        // Pass 2 — wait thêm 800ms (Zalo lag UI) rồi verify lần 2.
                        if (!confirmedLiked) {
                            delay(ecoVerifyMs(800L))
                            confirmedLiked = verifyLikedOnFreshRoot()
                        }
                        // Pass 3 — wait thêm 800ms cho thiết bị chậm.
                        if (!confirmedLiked) {
                            delay(ecoVerifyMs(800L))
                            confirmedLiked = verifyLikedOnFreshRoot()
                        }
                        logger.log(
                            LogTag.CLICK,
                            "confirmed=$confirmedLiked",
                            "VERIFY_AFTER_CLICK"
                        )

                        if (!confirmedLiked) {
                            // Fallback theo yêu cầu: nếu không thấy ô bình luận (composer) sau click,
                            // có thể click 1 phát vừa toggle sang UNLIKE trên bài đã like trước đó → click lại để LIKE lại.
                            val resolved = resolvedLikeAreaOnFreshRoot()
                            if (resolved != null) {
                                // Nếu đang ở full-screen Bình luận thì heuristic composer KHÔNG còn nghĩa "đã like"
                                // — escape rồi bỏ qua bài, không treat-as-confirmed sai ngữ cảnh.
                                val verifyRoot = acquireRootOrNull(3, 60L..180L, LogTag.CLICK, quietLog = true)
                                val onCommentScreen = verifyRoot?.let { nodeFinder.isFullScreenCommentScreen(it) } == true
                                if (onCommentScreen) {
                                    logger.log(LogTag.CLICK, author ?: "unknown", "COMMENT_SCREEN_AFTER_CLICK_SKIP")
                                    updateStatus("💬 Sau click rơi vào Bình luận — back & bỏ qua")
                                    tryEscapeCommentScreen(verifyRoot)
                                    runCatching { verifyRoot?.recycle() }
                                    progressManager.incrementPostsHandledAndSave()
                                    sendInternalBroadcast(Intent("com.zalopilot.PROGRESS_UPDATE"))
                                    continue
                                }
                                runCatching { verifyRoot?.recycle() }

                                val composerAfter = nodeFinder.hasInlineCommentComposerNearLikeAnchor(resolved)
                                logger.log(
                                    LogTag.CLICK,
                                    "before=$composerBefore after=$composerAfter",
                                    "COMPOSER_DELTA"
                                )

                                suspend fun reLikeAndVerify(target: AccessibilityNodeInfo, tag: String) {
                                    val reClicked = performLikeClickWithFallbacks(target)
                                    logger.log(
                                        LogTag.CLICK,
                                        author ?: "unknown",
                                        if (reClicked) "${tag}_DISPATCHED" else "${tag}_FAILED"
                                    )
                                    if (reClicked) {
                                        delay(ecoVerifyMs(1500L))
                                        confirmedLiked = verifyLikedOnFreshRoot()
                                        if (!confirmedLiked) {
                                            delay(ecoVerifyMs(800L))
                                            confirmedLiked = verifyLikedOnFreshRoot()
                                        }
                                        if (!confirmedLiked) {
                                            delay(ecoVerifyMs(800L))
                                            confirmedLiked = verifyLikedOnFreshRoot()
                                        }
                                        logger.log(
                                            LogTag.CLICK,
                                            "confirmed=$confirmedLiked tag=$tag",
                                            "VERIFY_AFTER_RECLICK"
                                        )
                                    }
                                }

                                when {
                                    // Composer XUẤT HIỆN (trước không có, giờ có) → like vừa được thêm thành công.
                                    !composerBefore && composerAfter -> {
                                        logger.log(LogTag.CLICK, author ?: "unknown", "POST_CLICK_COMPOSER_APPEARED_CONFIRMED")
                                        confirmedLiked = true
                                    }
                                    // Composer BIẾN MẤT (trước có, giờ không) → click vừa toggle sang UNLIKE → re-click ngay.
                                    composerBefore && !composerAfter -> {
                                        logger.log(LogTag.CLICK, author ?: "unknown", "POST_CLICK_COMPOSER_DISAPPEARED_RECLICK")
                                        reLikeAndVerify(resolved, "RECLICK_TO_RELIKE")
                                    }
                                    // Trước & sau đều CÓ composer → coi như đã like (nguyên rule cũ).
                                    composerBefore && composerAfter -> {
                                        logger.log(LogTag.CLICK, author ?: "unknown", "POST_CLICK_HAS_COMMENT_COMPOSER_TREAT_AS_CONFIRMED")
                                        confirmedLiked = true
                                    }
                                    // Trước & sau đều KHÔNG có composer (heuristic không kết luận được) →
                                    // KHÔNG re-click bừa (tránh unlike nhầm). Coi là CLICK_UNCONFIRMED → skip bài.
                                    else -> {
                                        logger.log(LogTag.CLICK, author ?: "unknown", "POST_CLICK_NO_COMPOSER_EVIDENCE_SKIP")
                                    }
                                }
                            }
                        }

                        if (!confirmedLiked) {
                            logger.log(LogTag.CLICK, author ?: "unknown", "CLICK_UNCONFIRMED")
                            updateStatus("❓ Chưa xác nhận được like — bỏ qua bài này, thử bài khác")
                            continue
                        }

                        val progressAfterClick = progressManager.incrementAndSave()
                        sessionLikeCount++
                        if (author != null) likedAuthorsThisSession.add(author)
                        updateStatus("✅ Like #${progressAfterClick.todayLikeCount} — ${author ?: "unknown"}")
                        sendInternalBroadcast(Intent("com.zalopilot.PROGRESS_UPDATE"))

                        processedPosts.add(postKey)
                        clickedPositionsThisSession.add("${rect.left}_${rect.top}")
                        lastClickedPostKey = postKey
                        lastClickedPostAt = System.currentTimeMillis()
                        logger.log(
                            LogTag.STATE,
                            "postKey=$postKey processed=${processedPosts.size}",
                            "PROCESSED_ADD"
                        )
                        Log.d("AUTO", "PROCESSED_ADD postKey=$postKey cooldownSet lastClick=$lastClickedPostAt")

                        logger.log(LogTag.CLICK, author ?: "unknown", "SUCCESS_CONFIRMED")

                        if (progressManager.isLimitReached()) {
                            logger.log(LogTag.STATE, "autoLike", "DAILY_LIMIT_REACHED")
                            stopAutoLike()
                            return FeedScanResult.LIKED
                        }
                        return FeedScanResult.LIKED
                    }

                    updateStatus("❌ Click thất bại — thử bài khác")
                    logger.log(LogTag.CLICK, author ?: "unknown", "CLICK_FAILED:${boundsSummary(nodeForClick)}")
                    progressManager.incrementPostsHandledAndSave()
                    sendInternalBroadcast(Intent("com.zalopilot.PROGRESS_UPDATE"))
                } finally {
                    if (microRoot != null && microRoot !== scanRoot) {
                        runCatching { microRoot.recycle() }
                    }
                }
            }

            if (skippedShouldLike > 0) {
                logger.log(LogTag.STATE, "count=$skippedShouldLike", "SKIP_SHOULD_LIKE_BATCH")
            }

            return FeedScanResult.ALL_SKIPPED
        } finally {
            acquiredExtra?.recycle()
        }
    }

    private fun maybeDumpFeedItemTrees(root: AccessibilityNodeInfo) {
        val outUnliked = File(filesDir, "ui_dump_unliked.json")
        val outLiked = File(filesDir, "ui_dump_liked.json")
        if (outUnliked.exists() && outLiked.exists()) return

        val lvMediaStoreId = ZaloIDStore.FEED_LAYOUT_ANCHOR_IDS.firstOrNull { it.contains("lv_media_store") } ?: return
        val anchors = runCatching { root.findAccessibilityNodeInfosByViewId(lvMediaStoreId) }.getOrNull()
        val lv = anchors?.firstOrNull() ?: return

        val itemCount = lv.childCount.coerceAtMost(60)
        for (i in 0 until itemCount) {
            val item = lv.getChild(i) ?: continue

            try {
                if (!outUnliked.exists() && itemHasUnlikedLike(item)) {
                    outUnliked.writeText(dumpNodeTreeJson(item).toString(2))
                    logger.log(LogTag.SCAN, "ui_dump_unliked.json itemIndex=$i", "DUMP_SAVED")
                    // continue to allow liked too in same pass if possible
                }

                if (!outLiked.exists() && itemHasLikedLike(item)) {
                    outLiked.writeText(dumpNodeTreeJson(item).toString(2))
                    logger.log(LogTag.SCAN, "ui_dump_liked.json itemIndex=$i", "DUMP_SAVED")
                }
            } finally {
                runCatching { item.recycle() }
            }

            if (outUnliked.exists() && outLiked.exists()) return
        }
    }

    private fun itemHasUnlikedLike(item: AccessibilityNodeInfo): Boolean {
        // Theo yêu cầu: btn_like text="Thích" và chưa checked/selected.
        val byText = runCatching { item.findAccessibilityNodeInfosByText(ZaloIDStore.TEXT_LIKE) }.getOrNull()
            ?: emptyList()
        for (n in byText) {
            val t = n.text?.toString()?.trim().orEmpty()
            if (t == ZaloIDStore.TEXT_LIKE && !n.isChecked && !n.isSelected) return true
        }
        return false
    }

    private fun itemHasLikedLike(item: AccessibilityNodeInfo): Boolean {
        // Không dùng để quyết định like, chỉ phân loại dump.
        if (nodeFinder.hasVisibleSelfAlreadyLikedLikeControl(item)) return true
        val byLikedText = runCatching { item.findAccessibilityNodeInfosByText(ZaloIDStore.TEXT_LIKED) }.getOrNull()
            ?: emptyList()
        return byLikedText.isNotEmpty()
    }

    private fun dumpNodeTreeJson(root: AccessibilityNodeInfo): JSONObject {
        var nodeCount = 0
        val depthLimit = 60
        val nodeLimit = 4_500

        fun rectJson(n: AccessibilityNodeInfo): JSONObject {
            val r = Rect()
            n.getBoundsInScreen(r)
            return JSONObject().apply {
                put("l", r.left); put("t", r.top); put("r", r.right); put("b", r.bottom)
                put("w", r.width()); put("h", r.height())
            }
        }

        fun toJson(n: AccessibilityNodeInfo, depth: Int): JSONObject {
            nodeCount++
            val o = JSONObject().apply {
                put("id", n.viewIdResourceName?.toString().orEmpty())
                put("class", n.className?.toString().orEmpty())
                put("text", n.text?.toString().orEmpty())
                put("contentDescription", n.contentDescription?.toString().orEmpty())
                put("bounds", rectJson(n))
                put("clickable", n.isClickable)
                put("longClickable", n.isLongClickable)
                put("checked", n.isChecked)
                put("selected", n.isSelected)
                put("childCount", n.childCount)
            }

            if (depth >= depthLimit || nodeCount >= nodeLimit) return o

            val children = JSONArray()
            for (i in 0 until n.childCount) {
                if (nodeCount >= nodeLimit) break
                val c = n.getChild(i) ?: continue
                try {
                    children.put(toJson(c, depth + 1))
                } finally {
                    runCatching { c.recycle() }
                }
            }
            o.put("children", children)
            return o
        }

        return JSONObject().apply {
            put("scannedAtMs", System.currentTimeMillis())
            put("nodeCount", 0)
            put("tree", toJson(root, 0))
            put("nodeCount", nodeCount)
        }
    }

    private fun makePostKey(likeNode: AccessibilityNodeInfo): String {
        // KHÔNG dùng bounds tuyệt đối: sau khi scroll, bài mới xuất hiện ở
        // cùng tọa độ pixel với bài cũ → postKey trùng → bị SKIP sai.
        // Key chỉ dựa vào nội dung bài: author + snippet.
        val author = nodeFinder.getAuthorName(likeNode)?.trim().orEmpty().take(64)
        val snippet = nodeFinder.getPostSnippetForKey(likeNode).take(96)
        if (author.isEmpty() && snippet.isEmpty()) {
            // Fallback khi không đọc được nội dung: dùng bounds nhưng chỉ giữ
            // trong 30s (xử lý bởi cooldown lastClickedPostKey).
            val rect = Rect()
            likeNode.getBoundsInScreen(rect)
            return "BOUNDS|${rect.left}_${rect.top}_${rect.right}_${rect.bottom}"
        }
        return "CONTENT|$author|$snippet"
    }

    private fun logAutoBeforeClick(postKey: String, node: AccessibilityNodeInfo, rect: Rect) {
        Log.d(
            "AUTO",
            """
            POST_KEY=$postKey
            TEXT=${node.text}
            DESC=${node.contentDescription}
            BOUNDS=$rect
            """.trimIndent()
        )
        logger.log(LogTag.STATE, "POST_KEY=$postKey BOUNDS=$rect", "BEFORE_CLICK")
    }

    /** Neo layout feed (Zalo cố định package) — chờ render trước khi scan bài. */
    private fun rootContainsFeedAnchor(root: AccessibilityNodeInfo): Boolean {
        for (id in ZaloIDStore.FEED_LAYOUT_ANCHOR_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            val hit = !nodes.isNullOrEmpty()
            nodes?.forEach { runCatching { it.recycle() } }
            if (hit) return true
        }
        return false
    }

    /** Chờ [layoutSocialFeed] / [lv_media_store] + delay settle; timeout vẫn tiếp tục automation. */
    private suspend fun waitForFeedLayoutRendered() {
        val deadline = System.currentTimeMillis() + 14_000L
        while (isRunning && System.currentTimeMillis() < deadline) {
            val probe = acquireRootOrNull(4, 60L..200L, LogTag.STATE, quietLog = true)
            if (probe == null) {
                delay(320)
                continue
            }
            try {
                if (rootContainsFeedAnchor(probe)) {
                    logger.log(LogTag.STATE, "feed", "ANCHOR_VISIBLE")
                    delayEco(1_000L..2_000L)
                    return
                }
            } finally {
                probe.recycle()
            }
            delayEco(280L..520L)
        }
        logger.log(LogTag.STATE, "feed anchor", "TIMEOUT_CONTINUE_ANYWAY")
    }

    /** Top nhỏ nhất của nút Thích đang thấy — proxy để biết feed có cuộn không. */
    private fun measureFeedAnchorTop(root: AccessibilityNodeInfo): Int? {
        val nodes = nodeFinder.findLikeButtons(root)
        if (nodes.isEmpty()) return null
        var minTop = Int.MAX_VALUE
        for (n in nodes) {
            if (!n.hasValidScreenBounds()) continue
            val r = Rect()
            n.getBoundsInScreen(r)
            minTop = minOf(minTop, r.top)
        }
        return if (minTop == Int.MAX_VALUE) null else minTop
    }

    /**
     * @return `true` khi có bằng chứng cuộn (anchor nút Thích đổi, hoặc chưa có anchor nhưng scroll/gesture thành công).
     */
    private suspend fun scrollFeedWithVerification(
        rootBeforeScroll: AccessibilityNodeInfo,
        gestureProfile: GestureScrollProfile = GestureScrollProfile.NORMAL,
        preferGesture: Boolean = false
    ): Boolean {
        var rootAfter: AccessibilityNodeInfo? = null
        var rootRetry: AccessibilityNodeInfo? = null
        return try {
            val beforeTop = measureFeedAnchorTop(rootBeforeScroll)
            val (recyclerOk, gestureOk) = if (preferGesture) {
                updateStatus("👆 Vuốt tay (touch) — profile=$gestureProfile")
                val g = scrollDownByGesture(gestureProfile)
                val r = if (!g) {
                    updateStatus("🌀 Vuốt fail → fallback API SCROLL_FORWARD")
                    tryScrollFeedRecycler(rootBeforeScroll)
                } else false
                r to g
            } else {
                updateStatus("🌀 Cuộn API (SCROLL_FORWARD)")
                val r = tryScrollFeedRecycler(rootBeforeScroll)
                val g = if (!r) {
                    updateStatus("👆 API fail → fallback vuốt tay (touch)")
                    scrollDownByGesture(gestureProfile)
                } else false
                r to g
            }
            var scrollAttemptSucceeded = recyclerOk || gestureOk
            logger.log(
                LogTag.SCROLL,
                "beforeTop=$beforeTop preferGesture=$preferGesture recycler=$recyclerOk gesture=$gestureProfile gestureOk=$gestureOk",
                "DISPATCHED"
            )
            delay(ecoVerifyMs(1_200L))
            rootAfter = acquireRootOrNull(
                maxAttempts = 4,
                delayRangeMs = 80L..220L,
                logTag = LogTag.SCROLL,
                quietLog = isRunning
            )
            var afterTop = rootAfter?.let { measureFeedAnchorTop(it) }
            if (beforeTop != null && afterTop != null && beforeTop == afterTop) {
                logger.log(LogTag.SCROLL, "pass1 no movement", "SCROLL_RETRY")
                rootRetry = acquireRootOrNull(3, 80L..200L, LogTag.SCROLL, quietLog = isRunning)
                val retryProfile = if (gestureProfile == GestureScrollProfile.SMALL) {
                    GestureScrollProfile.NORMAL
                } else {
                    GestureScrollProfile.LARGE
                }
                val (retryRecycler, retryGesture) = if (preferGesture) {
                    val g = scrollDownByGesture(retryProfile)
                    val r = if (!g) (rootRetry?.let { tryScrollFeedRecycler(it) } ?: false) else false
                    r to g
                } else {
                    val r = rootRetry?.let { tryScrollFeedRecycler(it) } ?: false
                    val g = if (!r) scrollDownByGesture(retryProfile) else false
                    r to g
                }
                scrollAttemptSucceeded = scrollAttemptSucceeded || retryRecycler || retryGesture
                logger.log(
                    LogTag.SCROLL,
                    "retry recycler=$retryRecycler gesture=$retryGesture",
                    "DISPATCHED"
                )
                delay(ecoVerifyMs(1_000L))
                runCatching { rootAfter?.recycle() }
                rootAfter = acquireRootOrNull(4, 80L..220L, LogTag.SCROLL, quietLog = isRunning)
                afterTop = rootAfter?.let { measureFeedAnchorTop(it) }
            }
            val anchorMoved = beforeTop != null && afterTop != null && beforeTop != afterTop
            when {
                anchorMoved ->
                    logger.log(LogTag.SCROLL, "beforeTop=$beforeTop afterTop=$afterTop", "SCROLL_SUCCESS")
                beforeTop != null && afterTop != null && beforeTop == afterTop ->
                    logger.log(LogTag.SCROLL, "beforeTop=$beforeTop afterTop=$afterTop", "SCROLL_FAILED")
                else ->
                    logger.log(LogTag.SCROLL, "beforeTop=$beforeTop afterTop=$afterTop", "SCROLL_VERIFY_PARTIAL")
            }
            anchorMoved ||
                (!anchorMoved && (beforeTop == null || afterTop == null) && scrollAttemptSucceeded)
        } finally {
            runCatching { rootRetry?.recycle() }
            runCatching { rootAfter?.recycle() }
        }
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
        logLikeButtonContextDump(node, "PRE_LIKE_DUMP")
        markLikeClickAttemptForBounds(node)
        logger.log(LogTag.CLICK, nodeSnapshotForLog(node), "CLICK_CANDIDATE")

        val interactMode = settingsManager.getInteractMode()
        logger.log(LogTag.CLICK, "interactMode=$interactMode", "INTERACT_MODE")

        val clickable = node.isClickable || node.isLongClickable

        val useGestureFirst = when (interactMode) {
            InteractMode.TAP -> true // đúng như setting: ưu tiên touch (gesture tap) trước
            InteractMode.SWIPE -> false
            InteractMode.MIX -> (1..2).random() == 1
        }

        fun actionClickIfPossible(): Boolean {
            if (!clickable) {
                logger.log(LogTag.CLICK, boundsSummary(node), "ACTION_CLICK_SKIP_NOT_CLICKABLE")
                return false
            }
            val ok = performClickLikeTargetNoParent(node)
            logger.log(
                LogTag.CLICK,
                boundsSummary(node),
                if (ok) "ACTION_CLICK_OK" else "ACTION_CLICK_FAIL"
            )
            return ok
        }

        suspend fun gestureTap(): Boolean {
            val ok = tapNodeByCoordinate(node)
            logger.log(
                LogTag.CLICK,
                boundsSummary(node),
                if (ok) "GESTURE_TAP_OK" else "GESTURE_TAP_FAIL"
            )
            return ok
        }

        val ok = if (useGestureFirst) {
            updateStatus("👆 Touch tap nút Thích...")
            // Touch giống người trước; fail thì fallback ACTION_CLICK nếu có thể.
            val g = gestureTap()
            if (g) true else {
                updateStatus("🖱 Touch fail → fallback ACTION_CLICK")
                actionClickIfPossible()
            }
        } else {
            updateStatus("🖱 ACTION_CLICK nút Thích...")
            // Click trước (ổn định hơn); fail thì fallback touch.
            val c = actionClickIfPossible()
            if (c) true else {
                updateStatus("👆 Click fail → fallback touch tap")
                gestureTap()
            }
        }

        if (ok) {
            logLikeButtonContextDump(node, "POST_LIKE_DUMP")
        }
        return ok
    }

    /**
     * btn_like + subtree, then parent + parent's direct children (siblings). Dedupe by node instance.
     * [dumpResult] is written to log JSON `result` (e.g. PRE_LIKE_DUMP / POST_LIKE_DUMP).
     */
    private fun logLikeButtonContextDump(btnLike: AccessibilityNodeInfo, dumpResult: String) {
        if (!debugHighlightPrefs.isVerboseLikeContextLoggingEnabled()) return
        val seen = HashSet<Int>()
        fun logOne(n: AccessibilityNodeInfo) {
            if (!seen.add(System.identityHashCode(n))) return
            val r = Rect()
            n.getBoundsInScreen(r)
            val text = n.text?.toString()?.replace("\n", "↵") ?: ""
            val desc = n.contentDescription?.toString()?.replace("\n", "↵") ?: ""
            val id = n.viewIdResourceName ?: ""
            val line =
                "text=\"$text\" contentDescription=\"$desc\" viewIdResourceName=\"$id\" " +
                    "isChecked=${n.isChecked} isSelected=${n.isSelected} bounds=[${r.left},${r.top},${r.right},${r.bottom}]"
            logger.log(LogTag.SCAN, line, dumpResult)
        }
        fun dfsSubtree(n: AccessibilityNodeInfo) {
            logOne(n)
            for (i in 0 until n.childCount) {
                val ch = n.getChild(i) ?: continue
                dfsSubtree(ch)
                ch.recycle()
            }
        }
        dfsSubtree(btnLike)
        val parent = btnLike.parent ?: return
        logOne(parent)
        for (i in 0 until parent.childCount) {
            val ch = parent.getChild(i) ?: continue
            logOne(ch)
            if (ch !== btnLike) ch.recycle()
        }
    }

    private fun showStatusOverlay(msg: String) {
        mainHandler.post {
            try {
                if (isOverlayShowing) {
                    updateStatus(msg)
                    applyKeepScreenOnToStatusOverlayIfNeeded()
                    return@post
                }
                val wm = windowManager ?: run {
                    logger.log(LogTag.STATE, "overlay", "WINDOW_MANAGER_NULL")
                    return@post
                }

                val overlayFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    if (isRunning) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    overlayFlags,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = 80
                }

                val layout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(24, 12, 24, 12)
                    setBackgroundColor(Color.parseColor("#CC000000"))
                    keepScreenOn = isRunning
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

    /** Khi bot chạy: giữ màn hình không tắt (đọc accessibility cần màn hình thường vẫn bật). */
    private fun applyKeepScreenOnToStatusOverlayIfNeeded() {
        if (!isRunning) return
        // Đang pause vì user rời Zalo → KHÔNG keep màn sáng (mục đích "Tiết kiệm khi rời Zalo").
        if (!isZaloForeground && settingsManager.isPauseWhenZaloAway()) return
        try {
            val wm = windowManager ?: return
            val v = statusView ?: return
            val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
            val want = lp.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            if (lp.flags != want) {
                lp.flags = want
                wm.updateViewLayout(v, lp)
            }
            v.keepScreenOn = true
        } catch (e: Exception) {
            logger.logError("applyKeepScreenOnToStatusOverlayIfNeeded", e)
        }
    }

    /** Gỡ FLAG_KEEP_SCREEN_ON để màn được tự tắt — dùng khi pause vì rời Zalo (Fix #1). */
    private fun removeKeepScreenOnFromStatusOverlay() {
        try {
            val wm = windowManager ?: return
            val v = statusView ?: return
            val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
            val want = lp.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
            if (lp.flags != want) {
                lp.flags = want
                wm.updateViewLayout(v, lp)
            }
            v.keepScreenOn = false
        } catch (e: Exception) {
            logger.logError("removeKeepScreenOnFromStatusOverlay", e)
        }
    }

    private fun updateStatus(msg: String) {
        mainHandler.post {
            if (!isOverlayShowing) {
                showStatusOverlay(msg)
                return@post
            }
            statusText?.text = msg
            applyKeepScreenOnToStatusOverlayIfNeeded()
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

    /**
     * Cuộn feed qua API accessibility (RecyclerView) — thường tin cậy hơn vuốt toàn màn hình
     * khi Zalo bọc feed trong RecyclerView đã học [ZaloIDStore.KEY_FEED_RECYCLER].
     */
    private fun tryScrollFeedRecycler(root: AccessibilityNodeInfo): Boolean {
        val id = idStore.getFeedRecyclerID()
        if (id.isNullOrBlank()) {
            logger.log(LogTag.SCROLL, "feed_recycler_id", "MISSING_SKIP_API")
            return false
        }
        val matches = root.findAccessibilityNodeInfosByViewId(id)
        if (matches.isNullOrEmpty()) {
            logger.log(LogTag.SCROLL, "id=$id", "RECYCLER_NOT_IN_TREE")
            return false
        }
        var scrolled = false
        try {
            for (node in matches) {
                if (node == null) continue
                if (attemptScrollRecyclerNode(node)) {
                    scrolled = true
                    // Một lần SCROLL_FORWARD trên Zalo thường chỉ đẩy feed ~một phần bài — gọi thêm một bước
                    // để bài kế lên rõ (tránh cảm giác cuộn 1/3–1/2 bài).
                    attemptScrollRecyclerNode(node)
                    break
                }
            }
        } finally {
            for (node in matches) {
                runCatching { node.recycle() }
            }
        }
        return scrolled
    }

    /** Thử [ACTION_SCROLL_FORWARD] trên node và tối đa một parent scrollable. */
    private fun attemptScrollRecyclerNode(n: AccessibilityNodeInfo): Boolean {
        if (n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            logger.log(LogTag.SCROLL, boundsSummary(n), "SCROLL_FORWARD_NODE")
            return true
        }
        val parent = n.parent ?: return false
        return try {
            val ok = parent.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            if (ok) logger.log(LogTag.SCROLL, boundsSummary(parent), "SCROLL_FORWARD_PARENT")
            ok
        } finally {
            runCatching { parent.recycle() }
        }
    }

    /**
     * Vuốt lên (nội dung feed xuống) — fallback khi API scroll không dùng được;
     * có callback để biết gesture bị reject/cancel hay hoàn tất.
     *
     * Giả lập gần tay người: mỗi lần khác tọa độ X, điểm dứt lệch X nhẹ, đường hơi cong (quad),
     * biên độ/duration dao động nhỏ — tránh mọi lần đều một đường thẳng giữa màn hình.
     */
    private suspend fun scrollDownByGesture(
        profile: GestureScrollProfile = GestureScrollProfile.NORMAL
    ): Boolean {
        val metrics = resources.displayMetrics
        val w = metrics.widthPixels.toFloat()
        val h = metrics.heightPixels.toFloat()
        val (baseFromY, baseToY, baseDur) = when (profile) {
            GestureScrollProfile.SMALL -> Triple(0.80f, 0.26f, 420L)
            GestureScrollProfile.NORMAL -> Triple(0.88f, 0.14f, 540L)
            GestureScrollProfile.LARGE -> Triple(0.91f, 0.08f, 680L)
        }
        val minVerticalSpan = when (profile) {
            GestureScrollProfile.SMALL -> 0.46f
            GestureScrollProfile.NORMAL -> 0.58f
            GestureScrollProfile.LARGE -> 0.72f
        }
        var fromYFrac = (baseFromY + Random.nextFloat() * 0.06f - 0.03f).coerceIn(0.70f, 0.96f)
        var toYFrac = (baseToY + Random.nextFloat() * 0.06f - 0.03f).coerceIn(0.05f, 0.38f)
        if (fromYFrac - toYFrac < minVerticalSpan) {
            toYFrac = (fromYFrac - minVerticalSpan).coerceAtLeast(0.05f)
            if (fromYFrac - toYFrac < minVerticalSpan) {
                fromYFrac = (toYFrac + minVerticalSpan).coerceAtMost(0.96f)
            }
        }
        val fromY = h * fromYFrac
        val toY = h * toYFrac

        val fromX = w * (0.34f + Random.nextFloat() * 0.32f)
        val endDx = w * (Random.nextFloat() * 0.09f - 0.045f)
        val toX = (fromX + endDx).coerceIn(w * 0.12f, w * 0.88f)

        val ctrlX = (fromX + toX) / 2f + w * (Random.nextFloat() * 0.12f - 0.06f)
        val ctrlY = (fromY + toY) / 2f + h * (Random.nextFloat() * 0.05f - 0.025f)

        val durationMs = (baseDur + Random.nextLong(-70L, 131L)).coerceIn(320L, 920L)

        val path = Path().apply {
            moveTo(fromX, fromY)
            quadTo(ctrlX, ctrlY, toX, toY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return withTimeoutOrNull(1_800L) {
            suspendCancellableCoroutine { cont ->
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription) {
                        logger.log(LogTag.SCROLL, "swipe", "GESTURE_CANCELLED")
                        if (cont.isActive) cont.resume(false)
                    }
                }
                val dispatched = dispatchGesture(gesture, callback, null)
                if (!dispatched) {
                    logger.log(LogTag.SCROLL, "swipe", "GESTURE_DISPATCH_REJECTED")
                    if (cont.isActive) cont.resume(false)
                }
            }
        } ?: run {
            logger.log(LogTag.SCROLL, "swipe", "GESTURE_TIMEOUT")
            false
        }
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

    /** Like: chỉ click đúng node target (btn_like_text / icon / …), không đụng parent container. */
    private fun performClickLikeTargetNoParent(node: AccessibilityNodeInfo): Boolean {
        val snap = nodeSnapshotForLog(node)
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            logger.log(LogTag.CLICK, "target=self $snap", "ACTION_CLICK_OK")
            return true
        }
        logger.log(LogTag.CLICK, "target=self $snap", "ACTION_CLICK_FAIL")
        return false
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
