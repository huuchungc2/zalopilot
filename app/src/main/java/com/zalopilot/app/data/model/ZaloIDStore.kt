package com.zalopilot.app.data.model

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lưu trữ resource-id thực tế của Zalo UI elements.
 * App tự học từ UI Zalo → lưu vào đây → dùng lại.
 * Zalo update đổi ID → ZaloUIScanner tìm lại → lưu đè.
 */
@Singleton
class ZaloIDStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("zalo_ui_ids", Context.MODE_PRIVATE)

    companion object {
        // Keys
        const val KEY_LIKE_BUTTON = "like_button_id"
        const val KEY_TAB_TIMELINE = "tab_timeline_id"
        const val KEY_FEED_RECYCLER = "feed_recycler_id"
        const val KEY_AUTHOR_NAME = "author_name_id"
        const val KEY_CONTACT_LIST = "contact_list_id"
        const val KEY_CONTACT_ITEM = "contact_item_id"

        // Text fallback dùng để tìm khi chưa có ID hoặc ID vỡ
        const val TEXT_LIKE = "Thích"
        const val TEXT_LIKED = "Đã thích"
        const val TEXT_TIMELINE = "Nhật ký"
        const val CLASS_RECYCLER = "androidx.recyclerview.widget.RecyclerView"

        /**
         * Neo layout feed cố định theo package Zalo — chỉ để biết feed đã render,
         * không dùng làm ID học động cho nút like.
         */
        val FEED_LAYOUT_ANCHOR_IDS = listOf(
            "com.zing.zalo:id/layoutSocialFeed",
            "com.zing.zalo:id/lv_media_store"
        )
    }

    fun getID(key: String): String? = prefs.getString(key, null)

    fun saveID(key: String, resourceId: String) {
        if (resourceId.isBlank()) return
        prefs.edit().putString(key, resourceId).apply()
    }

    fun getLikeButtonID(): String? = getID(KEY_LIKE_BUTTON)
    fun getTabTimelineID(): String? = getID(KEY_TAB_TIMELINE)
    fun getFeedRecyclerID(): String? = getID(KEY_FEED_RECYCLER)
    fun getAuthorNameID(): String? = getID(KEY_AUTHOR_NAME)

    fun saveLikeButtonID(id: String) = saveID(KEY_LIKE_BUTTON, id)
    fun saveTabTimelineID(id: String) = saveID(KEY_TAB_TIMELINE, id)
    fun saveFeedRecyclerID(id: String) = saveID(KEY_FEED_RECYCLER, id)
    fun saveAuthorNameID(id: String) = saveID(KEY_AUTHOR_NAME, id)

    fun hasScanned(): Boolean = getLikeButtonID() != null

    fun clearAll() = prefs.edit().clear().apply()
}
