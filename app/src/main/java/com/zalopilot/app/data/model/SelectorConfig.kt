package com.zalopilot.app.data.model

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.zalopilot.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class SelectorItem(
    val resourceId: String = "",
    val textFallback: String? = null,
    val contentDescFallback: String? = null,
    val alreadySelectedText: String? = null,
    val classFallback: String? = null
)

data class SwipeConfig(
    val fromX: Int = 500,
    val fromY: Int = 1500,
    val toX: Int = 500,
    val toY: Int = 500,
    val durationMs: Long = 500
)

@Singleton
class SelectorConfig @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val gson = Gson()
    private var config: JsonObject? = null

    init {
        load()
    }

    private fun load() {
        try {
            val inputStream = context.resources.openRawResource(R.raw.selector_config)
            val json = inputStream.bufferedReader().readText()
            config = gson.fromJson(json, JsonObject::class.java)
        } catch (e: Exception) {
            config = null
        }
    }

    fun getLikeButton(): SelectorItem = getSelector("like_button")
    fun getTabTimeline(): SelectorItem = getSelector("tab_timeline")
    fun getScrollContainer(): SelectorItem = getSelector("scroll_container")

    fun getSwipeConfig(): SwipeConfig {
        val obj = config?.getAsJsonObject("swipe_gesture") ?: return SwipeConfig()
        return SwipeConfig(
            fromX = obj.get("from_x")?.asInt ?: 500,
            fromY = obj.get("from_y")?.asInt ?: 1500,
            toX = obj.get("to_x")?.asInt ?: 500,
            toY = obj.get("to_y")?.asInt ?: 500,
            durationMs = obj.get("duration_ms")?.asLong ?: 500L
        )
    }

    private fun getSelector(key: String): SelectorItem {
        val obj = config?.getAsJsonObject(key) ?: return SelectorItem()
        return SelectorItem(
            resourceId = obj.get("resource_id")?.asString ?: "",
            textFallback = obj.get("text_fallback")?.asString,
            contentDescFallback = obj.get("content_desc_fallback")?.asString,
            alreadySelectedText = obj.get("already_selected_text")?.asString,
            classFallback = obj.get("class_fallback")?.asString
        )
    }
}
