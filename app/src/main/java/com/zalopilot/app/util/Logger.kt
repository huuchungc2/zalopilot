package com.zalopilot.app.util

import android.content.Context
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
        val dir = File(context.getExternalFilesDir(null), "ZaloPilot")
        dir.mkdirs()
        File(dir, "log.txt")
    }

    fun log(action: String, target: String = "", result: String = "") {
        scope.launch {
            try {
                val timestamp = dateFormat.format(Date())
                val line = "[$timestamp] [$action] [$result] $target\n"
                logFile.appendText(line)
                trimLogIfNeeded()
            } catch (e: Exception) {
                // Không crash app nếu log lỗi
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
            val parts = line.split("] [")
            val timestamp = parts[0].removePrefix("[")
            val action = parts[1]
            val result = parts[2]
            val target = parts[3].removeSuffix("]").trim()
            LogEntry(timestamp, action, result, target)
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
