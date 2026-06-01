package com.laragon.android.util

import android.content.Context
import com.laragon.android.util.ServerConfig.LOG_DIR
import com.laragon.android.util.ServerConfig.MAX_LOG_FILES
import com.laragon.android.util.ServerConfig.MAX_LOG_SIZE_BYTES
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rotating log system for Laragon Android.
 * Logs are written to files in the app's internal storage and rotated
 * when they exceed MAX_LOG_SIZE_BYTES.
 *
 * log() is NOT suspend — it launches internally on IO dispatcher.
 * readLastLines() / readFullLog() ARE suspend for use in coroutines.
 */
class LogRotator(private val context: Context) {

    private val logDir = File(context.filesDir, LOG_DIR)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        if (!logDir.exists()) logDir.mkdirs()
    }

    /**
     * Append a log entry to the specified log file.
     * Non-suspend: safe to call from anywhere.
     * Automatically rotates if the file exceeds the size limit.
     */
    fun log(logFile: String, message: String, level: LogLevel = LogLevel.INFO) {
        scope.launch {
            try {
                val file = File(logDir, logFile)
                if (file.parentFile != null && !file.parentFile!!.exists()) {
                    file.parentFile!!.mkdirs()
                }

                // Rotate if needed
                if (file.exists() && file.length() > MAX_LOG_SIZE_BYTES) {
                    rotate(file)
                }

                val timestamp = dateFormat.format(Date())
                val entry = "$timestamp [$level] $message\n"

                FileWriter(file, true).use { writer ->
                    writer.write(entry)
                    writer.flush()
                }
            } catch (_: Exception) {
                // Silently fail - logging should not crash the app
            }
        }
    }

    /**
     * Read the last N lines from a log file.
     */
    suspend fun readLastLines(logFile: String, lineCount: Int = 100): List<String> {
        return withContext(Dispatchers.IO) {
            try {
                val file = File(logDir, logFile)
                if (!file.exists()) return@withContext emptyList()

                val lines = file.readLines()
                if (lines.size <= lineCount) lines else lines.takeLast(lineCount)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /**
     * Get the full content of a log file as a string.
     */
    suspend fun readFullLog(logFile: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val file = File(logDir, logFile)
                if (!file.exists()) "" else file.readText()
            } catch (_: Exception) {
                ""
            }
        }
    }

    /**
     * Rotate log files: .log -> .log.1, .log.1 -> .log.2, etc.
     * Oldest files beyond MAX_LOG_FILES are deleted.
     */
    private fun rotate(file: File) {
        try {
            // Delete the oldest rotation if it exists
            val oldest = File("${file.absolutePath}.$MAX_LOG_FILES")
            if (oldest.exists()) oldest.delete()

            // Shift existing rotations
            for (i in MAX_LOG_FILES downTo 2) {
                val src = File("${file.absolutePath}.${i - 1}")
                val dst = File("${file.absolutePath}.$i")
                if (src.exists()) src.renameTo(dst)
            }

            // Move current log to .1
            file.renameTo(File("${file.absolutePath}.1"))
        } catch (_: Exception) {
            // Rotation failure should not crash
        }
    }

    enum class LogLevel {
        DEBUG, INFO, WARN, ERROR
    }
}
