package com.laragon.android.php

import android.content.Context
import com.laragon.android.util.LogRotator
import com.laragon.android.util.ServerConfig
import com.laragon.android.util.ServerConfig.PHP_CGI_BINARY
import com.laragon.android.util.ServerConfig.PHP_DIR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Handles execution of PHP scripts via php-cgi (CGI mode).
 * Launches a new process per request (acceptable for local development).
 * All execution happens on Dispatchers.IO to avoid blocking the main thread.
 */
class PhpCgiHandler(private val context: Context) {

    private val logRotator = LogRotator(context)
    private val phpBinary = File(context.filesDir, "$PHP_DIR/$PHP_CGI_BINARY")

    /**
     * Execute a PHP file via php-cgi and return the full response
     * (headers + body) as a string.
     *
     * @param scriptPath Absolute path to the .php file on the local filesystem
     * @param envVars Map of CGI environment variables
     * @return PhpResponse with headers and body separated, or null on error
     */
    suspend fun execute(
        scriptPath: String,
        envVars: Map<String, String>
    ): PhpResponse? = withContext(Dispatchers.IO) {
        try {
            if (!phpBinary.exists() || !phpBinary.canExecute()) {
                logRotator.log(
                    ServerConfig.ERROR_LOG,
                    "PHP binary not found or not executable at: ${phpBinary.absolutePath}",
                    LogRotator.LogLevel.ERROR
                )
                return@withContext PhpResponse(
                    statusCode = 500,
                    headers = mapOf("Content-Type" to "text/html"),
                    body = buildErrorPage(
                        "PHP Binary Not Found",
                        "The php-cgi binary was not found at <code>${phpBinary.absolutePath}</code>. " +
                                "Please ensure the PHP binary was extracted correctly on first launch."
                    )
                )
            }

            val scriptFile = File(scriptPath)
            if (!scriptFile.exists()) {
                return@withContext PhpResponse(
                    statusCode = 404,
                    headers = mapOf("Content-Type" to "text/html"),
                    body = buildErrorPage(
                        "File Not Found",
                        "The requested PHP file was not found: <code>$scriptPath</code>"
                    )
                )
            }

            // Build the process environment
            val processEnv = buildEnvironment(scriptPath, envVars)

            // Launch php-cgi process
            val processBuilder = ProcessBuilder(phpBinary.absolutePath)
                .redirectErrorStream(false)

            // Set environment variables
            processBuilder.environment().putAll(processEnv)

            val process = processBuilder.start()

            // Write request body if POST/PUT
            val contentLength = envVars["CONTENT_LENGTH"]?.toLongOrNull() ?: 0L
            if (contentLength > 0 && envVars["REQUEST_METHOD"] in listOf("POST", "PUT", "PATCH")) {
                val bodyData = envVars["REQUEST_BODY"]
                if (bodyData != null) {
                    process.outputStream.write(bodyData.toByteArray())
                    process.outputStream.flush()
                }
            }
            process.outputStream.close()

            // Read stdout (CGI response: headers + body)
            val stdout = process.inputStream.bufferedReader().readText()

            // Read stderr (PHP errors/warnings)
            val stderr = process.errorStream.bufferedReader().readText()

            process.waitFor()

            // Log stderr if present
            if (stderr.isNotBlank()) {
                logRotator.log(ServerConfig.PHP_LOG, stderr.trim(), LogRotator.LogLevel.WARN)
            }

            if (process.exitValue() != 0) {
                logRotator.log(
                    ServerConfig.ERROR_LOG,
                    "PHP process exited with code ${process.exitValue()}: $stderr",
                    LogRotator.LogLevel.ERROR
                )
                return@withContext PhpResponse(
                    statusCode = 500,
                    headers = mapOf("Content-Type" to "text/html"),
                    body = buildErrorPage(
                        "PHP Execution Error",
                        "PHP process exited with code ${process.exitValue()}.<br>" +
                                "<pre>${stderr.ifBlank { "No error output captured." }}</pre>"
                    )
                )
            }

            // Parse CGI response (headers separated from body by blank line)
            parseCgiResponse(stdout)
        } catch (e: Exception) {
            logRotator.log(
                ServerConfig.ERROR_LOG,
                "Exception executing PHP: ${e.message}",
                LogRotator.LogLevel.ERROR
            )
            PhpResponse(
                statusCode = 500,
                headers = mapOf("Content-Type" to "text/html"),
                body = buildErrorPage(
                    "PHP Execution Failed",
                    "An exception occurred: <code>${e.message}</code>"
                )
            )
        }
    }

