package com.zalopilot.app.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import com.zalopilot.app.accessibility.ZaloPilotAccessibilityService

/** Khởi động bot khi UI không giữ được [ZaloPilotAccessibilityService.instance]. */
object AccessibilityHelper {

    fun isAccessibilityEnabled(context: Context): Boolean {
        val service = "${context.packageName}/com.zalopilot.app.accessibility.ZaloPilotAccessibilityService"
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        return enabled.contains(service)
    }

    /** Thông tin ứng dụng — Samsung: ⋮ → Cho phép cài đặt hạn chế (trước khi bật Trợ năng). */
    fun openAppDetailsSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Mở thẳng trang Trợ năng của ZaloPilot (Android 13+) hoặc danh sách Trợ năng. */
    fun openAccessibilitySettings(context: Context) {
        val component = ComponentName(context, ZaloPilotAccessibilityService::class.java)
        val fallback = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val details = Intent(ACTION_ACCESSIBILITY_DETAILS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_ACCESSIBILITY_SERVICE_COMPONENT_NAME, component.flattenToString())
            }
            val opened = runCatching { context.startActivity(details) }.isSuccess
            if (opened) {
                toastOpenAccessibility(context)
                return
            }
        }
        runCatching { context.startActivity(fallback) }
            .onFailure { runCatching { context.startActivity(fallback) } }
        toastOpenAccessibility(context)
    }

    private fun toastOpenAccessibility(context: Context) {
        Toast.makeText(
            context,
            "Bật công tắc ZaloPilot → Cho phép (nếu máy hỏi)",
            Toast.LENGTH_LONG
        ).show()
    }

    /** API 33+ — dùng string để tránh lỗi compile trên một số SDK CI. */
    private const val ACTION_ACCESSIBILITY_DETAILS_SETTINGS =
        "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
    private const val EXTRA_ACCESSIBILITY_SERVICE_COMPONENT_NAME =
        "android.provider.extra.ACCESSIBILITY_SERVICE_COMPONENT_NAME"

    /**
     * @param entry [BotStartEntry.HOME_LIKE_BUTTON] cho nút Trang chủ; [BotStartEntry.FLOATING_ON_ZALO] cho menu ZP trên Zalo.
     * @return true nếu đã gọi start hoặc đã gửi broadcast chờ service nhận.
     */
    fun requestStartAutoLike(
        context: Context,
        mode: LikeMode? = null,
        entry: BotStartEntry = BotStartEntry.HOME_LIKE_BUTTON
    ): Boolean {
        LikeSettingsManager(context.applicationContext).setBotRunSuppressed(false)
        ZaloPilotAccessibilityService.instance?.let {
            it.startAutoLike(mode, userInitiated = true, startEntry = entry)
            return true
        }
        if (!isAccessibilityEnabled(context)) {
            Toast.makeText(
                context,
                "⚠️ Chưa bật Trợ năng — đang mở cài đặt ZaloPilot…",
                Toast.LENGTH_LONG
            ).show()
            openAccessibilitySettings(context)
            return false
        }
        val broadcast = Intent(ZaloPilotAccessibilityService.ACTION_START_AUTO_LIKE)
            .setPackage(context.packageName)
        mode?.let { broadcast.putExtra(ZaloPilotAccessibilityService.EXTRA_LIKE_MODE, it.name) }
        broadcast.putExtra(ZaloPilotAccessibilityService.EXTRA_START_ENTRY, entry.name)
        context.sendBroadcast(broadcast)
        Handler(Looper.getMainLooper()).postDelayed({
            val svc = ZaloPilotAccessibilityService.instance
            if (svc != null) {
                svc.startAutoLike(mode, userInitiated = true, startEntry = entry)
            } else {
                Toast.makeText(
                    context,
                    "⚠️ Trợ năng đã bật nhưng chưa kết nối — mở Zalo rồi bấm BẮT ĐẦU lại, hoặc tắt/bật ZaloPilot trong Trợ năng",
                    Toast.LENGTH_LONG
                ).show()
            }
        }, 450L)
        return true
    }

    fun requestStopAutoLike(context: Context) {
        LikeSettingsManager(context.applicationContext).setBotRunSuppressed(true)
        ZaloPilotAccessibilityService.instance?.stopAutoLike(userRequested = true)
    }
}
