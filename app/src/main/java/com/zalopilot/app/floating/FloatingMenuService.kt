package com.zalopilot.app.floating

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.graphics.Rect
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.zalopilot.app.accessibility.NodeFinder
import com.zalopilot.app.accessibility.ZaloPilotAccessibilityService
import com.zalopilot.app.util.LikeProgressManager
import com.zalopilot.app.util.LikeSettingsManager
import com.zalopilot.app.util.LogTag
import com.zalopilot.app.util.Logger
import dagger.hilt.android.AndroidEntryPoint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class FloatingMenuService : Service() {

    @Inject lateinit var settingsManager: LikeSettingsManager
    @Inject lateinit var progressManager: LikeProgressManager
    @Inject lateinit var logger: Logger
    @Inject lateinit var nodeFinder: NodeFinder

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

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(NOTIF_ID, buildNotification())
        logger.log(LogTag.STATE, "FloatingMenuService", "CREATED")
        val filter = IntentFilter().apply {
            addAction("com.zalopilot.STATUS_UPDATE")
            addAction("com.zalopilot.PROGRESS_UPDATE")
            addAction("com.zalopilot.ZALO_STATE")
        }
        registerReceiver(statusReceiver, filter)
        showFab()
    }

    override fun onDestroy() {
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
            setBackgroundColor(Color.parseColor("#0068FF"))
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

            addView(menuItem("📊 ${progress.todayLikeCount}/${settings.dailyLimit} hôm nay", "#0068FF", null))

            addView(menuItem("📋 Dump UI", "#6C5CE7") {
                runCatching {
                    dumpFirst3FeedItemsToFilesDir()
                    Toast.makeText(this@FloatingMenuService, "✅ Đã ghi filesDir/ui_dump.json", Toast.LENGTH_LONG).show()
                    logger.log(LogTag.SCAN, "ui_dump.json", "SUCCESS")
                }.onFailure { e ->
                    Toast.makeText(this@FloatingMenuService, "❌ Dump UI lỗi: ${e.message}", Toast.LENGTH_LONG).show()
                    logger.log(LogTag.ERROR, "ui_dump.json", "FAIL: ${e.message}")
                    logger.logError("FloatingMenuService.dumpFirst3FeedItems", e)
                }
                closeMenu()
            })

            addView(menuItem(
                if (autoMode) "🤖 Tự động: BẬT" else "👆 Thủ công",
                if (autoMode) "#2E75B6" else "#555555"
            ) {
                settingsManager.toggleAutoStart()
                closeMenu(); openMenu()
            })

            if (botRunning) {
                addView(menuItem("■  Dừng", "#E24B4A") {
                    ZaloPilotAccessibilityService.instance?.stopAutoLike()
                    closeMenu()
                })
            } else {
                addView(menuItem("▶  Bắt đầu like", "#27AE60") {
                    val svc = ZaloPilotAccessibilityService.instance
                    if (svc == null) {
                        Toast.makeText(this@FloatingMenuService, "⚠️ Chưa bật Accessibility cho ZaloPilot", Toast.LENGTH_LONG).show()
                        logger.log(LogTag.STATE, "floating menu", "NO_ACCESSIBILITY_SERVICE")
                        closeMenu()
                        return@menuItem
                    }
                    svc.startAutoLike()
                    closeMenu()
                })
            }

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
            if (botRunning) Color.parseColor("#27AE60") else Color.parseColor("#0068FF")
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
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("ZaloPilot đang chạy")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(android.R.drawable.ic_menu_save, "📋 Dump UI", dumpPi)
            .build()
    }

    private fun dumpFirst3FeedItemsToFilesDir() {
        val svc = ZaloPilotAccessibilityService.instance
        val root = svc?.rootInActiveWindow
        if (root == null) {
            throw IllegalStateException("Không đọc được UI — hãy mở Zalo trước")
        }

        val likeButtons = nodeFinder.findLikeButtons(root)
        if (likeButtons.isEmpty()) {
            // Fallback: vẫn dump 1 cây root nhỏ để debug.
            val json = JSONObject().apply {
                put("scannedAtMs", System.currentTimeMillis())
                put("note", "No like buttons found; dumping root subtree")
                put("items", JSONArray())
                put("root", serializeNode(root, depthLimit = 7, nodeLimit = 450).first)
            }
            writeUiDump(json)
            return
        }

        val items = JSONArray()
        val seenKeys = HashSet<String>()
        var picked = 0

        for (like in likeButtons) {
            val container = findFeedItemContainer(like) ?: continue
            val key = buildContainerKey(container)
            if (!seenKeys.add(key)) continue

            val (tree, nodeCount) = serializeNode(container, depthLimit = 9, nodeLimit = 600)
            val likeRect = Rect().also { like.getBoundsInScreen(it) }
            val containerRect = Rect().also { container.getBoundsInScreen(it) }
            items.put(
                JSONObject().apply {
                    put("index", picked)
                    put("nodeCount", nodeCount)
                    put("containerBounds", rectJson(containerRect))
                    put("likeBounds", rectJson(likeRect))
                    put("likeText", like.text?.toString().orEmpty())
                    put("likeResId", like.viewIdResourceName?.toString().orEmpty())
                    put("tree", tree)
                }
            )
            picked++
            if (picked >= 3) break
        }

        val json = JSONObject().apply {
            put("scannedAtMs", System.currentTimeMillis())
            put("picked", picked)
            put("likeButtonsFound", likeButtons.size)
            put("items", items)
        }
        writeUiDump(json)
    }

    private fun writeUiDump(json: JSONObject) {
        val out = File(filesDir, "ui_dump.json")
        out.writeText(json.toString(2))
    }

    private fun buildContainerKey(node: android.view.accessibility.AccessibilityNodeInfo): String {
        val r = Rect().also { node.getBoundsInScreen(it) }
        val id = node.viewIdResourceName?.toString().orEmpty()
        val cls = node.className?.toString().orEmpty()
        val txt = node.text?.toString().orEmpty()
        val cd = node.contentDescription?.toString().orEmpty()
        return listOf(cls, id, txt.take(32), cd.take(32), "${r.left},${r.top},${r.right},${r.bottom}").joinToString("|")
    }

    private fun findFeedItemContainer(start: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
        // Heuristic: climb up from Like control to a "card-ish" container.
        var current: android.view.accessibility.AccessibilityNodeInfo? = start
        var best: android.view.accessibility.AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE
        var steps = 0

        while (current != null && steps < 18) {
            val score = scoreAsFeedItemContainer(current)
            if (score > bestScore) {
                bestScore = score
                best = current
            }
            current = current.parent
            steps++
        }
        return best
    }

    private fun scoreAsFeedItemContainer(node: android.view.accessibility.AccessibilityNodeInfo): Int {
        val cls = node.className?.toString().orEmpty()
        val id = node.viewIdResourceName?.toString().orEmpty()
        val childCount = node.childCount

        var score = 0
        if (cls.contains("RecyclerView", ignoreCase = true)) score -= 80
        if (cls.contains("ListView", ignoreCase = true)) score -= 80
        if (cls.contains("ScrollView", ignoreCase = true)) score -= 50
        if (id.contains("recycler", ignoreCase = true)) score -= 60
        if (id.contains("vpager", ignoreCase = true)) score -= 80

        if (childCount in 5..35) score += 40
        if (childCount in 2..60) score += 10
        if (node.isClickable) score += 6

        val r = Rect().also { node.getBoundsInScreen(it) }
        val height = r.height()
        val width = r.width()
        if (height in 350..2000) score += 35
        if (width >= 500) score += 10

        // Text density hint: feed item often contains some text nodes inside.
        val textHits = countDescendantTextNodes(node, maxNodes = 120)
        score += textHits.coerceAtMost(18)
        return score
    }

    private fun countDescendantTextNodes(root: android.view.accessibility.AccessibilityNodeInfo, maxNodes: Int): Int {
        val q = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        q.add(root)
        var visited = 0
        var hits = 0
        while (q.isNotEmpty() && visited < maxNodes) {
            val n = q.removeFirst()
            visited++
            val t = n.text?.toString().orEmpty()
            val cd = n.contentDescription?.toString().orEmpty()
            if (t.isNotBlank() || cd.isNotBlank()) hits++
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { q.add(it) }
            }
        }
        return hits
    }

    private fun serializeNode(
        root: android.view.accessibility.AccessibilityNodeInfo,
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
    }
}
