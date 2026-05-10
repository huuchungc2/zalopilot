package com.zalopilot.app.util

import android.content.Context
import android.os.SystemClock
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
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Logger @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    @Volatile
    var lastForegroundPackage: String = ""
        private set

    fun setForegroundPackage(packageName: String?) {
        lastForegroundPackage = packageName?.trim().orEmpty()
    }

    private val dir: File by lazy {
        File(context.filesDir, "ZaloPilot").also { it.mkdirs() }
    }
    val logFile: File by lazy { File(dir, "log.json") }
    val uiTreeFile: File by lazy { File(dir, "uitree.json") }

    /**
     * Ghi log có cấu trúc: [tag], [target], [result], tự đính kèm [pkg] foreground hiện tại, tùy chọn [ms].
     */
    fun log(
        tag: LogTag,
        target: String = "",
        result: String = "",
        durationMs: Long? = null
    ) {
        val pkgSnapshot = lastForegroundPackage
        val line = buildJson(tag, target, result, pkgSnapshot, durationMs)
        scope.launch {
            writeLine(line)
        }
    }

    /**
     * Lỗi runtime — không nuốt: luôn ghi file + Logcat.
     */
    fun logError(source: String, throwable: Throwable) {
        val msg = throwable.message ?: throwable.javaClass.simpleName
        val stack = throwable.stackTraceToString().take(3500)
        Log.e("ZaloPilot", "[$source] $msg", throwable)
        val pkgSnapshot = lastForegroundPackage
        val line = buildJson(LogTag.ERROR, source, "$msg | $stack", pkgSnapshot, null)
        scope.launch {
            writeLine(line)
        }
    }

    /** Ghi đồng bộ khi coroutine logger không dùng được (ví dụ lỗi đọc file). */
    private fun appendLineSync(line: String) {
        try {
            logFile.appendText(line)
        } catch (e: Exception) {
            Log.e("ZaloPilot", "appendLineSync failed", e)
        }
    }

    private fun buildJson(
        tag: LogTag,
        target: String,
        result: String,
        pkg: String,
        durationMs: Long?
    ): String {
        return JSONObject().apply {
            put("ts", dateFormat.format(Date()))
            put("tag", tag.name)
            put("target", target)
            put("result", result)
            if (pkg.isNotEmpty()) put("pkg", pkg)
            if (durationMs != null) put("ms", durationMs)
        }.toString() + "\n"
    }

    private fun writeLine(line: String) {
        try {
            logFile.appendText(line)
            trimLogIfNeeded()
            val j = JSONObject(line.trim())
            Log.d("ZaloPilot", "[${j.optString("tag")}][${j.optString("result")}] ${j.optString("target")} pkg=${j.optString("pkg")}")
        } catch (e: Exception) {
            Log.e("ZaloPilot", "Logger.writeLine failed", e)
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
            Log.e("ZaloPilot", "readLogs failed — ${e.message}", e)
            emptyList()
        }
    }

    private fun parseEntry(line: String): LogEntry? {
        return try {
            val j = JSONObject(line)
            val tagStr = j.optString("tag").ifEmpty { mapLegacyActionToTagName(j.optString("action"), j.optString("result")) }
            val tag = runCatching { LogTag.valueOf(tagStr) }.getOrDefault(LogTag.STATE)
            LogEntry(
                timestamp = j.optString("ts"),
                tag = tag,
                target = j.optString("target"),
                result = j.optString("result"),
                foregroundPkg = j.optString("pkg"),
                durationMs = if (j.has("ms")) j.optLong("ms") else null,
                rawLine = line
            )
        } catch (e: Exception) {
            val m = Regex("^\\[(.*?)] \\[(.*?)] \\[(.*?)] \\[(.*)]\$").find(line.trim())
                ?: return null
            val (ts, action, result, target) = m.destructured
            LogEntry(
                timestamp = ts,
                tag = runCatching { LogTag.valueOf(mapLegacyActionToTagName(action, result)) }.getOrDefault(LogTag.STATE),
                target = target,
                result = result,
                foregroundPkg = "",
                durationMs = null,
                rawLine = line
            )
        }
    }

    private fun mapLegacyActionToTagName(action: String, result: String): String = when {
        result.startsWith("ERROR", ignoreCase = true) -> LogTag.ERROR.name
        action.equals("EVENT", ignoreCase = true) -> LogTag.ERROR.name
        action.equals("POLL", ignoreCase = true) -> LogTag.POLL.name
        action.equals("EVENT_HINT", ignoreCase = true) -> LogTag.EVENT_HINT.name
        action.equals("SCROLL", ignoreCase = true) -> LogTag.SCROLL.name
        action.equals("CLICK", ignoreCase = true) || action.equals("TAP", ignoreCase = true) ||
            action.equals("LIKE", ignoreCase = true) || action.equals("LIKE_TRY", ignoreCase = true) -> LogTag.CLICK.name
        action.equals("LIKE_SKIP", ignoreCase = true) -> LogTag.STATE.name
        action.equals("FINDER", ignoreCase = true) && result.equals("NODES_FOUND", ignoreCase = true) -> LogTag.FOUND.name
        action.equals("SCANNER", ignoreCase = true) || action.equals("SCAN", ignoreCase = true) ||
            action.equals("DEBUG_DUMP", ignoreCase = true) || action.equals("FINDER", ignoreCase = true) ||
            action.equals("UI_TREE", ignoreCase = true) -> LogTag.SCAN.name
        else -> LogTag.STATE.name
    }

    private fun trimLogIfNeeded() {
        if (logFile.length() > 5 * 1024 * 1024) {
            val lines = logFile.readLines().filter { it.isNotBlank() }
            logFile.writeText(lines.takeLast(1000).joinToString("\n") + "\n")
        }
    }

    fun saveUiTree(nodes: List<UiNodeEntry>) {
        scope.launch {
            val t0 = SystemClock.elapsedRealtime()
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
                val elapsed = SystemClock.elapsedRealtime() - t0
                log(LogTag.SCAN, "uitree nodes=${nodes.size}", "SAVED", durationMs = elapsed)
            } catch (e: Exception) {
                logError("Logger.saveUiTree", e)
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
                    depth = j.optInt("depth"),
                    text = j.optString("text"),
                    resourceId = j.optString("id"),
                    className = j.optString("class"),
                    clickable = j.optBoolean("clickable"),
                    checked = j.optBoolean("checked")
                )
            }
            UiTreeResult(
                scannedAt = root.optString("ts"),
                count = root.optInt("count"),
                nodes = nodes
            )
        } catch (e: Exception) {
            Log.e("ZaloPilot", "readUiTree failed", e)
            appendLineSync(
                buildJson(
                    LogTag.ERROR,
                    "readUiTree",
                    e.message ?: e.javaClass.simpleName,
                    lastForegroundPackage,
                    null
                )
            )
            null
        }
    }
}

data class LogEntry(
    val timestamp: String,
    val tag: LogTag,
    val target: String,
    val result: String,
    val foregroundPkg: String,
    val durationMs: Long?,
    /** Dòng JSON gốc (copy log / debug). */
    val rawLine: String = ""
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
