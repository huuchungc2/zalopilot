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
import java.util.Collections
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

    /** Log dòng JSON của phiên automation hiện tại (từ [beginAutomationSession] tới lần bắt đầu kế tiếp hoặc [clearDebugArtifacts]). */
    private val sessionLogLines = Collections.synchronizedList(ArrayList<String>(256))
    private val sessionLock = Any()

    @Volatile
    var sessionLoggingActive: Boolean = false
        private set

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
     * Xóa buffer phiên, xoá file log/UI tạm — dùng nút Clear Logs / trước khi export sạch.
     */
    fun clearDebugArtifacts() {
        synchronized(sessionLock) {
            sessionLogLines.clear()
            sessionLoggingActive = false
        }
        scope.launch {
            runCatching {
                if (logFile.exists()) logFile.delete()
                if (uiTreeFile.exists()) uiTreeFile.delete()
            }.onFailure { e -> Log.e("ZaloPilot", "clearDebugArtifacts", e) }
        }
    }

    /**
     * Bắt đầu phiên automation mới: xóa log trong RAM, truncate file log trên disk,
     * các dòng log tiếp theo thuộc phiên này (UI + Lưu log chỉ xem phiên).
     * Gọi từ [Dispatchers.IO] để không chặn main thread.
     */
    fun beginAutomationSession() {
        synchronized(sessionLock) {
            sessionLogLines.clear()
            sessionLoggingActive = true
        }
        runCatching {
            logFile.parentFile?.mkdirs()
            logFile.writeText("")
        }.onFailure { e ->
            Log.e("ZaloPilot", "beginAutomationSession truncate", e)
        }
    }

    /** Nội dịnh phiên để export / copy (JSON lines). */
    fun getSessionLogText(): String {
        val lines = synchronized(sessionLock) {
            sessionLogLines.toList()
        }
        return lines.joinToString("")
    }

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
        appendSessionLineIfActive(line)
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
        appendSessionLineIfActive(line)
        scope.launch {
            writeLine(line)
        }
    }

    private fun appendSessionLineIfActive(line: String) {
        synchronized(sessionLock) {
            if (!sessionLoggingActive) return
            sessionLogLines.add(line)
            trimSessionIfNeeded()
        }
    }

    private fun trimSessionIfNeeded() {
        val max = 8_000
        val keep = 4_000
        while (sessionLogLines.size > max) {
            val drop = sessionLogLines.size - keep
            repeat(drop.coerceAtLeast(1)) {
                if (sessionLogLines.isNotEmpty()) sessionLogLines.removeAt(0)
            }
        }
    }

    /** Ghi đồng bộ khi coroutine logger không dùng được (ví dụ lỗi đọc file). */
    private fun appendLineSync(line: String) {
        appendSessionLineIfActive(line)
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
            val lines = synchronized(sessionLock) {
                sessionLogLines.toList()
            }
            if (lines.isEmpty()) return emptyList()
            lines
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
