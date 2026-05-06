package com.zalopilot.app.util

import android.content.Context
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class LikeMode { FEED, VISIT }

data class LikeSettings(
    val dailyLimit: Int = 100,
    val delayMinMs: Long = 1000,
    val delayMaxMs: Long = 3000,
    val sessionLimit: Int = 30,
    val restMinMinutes: Int = 5,
    val restMaxMinutes: Int = 10,
    val quietHourStart: Int = 22,
    val quietHourEnd: Int = 6,
    val autoStart: Boolean = false,
    val likeModeStr: String = "FEED"  // Lưu String thay vì enum tránh Gson fail
)

@Singleton
class LikeSettingsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("like_settings", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun load(): LikeSettings {
        val json = prefs.getString("settings", null) ?: return LikeSettings()
        return try {
            gson.fromJson(json, LikeSettings::class.java) ?: LikeSettings()
        } catch (e: Exception) {
            LikeSettings()
        }
    }

    fun save(settings: LikeSettings) {
        prefs.edit().putString("settings", gson.toJson(settings)).apply()
    }

    fun isQuietHour(): Boolean {
        val settings = load()
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return if (settings.quietHourStart > settings.quietHourEnd) {
            hour >= settings.quietHourStart || hour < settings.quietHourEnd
        } else {
            hour >= settings.quietHourStart && hour < settings.quietHourEnd
        }
    }

    fun isAutoStart(): Boolean = load().autoStart

    fun getLikeMode(): LikeMode {
        return try {
            LikeMode.valueOf(load().likeModeStr)
        } catch (e: Exception) {
            LikeMode.FEED
        }
    }

    fun toggleAutoStart() {
        val s = load()
        save(s.copy(autoStart = !s.autoStart))
    }

    fun toggleLikeMode() {
        val newMode = if (getLikeMode() == LikeMode.FEED) LikeMode.VISIT else LikeMode.FEED
        save(load().copy(likeModeStr = newMode.name))
    }

    fun setAutoStart(value: Boolean) {
        save(load().copy(autoStart = value))
    }
}
