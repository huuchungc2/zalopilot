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
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.zalopilot.app.accessibility.ZaloPilotAccessibilityService
import com.zalopilot.app.util.LikeProgressManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class FloatingMenuService : Service() {

    @Inject lateinit var progressManager: LikeProgressManager

    private lateinit var windowManager: WindowManager
    private var fabView: View? = null
    private var menuView: View? = null
    private var isMenuOpen = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateFabStatus()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForeground(NOTIF_ID, buildNotification())
        registerReceiver(statusReceiver, IntentFilter("com.zalopilot.STATUS_UPDATE"))
        registerReceiver(statusReceiver, IntentFilter("com.zalopilot.PROGRESS_UPDATE"))
        showFab()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(statusReceiver)
        fabView?.let { windowManager.removeView(it) }
        menuView?.let { windowManager.removeView(it) }
    }

    private fun showFab() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.BOTTOM
            x = 20
            y = 120
        }

        val fab = TextView(this).apply {
            text = "ZP"
            textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(android.graphics.Color.parseColor("#0068FF"))
            val size = (52 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            setPadding(8, 8, 8, 8)

            setOnClickListener { toggleMenu() }
        }

        fabView = fab
        windowManager.addView(fab, params)
    }

    private fun toggleMenu() {
        if (isMenuOpen) closeMenu() else openMenu()
    }

    private fun openMenu() {
        isMenuOpen = true
        val progress = progressManager.load()
        val isRunning = ZaloPilotAccessibilityService.instance?.let { true } ?: false

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.BOTTOM
            x = 20
            y = 200
        }

        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(android.graphics.Color.parseColor("#1a1a2e"))

            // Chip tiến độ
            addView(makeMenuItem("${progress.todayLikeCount}/${progressManager.load().let { settingsManager?.load()?.dailyLimit ?: 100 }} hôm nay", "#0068FF"))

            // Start/Stop
            if (isRunning) {
                addView(makeMenuItem("■  Dừng lại", "#E24B4A") {
                    ZaloPilotAccessibilityService.instance?.stopAutoLike()
                    closeMenu()
                })
            } else {
                addView(makeMenuItem("▶  Bắt đầu like", "#27AE60") {
                    ZaloPilotAccessibilityService.instance?.startAutoLike()
                    closeMenu()
                })
            }

            // Đóng menu
            addView(makeMenuItem("✕  Đóng", "#555555") { closeMenu() })
        }

        menuView = menu
        windowManager.addView(menu, params)
    }

    private fun makeMenuItem(text: String, color: String, onClick: (() -> Unit)? = null): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(24, 16, 24, 16)
            setBackgroundColor(android.graphics.Color.parseColor(color.replace("#", "#22") + "44").also {})
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(0, 4, 0, 4)
            layoutParams = lp
            if (onClick != null) setOnClickListener { onClick() }
        }
    }

    private fun closeMenu() {
        isMenuOpen = false
        menuView?.let {
            windowManager.removeView(it)
            menuView = null
        }
    }

    private fun updateFabStatus() {
        // Update fab màu khi trạng thái thay đổi
    }

    private fun buildNotification(): Notification {
        val channelId = "zalopilot_floating"
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(channelId) == null) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "ZaloPilot Floating", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("ZaloPilot đang chạy")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 1002
    }
}
