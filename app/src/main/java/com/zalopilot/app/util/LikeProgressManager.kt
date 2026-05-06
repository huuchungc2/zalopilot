package com.zalopilot.app.util

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class LikeProgress(
    val todayLikeCount: Int = 0,
    val lastRunDate: String = "",
    val visitIndex: Int = 0  // Visit mode: đã đi tới người thứ N trong danh bạ
)

@Singleton
class LikeProgressManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsManager: LikeSettingsManager
) {
    private val prefs = context.getSharedPreferences("like_progress", Context.MODE_PRIVATE)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    fun load(): LikeProgress {
        return LikeProgress(
            todayLikeCount = prefs.getInt("today_count", 0),
            lastRunDate = prefs.getString("last_date", "") ?: "",
            visitIndex = prefs.getInt("visit_index", 0)
        )
    }

    fun incrementAndSave(): LikeProgress {
        val current = load()
        val updated = current.copy(
            todayLikeCount = current.todayLikeCount + 1,
            lastRunDate = today()
        )
        prefs.edit()
            .putInt("today_count", updated.todayLikeCount)
            .putString("last_date", updated.lastRunDate)
            .apply()
        return updated
    }

    fun saveVisitIndex(index: Int) {
        prefs.edit().putInt("visit_index", index).apply()
    }

    fun isLimitReached(): Boolean {
        val progress = load()
        val limit = settingsManager.load().dailyLimit
        return progress.todayLikeCount >= limit
    }

    fun resetDailyIfNeeded() {
        val progress = load()
        if (progress.lastRunDate != today()) {
            prefs.edit()
                .putInt("today_count", 0)
                .putString("last_date", today())
                .apply()
        }
    }

    private fun today() = dateFormat.format(Date())
}
