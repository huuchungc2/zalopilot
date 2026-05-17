package com.zalopilot.app.util

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class VisitHandledOutcome {
    LIKE,
    COMMENT,
    CHAT,
    MIX,
    SKIPPED,
    FAILED
}

data class VisitHandledContact(
    val key: String,
    val displayName: String,
    val outcome: String,
    val updatedAtMs: Long = System.currentTimeMillis()
)

/**
 * Danh sách bạn đã Visit (like / comment / chat) — persist, check trùng theo tên chuẩn hóa.
 * Danh bạ Zalo thường sort A–Z: bot cuộn khi cả màn đã nằm trong list.
 */
@Singleton
class VisitHandledContactsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("visit_handled_contacts", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val listType = object : TypeToken<List<VisitHandledContact>>() {}.type

    fun loadAll(): List<VisitHandledContact> {
        val json = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            gson.fromJson<List<VisitHandledContact>>(json, listType) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun count(): Int = loadAll().size

    fun isHandled(key: String): Boolean {
        if (key.isBlank()) return false
        return loadAll().any { it.key == key }
    }

    /** Dòng đầu label hàng danh bạ / title chat → key. */
    fun keyFromTapLabel(label: String?): String? {
        val name = firstLineDisplayName(label) ?: return null
        return normalizeKey(name)
    }

    fun firstLineDisplayName(label: String?): String? {
        val line = label?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        if (line.length < 2) return null
        return line
    }

    fun normalizeKey(displayName: String): String =
        displayName.trim().lowercase().replace(Regex("\\s+"), " ")

    fun record(
        displayName: String,
        outcome: VisitHandledOutcome,
        key: String = normalizeKey(displayName)
    ) {
        if (key.isBlank() || displayName.isBlank()) return
        val now = System.currentTimeMillis()
        val updated = loadAll()
            .filterNot { it.key == key }
            .toMutableList()
        updated.add(
            VisitHandledContact(
                key = key,
                displayName = displayName.trim(),
                outcome = outcome.name,
                updatedAtMs = now
            )
        )
        while (updated.size > MAX_ENTRIES) {
            updated.removeAt(0)
        }
        prefs.edit().putString(KEY_ENTRIES, gson.toJson(updated)).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_ENTRIES).apply()
    }

    /** Gần đây nhất trước (để hiển thị UI). */
    fun recent(limit: Int = 30): List<VisitHandledContact> =
        loadAll().sortedByDescending { it.updatedAtMs }.take(limit.coerceIn(1, MAX_ENTRIES))

    private companion object {
        const val KEY_ENTRIES = "entries"
        const val MAX_ENTRIES = 2_000
    }
}
