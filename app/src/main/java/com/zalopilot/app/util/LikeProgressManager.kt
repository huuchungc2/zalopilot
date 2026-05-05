package com.zalopilot.app.util

import android.content.Context
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

data class LikeProgress(
    val totalFriends: Int = 0,
    val lastLikedIndex: Int = 0,
    val todayLikeCount: Int = 0,
    val lastRunDate: String = "",
    val totalRounds: Int = 0
)

@Singleton
class LikeProgressManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsManager: LikeSettingsManager
) {
    private val prefs = context.getSharedPreferences("like_progress", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    fun load(): LikeProgress {
        val json = prefs.getString("progress", null) ?: return LikeProgress()
        return try {
            gson.fromJson(json, LikeProgress::class.java)
        } catch (e: Exception) {
            LikeProgress()
        }
    }

    fun save(progress: LikeProgress) {
        prefs.edit().putString("progress", gson.toJson(progress)).apply()
    }

    fun resetDailyIfNeeded() {
        val progress = load()
        val today = dateFormat.format(Date())
        if (progress.lastRunDate != today) {
            save(progress.copy(todayLikeCount = 0, lastRunDate = today))
        }
    }

    fun isLimitReached(): Boolean {
        val progress = load()
        val settings = settingsManager.load()
        return progress.todayLikeCount >= settings.dailyLimit
    }

    fun incrementAndSave(): LikeProgress {
        val progress = load()
        val settings = settingsManager.load()
        val today = dateFormat.format(Date())
        var newIndex = progress.lastLikedIndex + 1
        var newRounds = progress.totalRounds
        if (newIndex >= progress.totalFriends && progress.totalFriends > 0) {
            newIndex = 0
            newRounds++
        }
        val updated = progress.copy(
            lastLikedIndex = newIndex,
            todayLikeCount = progress.todayLikeCount + 1,
            lastRunDate = today,
            totalRounds = newRounds
        )
        save(updated)
        return updated
    }

    fun getRemainingDays(): Int {
        val progress = load()
        val settings = settingsManager.load()
        val remaining = progress.totalFriends - progress.lastLikedIndex
        return if (settings.dailyLimit > 0) (remaining / settings.dailyLimit) else 0
    }

    fun updateTotalFriends(total: Int) {
        val progress = load()
        save(progress.copy(totalFriends = total))
    }
}
