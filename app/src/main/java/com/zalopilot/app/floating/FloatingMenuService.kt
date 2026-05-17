package com.zalopilot.app.floating

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.graphics.Rect
import android.content.ContentValues
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.zalopilot.app.accessibility.ZaloPilotAccessibilityService
import com.zalopilot.app.util.AccessibilityHelper
import com.zalopilot.app.util.BotStartEntry
import com.zalopilot.app.util.AppVersion
import com.zalopilot.app.util.LikeMode
import com.zalopilot.app.util.LikeProgressManager
import com.zalopilot.app.util.LikeSettingsManager
import com.zalopilot.app.util.VisitActionMode
import com.zalopilot.app.util.LogTag
import com.zalopilot.app.util.Logger
import dagger.hilt.android.AndroidEntryPoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

/** Khớp [com.zalopilot.app.ui.ZpColors] — overlay View, không Compose. */
private object FloatingMenuUiColors {
    const val ACCENT_BLUE = "#007AFF"
    const val ACCENT_PURPLE = "#AF52DE"
    const val COLOR_GREEN = "#34C759"
    const val COLOR_RED = "#FF3B30"
    const val TEXT_MUTED = "#8E8E93"
}

@AndroidEntryPoint
class FloatingMenuService : Service() {

    @Inject lateinit var settingsManager: LikeSettingsManager
    @Inject lateinit var progressManager: LikeProgressManager
    @Inject lateinit var logger: Logger

