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
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Intent(Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS).apply {
                putExtra(Settings.EXTRA_ACCESSIBILITY_SERVICE_COMPONENT_NAME, component.flattenToString())
            }
        } else {
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        Toast.makeText(
            context,
            "Bật công tắc ZaloPilot → Cho phép (nếu máy hỏi)",
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * @return true nếu đã gọi start hoặc đã gửi broadcast chờ service nhận.
     */
    fun requestStartAutoLike(context: Context): Boolean {
        ZaloPilotAccessibilityService.instance?.let {
            it.startAutoLike()
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
        context.sendBroadcast(
            Intent(ZaloPilotAccessibilityService.ACTION_START_AUTO_LIKE)
                .setPackage(context.packageName)
        )
        Handler(Looper.getMainLooper()).postDelayed({
            val svc = ZaloPilotAccessibilityService.instance
            if (svc != null) {
                svc.startAutoLike()
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

    fun requestStopAutoLike() {
        ZaloPilotAccessibilityService.instance?.stopAutoLike()
            ?: run {
                // Service có thể đã disconnect — broadcast không cần cho stop.
            }
    }
}
