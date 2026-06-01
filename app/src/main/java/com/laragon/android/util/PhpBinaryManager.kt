package com.laragon.android.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.laragon.android.util.ServerConfig.PHP_ASSET_PATH
import com.laragon.android.util.ServerConfig.PHP_CGI_BINARY
import com.laragon.android.util.ServerConfig.PHP_DIR
import com.laragon.android.util.ServerConfig.PREFS_NAME
import com.laragon.android.util.ServerConfig.PREF_PHP_INITIALIZED
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Manages the extraction and setup of the PHP binary from assets
 * to the app's internal storage.
 */
class PhpBinaryManager(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val phpDir = File(context.filesDir, PHP_DIR)
    private val phpBinary = File(phpDir, PHP_CGI_BINARY)

    /**
     * Check if the PHP binary has been extracted and is ready.
     */
    fun isInitialized(): Boolean {
        return prefs.getBoolean(PREF_PHP_INITIALIZED, false) && phpBinary.exists() && phpBinary.canExecute()
    }

    /**
     * Get the full path to the php-cgi binary.
     */
    fun getBinaryPath(): String = phpBinary.absolutePath

    /**
     * Extract the PHP binary from assets to internal storage.
     * Must be called on first run or when binary is missing.
     * Returns true if extraction was successful.
     */
    suspend fun extractBinary(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                if (!phpDir.exists()) phpDir.mkdirs()

                // Try to copy from assets
                val assetInputStream = context.assets.open(PHP_ASSET_PATH)
                val outputStream = FileOutputStream(phpBinary)

                assetInputStream.use { input ->
                    outputStream.use { output ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                        }
                    }
                }

                // Make executable
                phpBinary.setExecutable(true, false)
                phpBinary.setReadable(true, false)

                // Verify
                if (!phpBinary.canExecute()) {
                    // Fallback: try chmod via Runtime
                    try {
                        Runtime.getRuntime().exec(arrayOf("chmod", "755", phpBinary.absolutePath)).waitFor()
                    } catch (_: Exception) {}
                }

                val success = phpBinary.exists() && phpBinary.canExecute()
                if (success) {
                    prefs.edit().putBoolean(PREF_PHP_INITIALIZED, true).apply()
                }
                success
            } catch (e: Exception) {
                // Asset not found - binary must be provided separately
                false
            }
        }
    }

    /**
     * Test if the PHP binary can execute by running `php-cgi -v`.
     * Returns the version string or null on failure.
     */
    suspend fun testBinary(): String? {
        return withContext(Dispatchers.IO) {
            try {
                if (!phpBinary.exists()) return@withContext null

                val process = ProcessBuilder(phpBinary.absolutePath, "-v")
                    .redirectErrorStream(true)
                    .start()

                val output = process.inputStream.bufferedReader().readText()
                process.waitFor()

                if (process.exitValue() == 0) output.trim() else null
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * Delete the extracted PHP binary (for cleanup or re-extraction).
     */
    suspend fun deleteBinary(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val deleted = phpBinary.delete()
                prefs.edit().putBoolean(PREF_PHP_INITIALIZED, false).apply()
                deleted
            } catch (_: Exception) {
                false
            }
        }
    }
}
