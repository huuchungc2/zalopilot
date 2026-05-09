package com.zalopilot.app.util

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
    private val logFile: File by lazy {
        // Internal storage để tránh lỗi scoped storage / external không sẵn sàng.
        val dir = File(context.filesDir, "ZaloPilot")
        dir.mkdirs()
        File(dir, "log.txt")
    }

    fun log(action: String, target: String = "", result: String = "") {
        scope.launch {
            try {
                val timestamp = dateFormat.format(Date())
                // Wrap target in [] to make parsing reliable.
                val line = "[$timestamp] [$action] [$result] [$target]\n"
                logFile.appendText(line)
                trimLogIfNeeded()
            } catch (e: Exception) {
                // Không crash app nếu log lỗi, nhưng phải có dấu vết để debug.
                Log.e("ZaloPilot", "Logger failed: action=$action result=$result target=$target", e)
            }
        }
    }

    fun readLogs(): List<LogEntry> {
        return try {
            if (!logFile.exists()) return emptyList()
            logFile.readLines()
                .takeLast(100)
                .reversed()
                .mapNotNull { parseLine(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseLine(line: String): LogEntry? {
        return try {
            // Support both formats:
            // - New: [ts] [action] [result] [target]
            // - Old: [ts] [action] [result] target
            val newFmt = Regex("^\\[(.*?)] \\[(.*?)] \\[(.*?)] \\[(.*)]\\s*$")
            val oldFmt = Regex("^\\[(.*?)] \\[(.*?)] \\[(.*?)]\\s+(.*)\\s*$")

            val m1 = newFmt.find(line)
            if (m1 != null) {
                val (ts, action, result, target) = m1.destructured
                return LogEntry(ts, action, result, target)
            }

            val m2 = oldFmt.find(line) ?: return null
            val (ts, action, result, target) = m2.destructured
            LogEntry(ts, action, result, target)
        } catch (e: Exception) {
            null
        }
    }

    private fun trimLogIfNeeded() {
        if (logFile.length() > 5 * 1024 * 1024) {
            val lines = logFile.readLines()
            logFile.writeText(lines.takeLast(500).joinToString("\n"))
        }
    }
}

data class LogEntry(
    val timestamp: String,
    val action: String,
    val result: String,
    val target: String
)
