package com.laragon.android.util

/**
 * Central configuration for the Laragon Android server.
 */
object ServerConfig {
    const val DEFAULT_PORT = 8080
    const val NOTIFICATION_CHANNEL_ID = "laragon_server"
    const val NOTIFICATION_CHANNEL_NAME = "Servidor Laragon"
    const val NOTIFICATION_ID = 1001

    // PHP binary paths
    const val PHP_DIR = "php"
    const val PHP_CGI_BINARY = "php-cgi"
    const val PHP_ASSET_PATH = "bin/php/arm64/php-cgi"

    // Log paths
    const val LOG_DIR = "logs"
    const val SERVER_LOG = "server.log"
    const val PHP_LOG = "php.log"
    const val ERROR_LOG = "errors.log"

    // Log rotation
    const val MAX_LOG_SIZE_BYTES = 512L * 1024
    const val MAX_LOG_FILES = 3

    // Selection modes
    const val MODE_FOLDER = "folder"
    const val MODE_FILE = "file"
    const val MODE_PATH = "path"

    // Shared preferences keys
    const val PREFS_NAME = "laragon_prefs"
    const val PREF_TREE_URI = "project_tree_uri"
    const val PREF_FILE_URI = "pref_file_uri"
    const val PREF_FILE_NAME = "pref_file_name"
    const val PREF_SELECTION_MODE = "pref_selection_mode"
    const val PREF_DIRECT_PATH = "pref_direct_path"
    const val PREF_SERVER_PORT = "server_port"
    const val PREF_PHP_INITIALIZED = "php_initialized"

    // MIME type mapping
    val MIME_TYPES = mapOf(
        "html" to "text/html",
        "htm" to "text/html",
        "css" to "text/css",
        "js" to "application/javascript",
        "json" to "application/json",
        "xml" to "application/xml",
        "txt" to "text/plain",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "svg" to "image/svg+xml",
        "ico" to "image/x-icon",
        "webp" to "image/webp",
        "woff" to "font/woff",
        "woff2" to "font/woff2",
        "ttf" to "font/ttf",
        "otf" to "font/otf",
        "eot" to "application/vnd.ms-fontobject",
        "mp3" to "audio/mpeg",
        "mp4" to "video/mp4",
        "webm" to "video/webm",
        "pdf" to "application/pdf",
        "zip" to "application/zip",
        "php" to "application/x-httpd-php"
    )

    fun getMimeType(extension: String): String {
        return MIME_TYPES[extension.lowercase()] ?: "application/octet-stream"
    }
}