    private lateinit var windowManager: WindowManager
    private var fabView: TextView? = null
    private var menuView: LinearLayout? = null
    private var isMenuOpen = false
    private var fabParams: WindowManager.LayoutParams? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateFabState()
            if (isMenuOpen) { closeMenu(); openMenu() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            logger.log(LogTag.STATE, "FloatingMenuService", "STOP_REQUESTED")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        isOverlayRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(NOTIF_ID, buildNotification())
        logger.log(LogTag.STATE, "FloatingMenuService", "CREATED")
        val filter = IntentFilter().apply {
            addAction("com.zalopilot.STATUS_UPDATE")
            addAction("com.zalopilot.PROGRESS_UPDATE")
            addAction("com.zalopilot.ZALO_STATE")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
        showFab()
    }

    override fun onDestroy() {
        isOverlayRunning = false
        closeMenu()
        super.onDestroy()
        try {
            unregisterReceiver(statusReceiver)
        } catch (e: Exception) {
            logger.logError("FloatingMenuService.onDestroy.unregister", e)
        }
        fabView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                logger.logError("FloatingMenuService.onDestroy.removeFab", e)
            }
        }
        menuView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                logger.logError("FloatingMenuService.onDestroy.removeMenu", e)
            }
        }
    }

    private fun showFab() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20; y = 300
        }
        fabParams = params

        val size = (52 * resources.displayMetrics.density).toInt()
        val fab = TextView(this).apply {
            text = "ZP"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor(FloatingMenuUiColors.ACCENT_BLUE))
            layoutParams = LinearLayout.LayoutParams(size, size)
        }

        setupDrag(fab, params)
        fabView = fab
        windowManager.addView(fab, params)
    }

    private fun setupDrag(view: TextView, params: WindowManager.LayoutParams) {
        var initialX = 0; var initialY = 0
        var touchX = 0f; var touchY = 0f
        var isDragging = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    isDragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (dx * dx + dy * dy > 25) isDragging = true
                    if (isDragging) {
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        windowManager.updateViewLayout(view, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) toggleMenu()
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleMenu() {
        if (isMenuOpen) closeMenu() else openMenu()
    }

    private fun openMenu() {
        isMenuOpen = true
        val progress = progressManager.load()
        val settings = settingsManager.load()
        val botRunning = ZaloPilotAccessibilityService.instance?.isRunning == true
        val autoMode = settingsManager.isAutoStart()

        val fp = fabParams ?: return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = fp.x + 60; y = fp.y
        }

        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
            setBackgroundColor(Color.parseColor("#EE1a1a2e"))

            addView(menuItem("ZaloPilot ${AppVersion.fromContext(this@FloatingMenuService)}", FloatingMenuUiColors.ACCENT_BLUE, null))

            val statLabel = buildString {
                append("Like ${progress.todayLikeCount}")
                append(" · Duyệt ${progress.todayPostsHandledCount}")
                if (settings.dailyLimit > 0) append(" /${settings.dailyLimit}")
                append(" hôm nay")
            }
            addView(menuItem(statLabel, FloatingMenuUiColors.ACCENT_BLUE, null))

            if (settingsManager.getLikeMode() == LikeMode.VISIT) {
                val visitIdx = progress.visitIndex
                addView(menuItem("Profile: $visitIdx / ${settings.visitMaxProfiles}", FloatingMenuUiColors.TEXT_MUTED, null))
                addView(menuItem("Like −  (${settings.visitLikeCount})", FloatingMenuUiColors.ACCENT_PURPLE) {
                    settingsManager.setVisitLikeCount((settings.visitLikeCount - 1).coerceAtLeast(0))
                    closeMenu(); openMenu()
                })
                addView(menuItem("Like +  (${settings.visitLikeCount})", FloatingMenuUiColors.ACCENT_PURPLE) {
                    settingsManager.setVisitLikeCount((settings.visitLikeCount + 1).coerceAtMost(10))
                    closeMenu(); openMenu()
                })
                addView(menuItem("Cmt −  (${settings.visitCommentCount})", FloatingMenuUiColors.ACCENT_BLUE) {
                    settingsManager.setVisitCommentCount((settings.visitCommentCount - 1).coerceAtLeast(0))
                    closeMenu(); openMenu()
                })
                addView(menuItem("Cmt +  (${settings.visitCommentCount})", FloatingMenuUiColors.ACCENT_BLUE) {
                    settingsManager.setVisitCommentCount((settings.visitCommentCount + 1).coerceAtMost(5))
                    closeMenu(); openMenu()
                })
                val modeLabel = when (settingsManager.getVisitActionMode()) {
                    VisitActionMode.LIKE_ONLY -> "LIKE"
                    VisitActionMode.COMMENT_ONLY -> "COMMENT"
                    VisitActionMode.MIX -> "MIX"
                }
                addView(menuItem("Chế độ: $modeLabel (đổi trong app)", "#555555", null))
            }

            addView(menuItem("Dump UI", FloatingMenuUiColors.ACCENT_BLUE) {
                runCatching {
                    val fileName = dumpFullScreenToDownloads()
                    Toast.makeText(
                        this@FloatingMenuService,
                        "✅ Downloads: $fileName",
                        Toast.LENGTH_LONG
                    ).show()
                    logger.log(LogTag.SCAN, fileName, "DUMP_DOWNLOADS_OK")
                }.onFailure { e ->
                    Toast.makeText(this@FloatingMenuService, "❌ Dump UI lỗi: ${e.message}", Toast.LENGTH_LONG).show()
                    logger.log(LogTag.ERROR, "dump_downloads", "FAIL: ${e.message}")
                    logger.logError("FloatingMenuService.dumpFullScreenToDownloads", e)
                }
                closeMenu()
            })

            addView(menuItem(
                if (autoMode) "Tự động: BẬT" else "Thủ công",
                if (autoMode) FloatingMenuUiColors.ACCENT_BLUE else FloatingMenuUiColors.TEXT_MUTED
            ) {
                settingsManager.toggleAutoStart()
                closeMenu(); openMenu()
            })

            if (botRunning) {
                addView(menuItem("■  Dừng", FloatingMenuUiColors.COLOR_RED) {
                    AccessibilityHelper.requestStopAutoLike(this@FloatingMenuService)
                    closeMenu()
                })
            } else {
                addView(menuItem("▶  Bắt đầu like", FloatingMenuUiColors.COLOR_GREEN) {
                    val inferred = ZaloPilotAccessibilityService.instance?.inferLikeModeForStart()
                    val mode = inferred ?: settingsManager.getLikeMode()
                    AccessibilityHelper.requestStartAutoLike(
                        this@FloatingMenuService,
                        mode,
                        BotStartEntry.FLOATING_ON_ZALO
                    )
                    closeMenu()
                })
            }

            addView(menuItem("Ẩn nút ZP", FloatingMenuUiColors.TEXT_MUTED) {
                Toast.makeText(this@FloatingMenuService, "Đã tắt nút nổi — bật lại trong app ZaloPilot", Toast.LENGTH_LONG).show()
                logger.log(LogTag.STATE, "FloatingMenuService", "HIDE_OVERLAY_FROM_MENU")
                stopSelf()
                closeMenu()
            })

            addView(menuItem("✕  Đóng", "#888888") { closeMenu() })
        }

        menuView = menu
        windowManager.addView(menu, params)
    }

    private fun menuItem(label: String, colorHex: String, onClick: (() -> Unit)?): TextView {
        return TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(24, 14, 24, 14)
            setBackgroundColor(Color.parseColor(colorHex))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 3, 0, 3) }
            layoutParams = lp
            if (onClick != null) setOnClickListener { onClick() }
        }
    }

    private fun closeMenu() {
        isMenuOpen = false
        menuView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) {}
            menuView = null
        }
    }

    private fun updateFabState() {
        val botRunning = ZaloPilotAccessibilityService.instance?.isRunning == true
        fabView?.setBackgroundColor(
            if (botRunning) Color.parseColor(FloatingMenuUiColors.COLOR_GREEN)
            else Color.parseColor(FloatingMenuUiColors.ACCENT_BLUE)
        )
        fabView?.text = if (botRunning) "ZP▶" else "ZP"
    }

    private fun buildNotification(): Notification {
        val channelId = "zalopilot_service"
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(channelId) == null) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "ZaloPilot", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val dumpPi = PendingIntent.getBroadcast(
            this,
            2,
            Intent(ZaloPilotAccessibilityService.ACTION_DUMP_UI_TREE).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this,
            3,
            Intent(this, FloatingMenuService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("ZaloPilot ${AppVersion.shortLabel()}")
            .setContentText("Nút ZP nổi · ${AppVersion.fullLabel()}")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_save, "📋 Dump UI", dumpPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Tắt nút ZP", stopPi)
            .build()
    }

    /** Dump toàn màn hình Zalo → thư mục Downloads (MediaStore). */
    private fun dumpFullScreenToDownloads(): String {
        val svc = ZaloPilotAccessibilityService.instance
        val root = svc?.rootInActiveWindow
            ?: throw IllegalStateException("Không đọc được UI — hãy mở Zalo trước")
        return try {
            val screenName = detectScreenName(root)
            val (tree, nodeCount) = serializeNode(root, depthLimit = 12, nodeLimit = 1000)
            val json = JSONObject().apply {
                put("scannedAtMs", System.currentTimeMillis())
                put("screen", screenName)
                put("nodeCount", nodeCount)
                put("tree", tree)
            }
            val fileName = "zp_dump_${screenName}_${System.currentTimeMillis()}.json"
            writeJsonToDownloads(fileName, json)
            fileName
        } finally {
            runCatching { root.recycle() }
        }
    }

    private fun detectScreenName(root: AccessibilityNodeInfo): String {
        return when {
            (screenHasText(root, "Contacts") || screenHasText(root, "Danh bạ")) &&
                (screenHasText(root, "All") || screenHasText(root, "Tất cả")) -> "contacts"
            (screenHasText(root, "Comments") || screenHasText(root, "Bình luận")) &&
                (screenHasHint(root, "Write a comment") || screenHasHint(root, "Nhập bình luận")) -> "comments"
            (screenHasText(root, "Photos") || screenHasText(root, "Ảnh")) &&
                (screenHasText(root, "Videos") || screenHasText(root, "Video")) -> "profile"
            screenHasHint(root, "Message") || screenHasHint(root, "Tin nhắn") -> "chat"
            screenHasText(root, "Nhật ký") || screenHasText(root, "Timeline") ||
                screenHasText(root, "Tường nhà") -> "feed"
            else -> "unknown"
        }
    }

    private fun screenHasText(root: AccessibilityNodeInfo, needle: String, maxNodes: Int = 1500): Boolean {
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

    private fun screenHasHint(root: AccessibilityNodeInfo, needle: String, maxNodes: Int = 1500): Boolean {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < maxNodes) {
            val node = stack.removeLast()
            visited++
            val hint = node.hintText?.toString().orEmpty()
            val t = node.text?.toString().orEmpty()
            val d = node.contentDescription?.toString().orEmpty()
            if (hint.contains(needle, ignoreCase = true) ||
                t.contains(needle, ignoreCase = true) ||
                d.contains(needle, ignoreCase = true)
            ) {
                return true
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return false
    }

    private fun writeJsonToDownloads(displayName: String, json: JSONObject) {
        val bytes = json.toString(2).toByteArray(Charsets.UTF_8)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Không tạo được file trong Downloads")
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("Không ghi được file Downloads")
        } else {
            @Suppress("DEPRECATION")
            val dest = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                displayName
            )
            dest.writeBytes(bytes)
        }
    }

    private fun serializeNode(
        root: AccessibilityNodeInfo,
        depthLimit: Int,
        nodeLimit: Int
    ): Pair<JSONObject, Int> {
        var nodeCount = 0

        fun toJson(node: android.view.accessibility.AccessibilityNodeInfo, depth: Int): JSONObject {
            nodeCount++
            val r = Rect().also { node.getBoundsInScreen(it) }
            val o = JSONObject().apply {
                put("class", node.className?.toString().orEmpty())
                put("id", node.viewIdResourceName?.toString().orEmpty())
                put("text", node.text?.toString().orEmpty())
                put("desc", node.contentDescription?.toString().orEmpty())
                put("clickable", node.isClickable)
                put("checked", node.isChecked)
                put("selected", node.isSelected)
                put("enabled", node.isEnabled)
                put("bounds", rectJson(r))
                put("childCount", node.childCount)
            }

            if (depth >= depthLimit || nodeCount >= nodeLimit) return o

            val children = JSONArray()
            for (i in 0 until node.childCount) {
                if (nodeCount >= nodeLimit) break
                val c = node.getChild(i) ?: continue
                children.put(toJson(c, depth + 1))
            }
            o.put("children", children)
            return o
        }

        return toJson(root, 0) to nodeCount
    }

    private fun rectJson(r: Rect): JSONObject = JSONObject().apply {
        put("l", r.left); put("t", r.top); put("r", r.right); put("b", r.bottom)
        put("w", r.width()); put("h", r.height())
    }

    companion object {
        private const val NOTIF_ID = 1
        const val ACTION_STOP = "com.zalopilot.action.STOP_FLOATING_MENU"

        @Volatile
        var isOverlayRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, FloatingMenuService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            if (!isOverlayRunning) return
            val intent = Intent(context, FloatingMenuService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
