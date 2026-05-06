package com.zalopilot.app.floating

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
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
import androidx.core.app.NotificationCompat
import com.zalopilot.app.accessibility.ZaloPilotAccessibilityService
import com.zalopilot.app.util.LikeProgressManager
import com.zalopilot.app.util.LikeSettingsManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class FloatingMenuService : Service() {

    @Inject lateinit var settingsManager: LikeSettingsManager
    @Inject lateinit var progressManager: LikeProgressManager

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
        try { unregisterReceiver(statusReceiver) } catch (e: Exception) {}
        fabView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
        menuView?.let { try { windowManager.removeView(it) } catch (e: Exception) {} }
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
        val isRunning = ZaloPilotAccessibilityService.isActive
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

            addView(menuItem(
                if (autoMode) "🤖 Tự động: BẬT" else "👆 Thủ công",
                if (autoMode) "#2E75B6" else "#555555"
            ) {
                settingsManager.toggleAutoStart()
                closeMenu(); openMenu()
            })

            if (isRunning) {
                addView(menuItem("■  Dừng", "#E24B4A") {
                    ZaloPilotAccessibilityService.instance?.stopAutoLike()
                    closeMenu()
                })
            } else {
                addView(menuItem("▶  Bắt đầu like", "#27AE60") {
                    ZaloPilotAccessibilityService.instance?.startAutoLike()
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
        val isRunning = ZaloPilotAccessibilityService.isActive
        fabView?.setBackgroundColor(
            if (isRunning) Color.parseColor("#27AE60") else Color.parseColor("#0068FF")
        )
        fabView?.text = if (isRunning) "ZP▶" else "ZP"
    }

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
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 1
    }
}
