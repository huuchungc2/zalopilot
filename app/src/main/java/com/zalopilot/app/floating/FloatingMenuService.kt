package com.zalopilot.app.floating

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.zalopilot.app.R
import com.zalopilot.app.accessibility.ZaloPilotAccessibilityService
import com.zalopilot.app.util.LikeMode
import com.zalopilot.app.util.LikeProgressManager
import com.zalopilot.app.util.LikeSettingsManager
import com.zalopilot.app.util.Logger
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class FloatingMenuService : Service() {

    @Inject lateinit var settingsManager: LikeSettingsManager
    @Inject lateinit var progressManager: LikeProgressManager
    @Inject lateinit var logger: Logger

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var menuView: View? = null
    private var isMenuOpen = false

    // Broadcast receiver nhận update từ accessibility service
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.zalopilot.STATUS_UPDATE" -> updateButtonState()
                "com.zalopilot.PROGRESS_UPDATE" -> updateProgressText()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(1, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        registerReceiver(statusReceiver, IntentFilter().apply {
            addAction("com.zalopilot.STATUS_UPDATE")
            addAction("com.zalopilot.PROGRESS_UPDATE")
        })
        showFloatingButton()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(statusReceiver)
        floatingView?.let { windowManager.removeView(it) }
        menuView?.let { windowManager.removeView(it) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Floating Button ──────────────────────────────────────────

    private fun showFloatingButton() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 300
        }

        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_button, null)
        windowManager.addView(floatingView, params)

        updateButtonState()
        setupDragAndClick(floatingView!!, params)
    }

    private fun setupDragAndClick(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0; var initialY = 0
        var touchX = 0f; var touchY = 0f
        var isDragging = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (dx * dx + dy * dy > 100) isDragging = true
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

    private fun updateButtonState() {
        val service = ZaloPilotAccessibilityService.instance
        val running = service?.let { true } == true && ZaloPilotAccessibilityService.isActive
        floatingView?.findViewById<TextView>(R.id.tvFloatingBtn)?.text = if (running) "ZP▶" else "ZP"
    }

    private fun updateProgressText() {
        menuView?.findViewById<TextView>(R.id.tvProgress)?.let {
            val progress = progressManager.load()
            val limit = settingsManager.load().dailyLimit
            it.text = "📊 Hôm nay: ${progress.todayLikeCount}/$limit"
        }
    }

    // ─── Menu ─────────────────────────────────────────────────────

    private fun toggleMenu() {
        if (isMenuOpen) closeMenu() else openMenu()
    }

    private fun openMenu() {
        if (isMenuOpen) return
        isMenuOpen = true

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80; y = 300
        }

        menuView = LayoutInflater.from(this).inflate(R.layout.floating_menu, null)
        windowManager.addView(menuView, params)
        setupMenuActions()
        updateMenuLabels()
        updateProgressText()
    }

    private fun closeMenu() {
        if (!isMenuOpen) return
        isMenuOpen = false
        menuView?.let { windowManager.removeView(it) }
        menuView = null
    }

    private fun setupMenuActions() {
        val menu = menuView ?: return

        // Start / Stop
        menu.findViewById<TextView>(R.id.btnStartStop).setOnClickListener {
            val service = ZaloPilotAccessibilityService.instance
            if (service != null) {
                if (ZaloPilotAccessibilityService.isActive) {
                    service.stopAutoLike()
                } else {
                    service.startAutoLike()
                }
            }
            updateMenuLabels()
            closeMenu()
        }

        // Toggle Feed / Visit mode
        menu.findViewById<TextView>(R.id.btnMode).setOnClickListener {
            settingsManager.toggleLikeMode()
            updateMenuLabels()
        }

        // Toggle Auto / Manual
        menu.findViewById<TextView>(R.id.btnAutoManual).setOnClickListener {
            settingsManager.toggleAutoStart()
            updateMenuLabels()
        }

        // Close
        menu.findViewById<TextView>(R.id.btnClose).setOnClickListener {
            closeMenu()
        }
    }

    private fun updateMenuLabels() {
        val menu = menuView ?: return
        val settings = settingsManager.load()
        val running = ZaloPilotAccessibilityService.isActive

        menu.findViewById<TextView>(R.id.btnStartStop).text =
            if (running) "■  Dừng" else "▶  Bắt đầu"

        menu.findViewById<TextView>(R.id.btnMode).text =
            if (settings.likeMode == LikeMode.FEED) "🔄  Feed Mode" else "🔄  Visit Mode"

        menu.findViewById<TextView>(R.id.btnAutoManual).text =
            if (settings.autoStart) "⚙  Tự động" else "⚙  Thủ công"
    }

    // ─── Notification ─────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val channelId = "zalopilot_service"
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(channelId) == null) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "ZaloPilot", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("ZaloPilot đang chạy")
            .setContentText("Nhấn để mở cài đặt")
            .setSmallIcon(R.drawable.ic_notification)
            .build()
    }
}
