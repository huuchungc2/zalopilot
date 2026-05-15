package com.zalopilot.app.util

import android.content.Context
import com.zalopilot.app.BuildConfig

/** Nhãn phiên bản — lấy từ [BuildConfig] (đồng bộ với `app/build.gradle.kts`). */
object AppVersion {
    val versionName: String get() = BuildConfig.VERSION_NAME
    val versionCode: Int get() = BuildConfig.VERSION_CODE

    /** Hiển thị gọn: `v1.0.2` */
    fun shortLabel(): String = "v$versionName"

    /** Hiển thị đủ: `v1.0.2 (3)` — build number để phân biệt APK CI. */
    fun fullLabel(): String = "v$versionName ($versionCode)"

    fun fromContext(context: Context): String {
        return runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            val name = pi.versionName ?: versionName
            @Suppress("DEPRECATION")
            val code = pi.versionCode
            "v$name ($code)"
        }.getOrElse { fullLabel() }
    }
}
