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

    private enum class LogChannel { SLIM, VERBOSE, ERROR }

    private data class ChannelState(
        val lines: MutableList<String>,
        val lock: Any,
        val maxLines: Int,
        val clearOnNewSession: Boolean,
        val trimFileWhenExceeded: Boolean
    )

    private val slimState = ChannelState(
        lines = Collections.synchronizedList(ArrayList(256)),
        lock = Any(),
        maxLines = 500,
        clearOnNewSession = true,
        trimFileWhenExceeded = true
    )
    private val verboseState = ChannelState(
        lines = Collections.synchronizedList(ArrayList(512)),
        lock = Any(),
        maxLines = 2_000,
        clearOnNewSession = true,
        trimFileWhenExceeded = true
    )
    private val errorState = ChannelState(
        lines = Collections.synchronizedList(ArrayList(128)),
        lock = Any(),
        maxLines = 200,
        // "Không tự xoá" = không clear theo session; chỉ clear khi bấm nút riêng.
        clearOnNewSession = false,
        // Vẫn giữ cap 200 dòng như yêu cầu.
        trimFileWhenExceeded = true
    )

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
    val logVerboseFile: File by lazy { File(dir, "log_verbose.json") }
    val logSlimFile: File by lazy { File(dir, "log_slim.json") }
    val logErrorFile: File by lazy { File(dir, "log_error.json") }
    val uiTreeFile: File by lazy { File(dir, "uitree.json") }

    private val prefs by lazy { context.getSharedPreferences("logger_prefs", Context.MODE_PRIVATE) }
    fun isVerboseLogEnabled(): Boolean = prefs.getBoolean("verbose_log", false)
    fun setVerboseLogEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("verbose_log", enabled).apply()
    }

    /**
     * Xóa buffer phiên, xoá file log/UI tạm — dùng nút Clear Logs / trước khi export sạch.
     */
    fun clearDebugArtifacts() {
        clearSlimLogs()
        clearVerboseLogs()
        sessionLoggingActive = false
        scope.launch {
            runCatching {
                if (logSlimFile.exists()) logSlimFile.delete()
                if (logVerboseFile.exists()) logVerboseFile.delete()
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
        clearChannelStateForNewSession(slimState, logSlimFile)
        clearChannelStateForNewSession(verboseState, logVerboseFile)
        sessionLoggingActive = true
        runCatching {
            logSlimFile.parentFile?.mkdirs()
            logSlimFile.writeText("")
            logVerboseFile.writeText("")
        }.onFailure { e ->
            Log.e("ZaloPilot", "beginAutomationSession truncate", e)
        }
    }

    fun getSlimLogText(): String = getChannelText(slimState)
    fun getVerboseLogText(): String = getChannelText(verboseState)
    fun getErrorLogText(): String = getChannelText(errorState)

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
        appendLineIfActive(line, tag, result)
        scope.launch { writeLine(line, tag, result) }
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
        // ERROR log: luôn đẩy vào verbose (nếu bật) + error (vì là lỗi).
        appendLineIfActive(line, LogTag.ERROR, "ERROR")
        scope.launch { writeLine(line, LogTag.ERROR, "ERROR") }
    }

    private fun appendLineIfActive(line: String, tag: LogTag, result: String) {
        if (!sessionLoggingActive) return
        val channels = channelsFor(tag, result)
        for (c in channels) {
            val (st, _) = stateAndFile(c)
            synchronized(st.lock) {
                st.lines.add(line)
                trimInMemoryIfNeeded(st)
            }
        }
    }

    private fun trimInMemoryIfNeeded(st: ChannelState) {
        while (st.lines.size > st.maxLines) {
            st.lines.removeAt(0)
        }
    }

    /** Ghi đồng bộ khi coroutine logger không dùng được (ví dụ lỗi đọc file). */
    private fun appendLineSync(line: String) {
        // Fallback: sync append vào verbose file để không mất trace.
        try {
            logVerboseFile.appendText(line)
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

    private fun writeLine(line: String, tag: LogTag, result: String) {
        try {
            val channels = channelsFor(tag, result)
            for (c in channels) {
                val (st, file) = stateAndFile(c)
                // Verbose file chỉ ghi khi user bật toggle.
                if (c == LogChannel.VERBOSE && !isVerboseLogEnabled()) continue
                file.appendText(line)
                if (st.trimFileWhenExceeded) trimFileToLastNLines(file, st.maxLines)
            }
            val j = JSONObject(line.trim())
            Log.d("ZaloPilot", "[${j.optString("tag")}][${j.optString("result")}] ${j.optString("target")} pkg=${j.optString("pkg")}")
        } catch (e: Exception) {
            Log.e("ZaloPilot", "Logger.writeLine failed", e)
        }
    }

    fun readSlimLogs(limit: Int = 200): List<LogEntry> = readChannelLogs(slimState, limit)
    fun readVerboseLogs(limit: Int = 200): List<LogEntry> = readChannelLogs(verboseState, limit)
    fun readErrorLogs(limit: Int = 200): List<LogEntry> = readChannelLogs(errorState, limit)

    private fun readChannelLogs(state: ChannelState, limit: Int): List<LogEntry> {
        return try {
            val lines = synchronized(state.lock) { state.lines.toList() }
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

    private fun trimFileToLastNLines(file: File, keep: Int) {
        // Nhỏ gọn: file log luôn là JSON lines, nên trim theo số dòng.
        runCatching {
            if (!file.exists()) return
            val lines = file.readLines().filter { it.isNotBlank() }
            if (lines.size <= keep) return
            file.writeText(lines.takeLast(keep).joinToString("\n") + "\n")
        }
    }

    fun clearSlimLogs() = clearChannel(slimState, logSlimFile)
    fun clearVerboseLogs() = clearChannel(verboseState, logVerboseFile)
    fun clearErrorLogs() = clearChannel(errorState, logErrorFile)

    private fun clearChannel(st: ChannelState, file: File) {
        synchronized(st.lock) { st.lines.clear() }
        scope.launch {
            runCatching { if (file.exists()) file.delete() }
                .onFailure { e -> Log.e("ZaloPilot", "clearChannel ${file.name}", e) }
        }
    }

    private fun clearChannelStateForNewSession(st: ChannelState, file: File) {
        if (!st.clearOnNewSession) return
        synchronized(st.lock) { st.lines.clear() }
        runCatching { file.parentFile?.mkdirs(); file.writeText("") }
            .onFailure { e -> Log.e("ZaloPilot", "clearChannelStateForNewSession ${file.name}", e) }
    }

    private fun getChannelText(st: ChannelState): String =
        synchronized(st.lock) { st.lines.toList() }.joinToString("")

    private fun stateAndFile(c: LogChannel): Pair<ChannelState, File> = when (c) {
        LogChannel.SLIM -> slimState to logSlimFile
        LogChannel.VERBOSE -> verboseState to logVerboseFile
        LogChannel.ERROR -> errorState to logErrorFile
    }

    private fun channelsFor(tag: LogTag, result: String): Set<LogChannel> {
        val res = result.trim()

        val slimResults = setOf(
            "CLICK_CANDIDATE",
            "CLICK_UNCONFIRMED",
            "SUCCESS",
            "ID_SAVED",
            "EMPTY_AFTER_RETRY",
            "ALL_SKIPPED",
            "NO_BUTTONS",
            "STOPPED",
            "PAUSED_ZALO_CLOSED",
            "BEFORE_CLICK"
        )
        val errorResults = setOf(
            "ACTION_CLICK_FAIL",
            "ACTION_CLICK_PRIMARY_FAIL",
            "GESTURE_FALLBACK_FAIL",
            "IMAGE_VIEWER_BACK_SKIP_POST",
            "CLICK_UNCONFIRMED",
            "STOPPED",
            "PAUSED_ZALO_CLOSED",
            "EMPTY_AFTER_RETRY",
            "NO_BUTTONS"
        )

        val isActionClick = res.startsWith("ACTION_CLICK_")
        val isGesture = res.startsWith("GESTURE_")
        val isImageViewer = res.startsWith("IMAGE_VIEWER_")

        val slim =
            slimResults.contains(res) || isActionClick || isGesture || isImageViewer

        val err =
            errorResults.contains(res)

        val out = linkedSetOf<LogChannel>()
        if (slim) out.add(LogChannel.SLIM)
        if (err || tag == LogTag.ERROR) out.add(LogChannel.ERROR)
        // Verbose: ghi tất cả như cũ, nhưng chỉ khi user bật.
        out.add(LogChannel.VERBOSE)
        return out
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