    /**
     * Build the complete CGI environment variables map.
     */
    private fun buildEnvironment(
        scriptPath: String,
        extraEnv: Map<String, String>
    ): Map<String, String> {
        val env = mutableMapOf(
            "SCRIPT_FILENAME" to scriptPath,
            "SCRIPT_NAME" to (extraEnv["SCRIPT_NAME"] ?: "/index.php"),
            "REQUEST_URI" to (extraEnv["REQUEST_URI"] ?: "/"),
            "QUERY_STRING" to (extraEnv["QUERY_STRING"] ?: ""),
            "REQUEST_METHOD" to (extraEnv["REQUEST_METHOD"] ?: "GET"),
            "SERVER_PROTOCOL" to "HTTP/1.1",
            "SERVER_SOFTWARE" to "LaragonAndroid/1.0",
            "SERVER_NAME" to "localhost",
            "SERVER_PORT" to (extraEnv["SERVER_PORT"] ?: "8080"),
            "GATEWAY_INTERFACE" to "CGI/1.1",
            "REDIRECT_STATUS" to "200",
            "CONTENT_TYPE" to (extraEnv["CONTENT_TYPE"] ?: ""),
            "CONTENT_LENGTH" to (extraEnv["CONTENT_LENGTH"] ?: "0"),
            "DOCUMENT_ROOT" to (extraEnv["DOCUMENT_ROOT"] ?: ""),
            "REMOTE_ADDR" to "127.0.0.1",
            "REMOTE_PORT" to "12345",
            "HTTP_HOST" to "localhost:8080",
            "HTTP_USER_AGENT" to "LaragonAndroid-WebView",
            "HTTP_ACCEPT" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "HTTP_ACCEPT_LANGUAGE" to "en-US,en;q=0.9",
            "PHP_SELF" to (extraEnv["SCRIPT_NAME"] ?: "/index.php")
        )

        // Add any extra HTTP_* headers
        extraEnv.forEach { (key, value) ->
            if (key.startsWith("HTTP_") || key in listOf(
                    "CONTENT_TYPE", "CONTENT_LENGTH", "REQUEST_BODY"
                )) {
                env[key] = value
            }
        }

        // Set TEMP/TMP for PHP session/upload temp files
        val tmpDir = File(context.cacheDir, "php_tmp")
        if (!tmpDir.exists()) tmpDir.mkdirs()
        env["TEMP"] = tmpDir.absolutePath
        env["TMP"] = tmpDir.absolutePath
        env["TMPDIR"] = tmpDir.absolutePath

        // PHP ini settings via environment
        env["PHPRC"] = context.filesDir.absolutePath + "/php"

        return env
    }

    /**
     * Parse a CGI response into headers and body.
     * CGI format: headers separated from body by \r\n\r\n or \n\n
     */
    private fun parseCgiResponse(raw: String): PhpResponse {
        val headerEndIndex = raw.indexOf("\r\n\r\n")
            .takeIf { it >= 0 }
            ?: raw.indexOf("\n\n").takeIf { it >= 0 }
            ?: return PhpResponse(
                statusCode = 200,
                headers = mapOf("Content-Type" to "text/html"),
                body = raw
            )

        val headerSection = raw.substring(0, headerEndIndex)
        val bodyStart = if (raw[headerEndIndex] == '\r') headerEndIndex + 4 else headerEndIndex + 2
        val body = if (bodyStart < raw.length) raw.substring(bodyStart) else ""

        val headers = mutableMapOf<String, String>()
        var statusCode = 200

        headerSection.split("\n").forEach { line ->
            val cleanLine = line.trimEnd('\r')
            if (cleanLine.contains(":")) {
                val colonIndex = cleanLine.indexOf(':')
                val key = cleanLine.substring(0, colonIndex).trim()
                val value = cleanLine.substring(colonIndex + 1).trim()
                headers[key] = value
            } else if (cleanLine.startsWith("Status:", ignoreCase = true)) {
                // CGI Status header: "Status: 404 Not Found"
                val statusPart = cleanLine.substringAfter("Status:").trim()
                statusCode = statusPart.substringBefore(" ").toIntOrNull() ?: 200
            }
        }

        // If Content-Type is not set by PHP, default to text/html
        if (!headers.containsKey("Content-Type")) {
            headers["Content-Type"] = "text/html"
        }

        return PhpResponse(statusCode = statusCode, headers = headers, body = body)
    }

    /**
     * Build a styled HTML error page.
     */
    private fun buildErrorPage(title: String, message: String): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>$title</title>
                <style>
                    body { font-family: -apple-system, BlinkMacSystemFont, sans-serif; padding: 40px 20px; background: #f5f5f5; color: #333; }
                    .container { max-width: 600px; margin: 0 auto; background: #fff; padding: 30px; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
                    h1 { color: #e74c3c; font-size: 22px; margin-top: 0; }
                    p { line-height: 1.6; }
                    code { background: #f0f0f0; padding: 2px 6px; border-radius: 3px; font-size: 14px; }
                    pre { background: #2d2d2d; color: #f8f8f2; padding: 15px; border-radius: 5px; overflow-x: auto; font-size: 13px; }
                    .footer { margin-top: 20px; color: #999; font-size: 12px; text-align: center; }
                </style>
            </head>
            <body>
                <div class="container">
                    <h1>$title</h1>
                    <p>$message</p>
                    <div class="footer">Laragon Android - Local Development Server</div>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    data class PhpResponse(
        val statusCode: Int,
        val headers: Map<String, String>,
        val body: String
    )
}
