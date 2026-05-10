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

    private companion object {
        const val KEY_NODE_HIGHLIGHT = "node_highlight_enabled"
    }
}
