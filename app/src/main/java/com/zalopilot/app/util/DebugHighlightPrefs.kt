package com.zalopilot.app.util

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DebugHighlightPrefs @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("zp_debug_overlay", Context.MODE_PRIVATE)

    fun isNodeHighlightEnabled(): Boolean = prefs.getBoolean(KEY_NODE_HIGHLIGHT, false)

    fun setNodeHighlightEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_NODE_HIGHLIGHT, value).apply()
    }

    /** Dump cây UI / nút like chi tiết — mặc định tắt để log gọn. */
    fun isVerboseUiTreeLoggingEnabled(): Boolean =
        prefs.getBoolean(KEY_VERBOSE_UI_TREE, false)

    fun setVerboseUiTreeLoggingEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_VERBOSE_UI_TREE, value).apply()
    }

    fun isVerboseLikeContextLoggingEnabled(): Boolean =
        prefs.getBoolean(KEY_VERBOSE_LIKE_CONTEXT, false)

    fun setVerboseLikeContextLoggingEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_VERBOSE_LIKE_CONTEXT, value).apply()
    }

    private companion object {
        const val KEY_NODE_HIGHLIGHT = "node_highlight_enabled"
        const val KEY_VERBOSE_UI_TREE = "verbose_ui_tree_log"
        const val KEY_VERBOSE_LIKE_CONTEXT = "verbose_like_context_log"
    }
}
