package com.zalopilot.app.data.model

import android.content.Context
import java.io.File
import org.json.JSONObject
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lưu trữ resource-id thực tế của Zalo UI elements.
 * App tự học từ UI Zalo → lưu vào đây → dùng lại.
 * Zalo update đổi ID → ZaloUIScanner tìm lại → lưu đè.
 *
 * Script DSL (`ZPEngine.resolveVar`): `$contactListId`, `$contactItemId`,
 * `$likeButtonId`, `$feedRecyclerId` (chuỗi id đầy đủ hoặc rỗng nếu chưa học).
 * Action `logStoreIds` ghi các id đã học ra log (debug script).
 */
@Singleton
class ZaloIDStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("zalo_ui_ids", Context.MODE_PRIVATE)
    private val uiMapFile: File by lazy { File(context.filesDir, "ui_map.json") }

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
    fun getContactListID(): String? = getID(KEY_CONTACT_LIST)
    fun getContactItemID(): String? = getID(KEY_CONTACT_ITEM)

    fun saveLikeButtonID(id: String) = saveID(KEY_LIKE_BUTTON, id)

    fun clearLikeButtonID() {
        prefs.edit().remove(KEY_LIKE_BUTTON).apply()
    }
    fun saveTabTimelineID(id: String) = saveID(KEY_TAB_TIMELINE, id)
    fun saveFeedRecyclerID(id: String) = saveID(KEY_FEED_RECYCLER, id)
    fun saveAuthorNameID(id: String) = saveID(KEY_AUTHOR_NAME, id)
    fun saveContactListID(id: String) = saveID(KEY_CONTACT_LIST, id)
    fun saveContactItemID(id: String) = saveID(KEY_CONTACT_ITEM, id)

    fun clearContactListID() {
        prefs.edit().remove(KEY_CONTACT_LIST).apply()
    }

    fun clearContactItemID() {
        prefs.edit().remove(KEY_CONTACT_ITEM).apply()
    }

    fun hasScanned(): Boolean = getLikeButtonID() != null

    fun hasVisitContactIds(): Boolean = getContactListID() != null

    fun clearAll() = prefs.edit().clear().apply()

    /**
     * Hiển thị trong app (tab UI) — mọi slot học ID, kể cả chưa có giá trị.
     */
    fun listStoredIdsForDebug(): List<Pair<String, String>> {
        val rows = listOf(
            KEY_LIKE_BUTTON to "Nút like",
            KEY_TAB_TIMELINE to "Tab Nhật ký",
            KEY_FEED_RECYCLER to "Recycler feed",
            KEY_AUTHOR_NAME to "Tên tác giả",
            KEY_CONTACT_LIST to "Danh sách liên hệ",
            KEY_CONTACT_ITEM to "Ô danh bạ"
        )
        return rows.map { (key, label) ->
            val v = getID(key)
            val display = if (v.isNullOrBlank()) "— (chưa học)" else v
            label to display
        }
    }

    fun getStoredIdsDebugTextWithKeys(): String {
        val rows = listOf(
            KEY_LIKE_BUTTON to "Nút like",
            KEY_TAB_TIMELINE to "Tab Nhật ký",
            KEY_FEED_RECYCLER to "Recycler feed",
            KEY_AUTHOR_NAME to "Tên tác giả",
            KEY_CONTACT_LIST to "Danh sách liên hệ",
            KEY_CONTACT_ITEM to "Ô danh bạ"
        )
        return rows.joinToString("\n") { (key, label) ->
            val v = getID(key)
            val display = if (v.isNullOrBlank()) "— (chưa học)" else v
            "$label [$key]\n  $display"
        }
    }

    /**
     * Export mapping UI ids ra internal storage (`filesDir/ui_map.json`).
     * @return file nếu ghi thành công.
     */
    fun exportToJson(): File {
        val json = JSONObject()
        val keys = listOf(
            KEY_LIKE_BUTTON,
            KEY_TAB_TIMELINE,
            KEY_FEED_RECYCLER,
            KEY_AUTHOR_NAME,
            KEY_CONTACT_LIST,
            KEY_CONTACT_ITEM
        )
        for (k in keys) {
            val v = getID(k)
            if (!v.isNullOrBlank()) json.put(k, v)
        }
        uiMapFile.writeText(json.toString(2))
        return uiMapFile
    }

    /**
     * Import mapping UI ids từ internal storage (`filesDir/ui_map.json`).
     * @return true nếu import OK.
     */
    fun importFromJson(): Boolean {
        if (!uiMapFile.exists()) return false
        val raw = uiMapFile.readText().trim()
        if (raw.isBlank()) return false
        val json = JSONObject(raw)
        val editor = prefs.edit()
        val keys = listOf(
            KEY_LIKE_BUTTON,
            KEY_TAB_TIMELINE,
            KEY_FEED_RECYCLER,
            KEY_AUTHOR_NAME,
            KEY_CONTACT_LIST,
            KEY_CONTACT_ITEM
        )
        for (k in keys) {
            val v = json.optString(k, "")
            if (v.isNotBlank()) editor.putString(k, v)
        }
        editor.apply()
        return true
    }
}
