package com.laragon.android.server

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.laragon.android.php.PhpCgiHandler
import com.laragon.android.util.LogRotator
import com.laragon.android.util.ServerConfig
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Ktor HTTP server supporting three modes:
 * - MODE_PATH: direct filesystem path (requires MANAGE_EXTERNAL_STORAGE on Android 11+)
 * - MODE_FOLDER: SAF tree URI
 * - MODE_FILE: single file (SAF URI or direct path)
 */
class LaragonServer(
    private val context: Context,
    private val port: Int = ServerConfig.DEFAULT_PORT
) {
    private var serverEngine: ApplicationEngine? = null
    private val logRotator = LogRotator(context)
    private val phpHandler = PhpCgiHandler(context)
    private var _isRunning = false
    val isRunning: Boolean get() = _isRunning

    private var currentMode: String = ServerConfig.MODE_PATH
    private var projectTreeUri: String? = null
    private var singleFileUri: String? = null
    private var singleFileName: String? = null
    private var directPath: String? = null

    suspend fun start(projectTreeUri: String) {
        currentMode = ServerConfig.MODE_FOLDER
        this.projectTreeUri = projectTreeUri
        this.directPath = null
        this.singleFileUri = null
        startServer()
    }

    suspend fun startWithPath(path: String) {
        currentMode = ServerConfig.MODE_PATH
        this.directPath = path
        this.projectTreeUri = null
        this.singleFileUri = null
        startServer()
    }

    suspend fun startWithFile(fileUri: String, fileName: String) {
        currentMode = ServerConfig.MODE_FILE
        this.singleFileUri = fileUri
        this.singleFileName = fileName
        this.projectTreeUri = null
        this.directPath = null
        startServer()
    }

    suspend fun startWithDirectFile(path: String, fileName: String) {
        currentMode = ServerConfig.MODE_FILE
        this.directPath = path
        this.singleFileName = fileName
        this.singleFileUri = null
        this.projectTreeUri = null
        startServer()
    }

    private suspend fun startServer() {
        if (_isRunning) return

        logRotator.log(ServerConfig.SERVER_LOG, "Starting server on port $port (mode=$currentMode)")

        try {
            val engine = embeddedServer(CIO, port = port) {
                install(io.ktor.server.plugins.statuspages.StatusPages) {
                    exception<Throwable> { call, cause ->
                        call.respondText(
                            buildHtmlError("500 Internal Server Error", cause.message ?: "Unknown"),
                            ContentType.Text.Html,
                            HttpStatusCode.InternalServerError
                        )
                    }
                }
                routing {
                    get("/{path...}") { handleRequest(call) }
                    get("/") { handleRequest(call) }
                    post("/{path...}") { handleRequest(call) }
                    post("/") { handleRequest(call) }
                }
            }
            engine.start(wait = false)
            serverEngine = engine
            _isRunning = true
            logRotator.log(ServerConfig.SERVER_LOG, "Server started on port $port")
        } catch (e: Exception) {
            logRotator.log(ServerConfig.SERVER_LOG, "Start failed: ${e.message}", LogRotator.LogLevel.ERROR)
            throw e
        }
    }

    fun stop() {
        if (!_isRunning) return
        try {
            serverEngine?.stop(1000, 2000)
            _isRunning = false
            logRotator.log(ServerConfig.SERVER_LOG, "Server stopped")
        } catch (e: Exception) {
            logRotator.log(ServerConfig.SERVER_LOG, "Stop error: ${e.message}", LogRotator.LogLevel.ERROR)
        }
    }

    private suspend fun handleRequest(call: ApplicationCall) {
        val requestPath = call.request.path()
        val queryString = call.request.queryParameters.formUrlEncode()
        val method = call.request.httpMethod.value

        logRotator.log(ServerConfig.SERVER_LOG, "$method $requestPath")

        try {
            when (currentMode) {
                ServerConfig.MODE_PATH -> handlePathModeRequest(call, requestPath, queryString, method)
                ServerConfig.MODE_FILE -> handleFileModeRequest(call, requestPath, queryString, method)
                ServerConfig.MODE_FOLDER -> handleFolderModeRequest(call, requestPath, queryString, method)
                else -> call.respondText(buildHtmlError("503", "No project selected"), ContentType.Text.Html, HttpStatusCode.ServiceUnavailable)
            }
        } catch (e: Exception) {
            logRotator.log(ServerConfig.ERROR_LOG, "Request error: ${e.message}", LogRotator.LogLevel.ERROR)
            call.respondText(buildHtmlError("500", e.message ?: "Unknown"), ContentType.Text.Html, HttpStatusCode.InternalServerError)
        }
    }

    // ── DIRECT PATH MODE (filesystem) ──

    private suspend fun handlePathModeRequest(call: ApplicationCall, requestPath: String, queryString: String, method: String) {
        val rootPath = directPath ?: run {
            call.respondText(buildHtmlError("503", "No path set"), ContentType.Text.Html, HttpStatusCode.ServiceUnavailable)
            return
        }
        val rootDir = File(rootPath)
        if (!rootDir.exists() || !rootDir.isDirectory) {
            call.respondText(buildHtmlError("500", "Path does not exist: $rootPath"), ContentType.Text.Html, HttpStatusCode.InternalServerError)
            return
        }

        var normalizedPath = requestPath.trimStart('/')
        if (normalizedPath.isBlank()) {
            normalizedPath = findIndexFileFs(rootDir) ?: "index.html"
        }

        // Security: prevent path traversal
        val targetFile = File(rootDir, normalizedPath).canonicalFile
        if (!targetFile.canonicalPath.startsWith(rootDir.canonicalPath)) {
            call.respondText(buildHtmlError("403", "Forbidden"), ContentType.Text.Html, HttpStatusCode.Forbidden)
            return
        }

        if (!targetFile.exists()) {
            call.respondText(buildHtmlError("404", "Not found: $requestPath"), ContentType.Text.Html, HttpStatusCode.NotFound)
            return
        }

        if (targetFile.isFile) {
            if (normalizedPath.endsWith(".php", ignoreCase = true)) {
                servePhpFromFs(call, targetFile, normalizedPath, queryString, method)
            } else {
                serveStaticFromFs(call, targetFile, normalizedPath)
            }
        } else if (targetFile.isDirectory) {
            // Try index file in subdirectory
            val indexFile = findIndexFileFs(targetFile)
            if (indexFile != null) {
                val subPath = "$normalizedPath/$indexFile"
                val indexTarget = File(targetFile, indexFile)
                if (normalizedPath.endsWith(".php", ignoreCase = true) || indexFile.endsWith(".php", ignoreCase = true)) {
                    servePhpFromFs(call, indexTarget, subPath, queryString, method)
                } else {
                    serveStaticFromFs(call, indexTarget, subPath)
                }
            } else {
                call.respondText(buildHtmlError("403", "Directory listing not allowed"), ContentType.Text.Html, HttpStatusCode.Forbidden)
            }
        }
    }

    private fun findIndexFileFs(dir: File): String? {
        listOf("index.php", "index.html", "index.htm").forEach {
            if (File(dir, it).exists()) return it
        }
        return null
    }

    private suspend fun serveStaticFromFs(call: ApplicationCall, file: File, path: String) {
        val ext = path.substringAfterLast('.', "").lowercase()
        val mime = ServerConfig.getMimeType(ext)
        val bytes = withContext(Dispatchers.IO) { file.readBytes() }
        call.respondBytes(bytes, ContentType.parse(mime))
    }

    private suspend fun servePhpFromFs(call: ApplicationCall, file: File, requestPath: String, queryString: String, method: String) {
        val documentRoot = directPath ?: context.cacheDir.absolutePath

        val requestBody = if (method in listOf("POST", "PUT", "PATCH")) {
            try { call.receiveText() } catch (_: Exception) { null }
        } else null

        val envVars = mapOf(
            "REQUEST_URI" to call.request.path(),
            "QUERY_STRING" to queryString,
            "REQUEST_METHOD" to method,
            "CONTENT_TYPE" to (call.request.headers[HttpHeaders.ContentType] ?: ""),
            "CONTENT_LENGTH" to (requestBody?.length?.toString() ?: "0"),
            "REQUEST_BODY" to (requestBody ?: ""),
            "SCRIPT_NAME" to "/$requestPath",
            "DOCUMENT_ROOT" to documentRoot,
            "SERVER_PORT" to port.toString(),
            "HTTP_COOKIE" to (call.request.headers[HttpHeaders.Cookie] ?: "")
        )

        val response = phpHandler.execute(file.absolutePath, envVars)

        if (response != null) {
            response.headers.forEach { (key, value) ->
                if (key.lowercase() !in listOf("status", "content-length")) {
                    try { call.response.header(key, value) } catch (_: Exception) {}
                }
            }
            call.respondText(response.body, ContentType.Text.Html, HttpStatusCode.fromValue(response.statusCode))
        } else {
            call.respondText(buildHtmlError("500", "PHP returned no response"), ContentType.Text.Html, HttpStatusCode.InternalServerError)
        }
    }

    // ── SINGLE FILE MODE ──

    private suspend fun handleFileModeRequest(call: ApplicationCall, requestPath: String, queryString: String, method: String) {
        val normalizedPath = requestPath.trimStart('/')

        // Direct path mode (filesystem)
        if (directPath != null) {
            val file = File(directPath!!)
            if (!file.exists()) {
                call.respondText(buildHtmlError("500", "File not found: $directPath"), ContentType.Text.Html, HttpStatusCode.InternalServerError)
                return
            }
            if (file.name.endsWith(".php", ignoreCase = true)) {
                servePhpFromFs(call, file, file.name, queryString, method)
            } else {
                serveStaticFromFs(call, file, file.name)
            }
            return
        }

        // SAF URI mode
        val uri = singleFileUri ?: run {
            call.respondText(buildHtmlError("503", "No file selected"), ContentType.Text.Html, HttpStatusCode.ServiceUnavailable)
            return
        }

        if (normalizedPath.isNotBlank() && normalizedPath != singleFileName) {
            call.respondText(buildHtmlError("404", "Single file mode"), ContentType.Text.Html, HttpStatusCode.NotFound)
            return
        }

        val fileUri = Uri.parse(uri)
        val fileName = singleFileName ?: "file"

        if (fileName.endsWith(".php", ignoreCase = true)) {
            servePhpFromSafUri(call, fileUri, fileName, queryString, method)
        } else {
            serveStaticFromSafUri(call, fileUri, fileName)
        }
    }

    // ── SAF FOLDER MODE ──

    private suspend fun handleFolderModeRequest(call: ApplicationCall, requestPath: String, queryString: String, method: String) {
        val uri = projectTreeUri ?: run {
            call.respondText(buildHtmlError("503", "No folder selected"), ContentType.Text.Html, HttpStatusCode.ServiceUnavailable)
            return
        }
        val treeDoc = DocumentFile.fromTreeUri(context, Uri.parse(uri))
            ?: throw IllegalStateException("Invalid tree URI")

        var normalizedPath = requestPath.trimStart('/')
        if (normalizedPath.isBlank()) {
            normalizedPath = findIndexFileSaf(treeDoc) ?: "index.html"
        }

        val fileDoc = findFileSaf(treeDoc, normalizedPath)
        if (fileDoc == null || !fileDoc.exists()) {
            call.respondText(buildHtmlError("404", "Not found: $requestPath"), ContentType.Text.Html, HttpStatusCode.NotFound)
            return
        }

        if (normalizedPath.endsWith(".php", ignoreCase = true)) {
            servePhpFromSafDoc(call, fileDoc, normalizedPath, queryString, method)
        } else {
            serveStaticFromSafDoc(call, fileDoc, normalizedPath)
        }
    }

    // ── SAF HELPERS ──

    private fun findIndexFileSaf(root: DocumentFile): String? {
        listOf("index.php", "index.html", "index.htm").forEach {
            val f = root.findFile(it)
            if (f != null && f.exists()) return it
        }
        return null
    }

    private fun findFileSaf(root: DocumentFile, path: String): DocumentFile? {
        if (path.isBlank()) return null
        var current = root
        for (seg in path.split("/")) {
            if (seg.isBlank()) continue
            val child = current.findFile(seg) ?: return null
            if (!child.exists()) return null
            current = child
        }
        return current
    }

    private suspend fun serveStaticFromSafDoc(call: ApplicationCall, doc: DocumentFile, path: String) {
        val ext = path.substringAfterLast('.', "").lowercase()
        val mime = ServerConfig.getMimeType(ext)
        val bytes = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(doc.uri)?.use { input ->
                val baos = ByteArrayOutputStream()
                input.copyTo(baos)
                baos.toByteArray()
            }
        }
        if (bytes != null) call.respondBytes(bytes, ContentType.parse(mime))
        else call.respondText(buildHtmlError("500", "Cannot read: $path"), ContentType.Text.Html, HttpStatusCode.InternalServerError)
    }

    private suspend fun servePhpFromSafDoc(call: ApplicationCall, doc: DocumentFile, path: String, queryString: String, method: String) {
        val tempFile = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "php_exec")
            dir.mkdirs()
            val f = File(dir, path)
            f.parentFile?.mkdirs()
            context.contentResolver.openInputStream(doc.uri)?.use { input ->
                f.outputStream().use { out -> input.copyTo(out) }
            }
            f
        }

        val documentRoot = withContext(Dispatchers.IO) {
            val d = File(context.cacheDir, "php_root"); d.mkdirs(); d.absolutePath
        }

        val body = if (method in listOf("POST", "PUT", "PATCH")) try { call.receiveText() } catch (_: Exception) { null } else null
        val env = mapOf(
            "REQUEST_URI" to call.request.path(), "QUERY_STRING" to queryString,
            "REQUEST_METHOD" to method, "CONTENT_TYPE" to (call.request.headers[HttpHeaders.ContentType] ?: ""),
            "CONTENT_LENGTH" to (body?.length?.toString() ?: "0"), "REQUEST_BODY" to (body ?: ""),
            "SCRIPT_NAME" to "/$path", "DOCUMENT_ROOT" to documentRoot,
            "SERVER_PORT" to port.toString(), "HTTP_COOKIE" to (call.request.headers[HttpHeaders.Cookie] ?: "")
        )

        val response = phpHandler.execute(tempFile.absolutePath, env)
        withContext(Dispatchers.IO) { tempFile.delete() }

        if (response != null) {
            response.headers.forEach { (k, v) ->
                if (k.lowercase() !in listOf("status", "content-length")) {
                    try { call.response.header(k, v) } catch (_: Exception) {}
                }
            }
            call.respondText(response.body, ContentType.Text.Html, HttpStatusCode.fromValue(response.statusCode))
        } else {
            call.respondText(buildHtmlError("500", "PHP error"), ContentType.Text.Html, HttpStatusCode.InternalServerError)
        }
    }

    private suspend fun servePhpFromSafUri(call: ApplicationCall, uri: Uri, fileName: String, queryString: String, method: String) {
        val tempFile = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "php_exec"); dir.mkdirs()
            val f = File(dir, fileName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                f.outputStream().use { out -> input.copyTo(out) }
            }
            f
        }
        val docRoot = withContext(Dispatchers.IO) { val d = File(context.cacheDir, "php_root"); d.mkdirs(); d.absolutePath }
        val body = if (method in listOf("POST", "PUT", "PATCH")) try { call.receiveText() } catch (_: Exception) { null } else null
        val env = mapOf(
            "REQUEST_URI" to call.request.path(), "QUERY_STRING" to queryString,
            "REQUEST_METHOD" to method, "CONTENT_TYPE" to (call.request.headers[HttpHeaders.ContentType] ?: ""),
            "CONTENT_LENGTH" to (body?.length?.toString() ?: "0"), "REQUEST_BODY" to (body ?: ""),
            "SCRIPT_NAME" to "/$fileName", "DOCUMENT_ROOT" to docRoot,
            "SERVER_PORT" to port.toString(), "HTTP_COOKIE" to (call.request.headers[HttpHeaders.Cookie] ?: "")
        )
        val response = phpHandler.execute(tempFile.absolutePath, env)
        withContext(Dispatchers.IO) { tempFile.delete() }
        if (response != null) {
            response.headers.forEach { (k, v) ->
                if (k.lowercase() !in listOf("status", "content-length")) {
                    try { call.response.header(k, v) } catch (_: Exception) {}
                }
            }
            call.respondText(response.body, ContentType.Text.Html, HttpStatusCode.fromValue(response.statusCode))
        } else {
            call.respondText(buildHtmlError("500", "PHP error"), ContentType.Text.Html, HttpStatusCode.InternalServerError)
        }
    }

    private suspend fun serveStaticFromSafUri(call: ApplicationCall, uri: Uri, fileName: String) {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val mime = ServerConfig.getMimeType(ext)
        val bytes = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val baos = ByteArrayOutputStream(); input.copyTo(baos); baos.toByteArray()
            }
        }
        if (bytes != null) call.respondBytes(bytes, ContentType.parse(mime))
        else call.respondText(buildHtmlError("500", "Cannot read file"), ContentType.Text.Html, HttpStatusCode.InternalServerError)
    }

    private fun buildHtmlError(title: String, message: String): String {
        return """<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>$title</title><style>body{font-family:sans-serif;padding:40px 20px;background:#f5f5f5;color:#333}.c{max-width:600px;margin:0 auto;background:#fff;padding:30px;border-radius:8px;box-shadow:0 2px 10px rgba(0,0,0,.1)}h1{color:#e74c3c;font-size:22px;margin-top:0}p{line-height:1.6}code{background:#f0f0f0;padding:2px 6px;border-radius:3px;font-size:14px}.f{margin-top:20px;color:#999;font-size:12px;text-align:center}</style></head><body><div class="c"><h1>$title</h1><p>$message</p><div class="f">Laragon Android</div></div></body></html>"""
    }
}
