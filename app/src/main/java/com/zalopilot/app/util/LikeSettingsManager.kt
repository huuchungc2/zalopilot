package com.zalopilot.app.util

import android.content.Context
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class LikeMode { FEED, VISIT }

/**
 * Giữ enum tương thích Gson / script; hành vi UI **luôn** touch (gesture + vuốt cuộn — xem [LikeSettingsManager.getInteractMode]).
 */
enum class InteractMode { TAP, SWIPE, MIX }

enum class FeedMode { SCROLL, MANUAL, MIX }

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
    val likeModeStr: String = "FEED",
    /** Giữ field cho prefs cũ; [getInteractMode] luôn TAP (touch like trước). */
    val interactModeStr: String = "TAP",
    /** Giữ field cho prefs cũ; [isHumanLikeScroll] luôn true (vuốt tay khi cuộn). */
    val humanLikeScroll: Boolean = true,
    /** Giảm tần suất poll/scan và tăng delay — ít nóng, ít hao pin hơn (hơi chậm hơn). */
    val ecoMode: Boolean = false,
    /** Chỉ chạy khi cắm sạc — rút sạc thì pause, cắm lại tự chạy tiếp (không stop hẳn). */
    val requireCharging: Boolean = false,
    /** Pause khi pin xuống dưới [lowBatteryThreshold]; pin sạc lại lên trên ngưỡng → tự chạy tiếp. */
    val lowBatteryPauseEnabled: Boolean = true,
    /** Ngưỡng % pin để pause (chỉ áp dụng khi [lowBatteryPauseEnabled]). */
    val lowBatteryThreshold: Int = 20,
    /** Khi user rời Zalo lâu, slow down poll mạnh để khỏi ngốn pin (vẫn check để biết khi nào về Zalo). */
    val pauseWhenZaloAway: Boolean = true,
    val visitLikeCount: Int = 3,
    val visitCommentCount: Int = 0,
    /** Số comment gửi trên mỗi bài sau khi like thành công ở chế độ Nhật ký (0 = chỉ like). Dùng chung [visitCommentList]. */
    val feedCommentCount: Int = 0,
    val visitActionMode: String = "LIKE_ONLY",
    val visitMaxProfiles: Int = 50,
    val visitCommentList: List<String> = listOf("👍", "❤️", "Hay quá!", "Tuyệt vời!")
)

enum class VisitActionMode { LIKE_ONLY, COMMENT_ONLY, MIX }

@Singleton
class LikeSettingsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("like_settings", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun load(): LikeSettings {
        val json = prefs.getString("settings", null) ?: return LikeSettings()
        return try {
            normalizeVisitFields(gson.fromJson(json, LikeSettings::class.java) ?: LikeSettings())
        } catch (e: Exception) {
            LikeSettings()
        }
    }

    private fun normalizeVisitFields(s: LikeSettings): LikeSettings {
        val comments = s.visitCommentList.ifEmpty { defaultVisitComments() }
        return s.copy(
            visitLikeCount = s.visitLikeCount.coerceIn(0, 10),
            visitCommentCount = s.visitCommentCount.coerceIn(0, 5),
            feedCommentCount = s.feedCommentCount.coerceIn(0, 5),
            visitMaxProfiles = s.visitMaxProfiles.coerceIn(1, 500),
            visitCommentList = comments,
            interactModeStr = InteractMode.TAP.name,
            humanLikeScroll = true
        )
    }

    private fun defaultVisitComments(): List<String> =
        listOf("👍", "❤️", "Hay quá!", "Tuyệt vời!")

    fun save(settings: LikeSettings) {
        prefs.edit().putString("settings", gson.toJson(settings)).apply()
    }

    /**
     * Tính năng "Giờ không hoạt động" đã bị **gỡ khỏi UI Cài đặt**.
     * Giữ hàm + field `quietHourStart/End` chỉ để tương thích settings cũ đã lưu — luôn trả `false`,
     * bot **không** còn tự dừng theo giờ.
     */
    @Suppress("UNUSED_PARAMETER")
    fun isQuietHour(): Boolean = false

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

    fun setLikeMode(mode: LikeMode) {
        save(load().copy(likeModeStr = mode.name))
    }

    fun setAutoStart(value: Boolean) {
        save(load().copy(autoStart = value))
    }

    /** Luôn TAP — chạm tọa độ (gesture) trước, `ACTION_CLICK` chỉ fallback khi touch fail. */
    fun getInteractMode(): InteractMode = InteractMode.TAP

    /** Luôn true — cuộn feed ưu tiên vuốt tay (gesture), không theo API scroll trước. */
    fun isHumanLikeScroll(): Boolean = true

    fun getFeedMode(): FeedMode {
        val name = prefs.getString("feed_mode", FeedMode.SCROLL.name) ?: FeedMode.SCROLL.name
        return try {
            FeedMode.valueOf(name)
        } catch (e: Exception) {
            FeedMode.SCROLL
        }
    }

    fun setFeedMode(mode: FeedMode) {
        prefs.edit().putString("feed_mode", mode.name).apply()
    }

    fun isEcoMode(): Boolean = load().ecoMode

    fun isRequireCharging(): Boolean = load().requireCharging
    fun isLowBatteryPauseEnabled(): Boolean = load().lowBatteryPauseEnabled
    fun getLowBatteryThreshold(): Int = load().lowBatteryThreshold
    fun isPauseWhenZaloAway(): Boolean = load().pauseWhenZaloAway

    fun getVisitLikeCount(): Int = load().visitLikeCount
    fun getVisitCommentCount(): Int = load().visitCommentCount
    fun getFeedCommentCount(): Int = load().feedCommentCount
    fun getVisitMaxProfiles(): Int = load().visitMaxProfiles
    fun getVisitCommentList(): List<String> = load().visitCommentList

    fun getVisitActionMode(): VisitActionMode {
        return try {
            VisitActionMode.valueOf(load().visitActionMode)
        } catch (e: Exception) {
            VisitActionMode.LIKE_ONLY
        }
    }

    fun setVisitLikeCount(count: Int) {
        save(load().copy(visitLikeCount = count.coerceIn(0, 10)))
    }

    fun setVisitCommentCount(count: Int) {
        save(load().copy(visitCommentCount = count.coerceIn(0, 5)))
    }

    fun setVisitActionMode(mode: VisitActionMode) {
        save(load().copy(visitActionMode = mode.name))
    }

    fun resetVisitCommentsToDefault() {
        save(load().copy(visitCommentList = defaultVisitComments()))
    }
}
