package com.zalopilot.app.util

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Logger @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private val dir: File by lazy {
        File(context.filesDir, "ZaloPilot").also { it.mkdirs() }
    }
    val logFile: File by lazy { File(dir, "log.json") }
    val uiTreeFile: File by lazy { File(dir, "uitree.json") }

    // Log hoạt động
    fun log(action: String, target: String = "", result: String = "") {
        scope.launch {
            try {
                val obj = JSONObject().apply {
                    put("ts", dateFormat.format(Date()))
                    put("action", action)
                    put("result", result)
                    put("target", target)
                }
                logFile.appendText(obj.toString() + "\n")
                trimLogIfNeeded()
                Log.d("ZaloPilot", "[$action][$result] $target")
            } catch (e: Exception) {
                Log.e("ZaloPilot", "Logger failed", e)
            }
        }
    }

    fun readLogs(limit: Int = 200): List<LogEntry> {
        return try {
            if (!logFile.exists()) return emptyList()
            logFile.readLines()
                .filter { it.isNotBlank() }
                .takeLast(limit)
                .reversed()
                .mapNotNull { parseEntry(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseEntry(line: String): LogEntry? {
        return try {
            val j = JSONObject(line)
            LogEntry(
                timestamp = j.optString("ts"),
                action    = j.optString("action"),
                result    = j.optString("result"),
                target    = j.optString("target")
            )
        } catch (e: Exception) {
            // fallback đọc format cũ
            val m = Regex("^\\[(.*?)] \\[(.*?)] \\[(.*?)] \\[(.*)]\$").find(line.trim())
                ?: return null
            val (ts, action, result, target) = m.destructured
            LogEntry(ts, action, result, target)
        }
    }

    private fun trimLogIfNeeded() {
        if (logFile.length() > 5 * 1024 * 1024) {
            val lines = logFile.readLines().filter { it.isNotBlank() }
            logFile.writeText(lines.takeLast(1000).joinToString("\n") + "\n")
        }
    }

    // UI Tree
    fun saveUiTree(nodes: List<UiNodeEntry>) {
        scope.launch {
            try {
                val arr = JSONArray()
                nodes.forEach { node ->
                    arr.put(JSONObject().apply {
                        put("depth", node.depth)
                        put("text", node.text)
                        put("id", node.resourceId)
                        put("class", node.className)
                        put("clickable", node.clickable)
                        put("checked", node.checked)
                    })
                }
                val root = JSONObject().apply {
                    put("ts", dateFormat.format(Date()))
                    put("count", nodes.size)
                    put("nodes", arr)
                }
                uiTreeFile.writeText(root.toString())
                log("UI_TREE", "nodes=${nodes.size}", "SAVED")
            } catch (e: Exception) {
                Log.e("ZaloPilot", "saveUiTree failed", e)
            }
        }
    }

    fun readUiTree(): UiTreeResult? {
        return try {
            if (!uiTreeFile.exists()) return null
            val root = JSONObject(uiTreeFile.readText())
            val arr = root.getJSONArray("nodes")
            val nodes = (0 until arr.length()).map { i ->
                val j = arr.getJSONObject(i)
                UiNodeEntry(
                    depth      = j.optInt("depth"),
                    text       = j.optString("text"),
                    resourceId = j.optString("id"),
                    className  = j.optString("class"),
                    clickable  = j.optBoolean("clickable"),
                    checked    = j.optBoolean("checked")
                )
            }
            UiTreeResult(
                scannedAt = root.optString("ts"),
                count     = root.optInt("count"),
                nodes     = nodes
            )
        } catch (e: Exception) {
            null
        }
    }
}

data class LogEntry(
    val timestamp: String,
    val action: String,
    val result: String,
    val target: String
)

data class UiNodeEntry(
    val depth: Int,
    val text: String,
    val resourceId: String,
    val className: String,
    val clickable: Boolean,
    val checked: Boolean
)

data class UiTreeResult(
    val scannedAt: String,
    val count: Int,
    val nodes: List<UiNodeEntry>
)
