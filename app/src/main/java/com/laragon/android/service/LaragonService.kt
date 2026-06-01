package com.laragon.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.laragon.android.server.LaragonServer
import com.laragon.android.ui.main.MainActivity
import com.laragon.android.util.LogRotator
import com.laragon.android.util.PhpBinaryManager
import com.laragon.android.util.ResourceMonitor
import com.laragon.android.util.ServerConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class LaragonService : Service() {

    private val TAG = "LaragonService"

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var server: LaragonServer? = null
    private var logRotator: LogRotator? = null
    private var resourceMonitor: ResourceMonitor? = null
    private var phpBinaryManager: PhpBinaryManager? = null
    private var prefs: SharedPreferences? = null

    private val _serverState = MutableStateFlow(ServerState.STOPPED)
    val serverState: StateFlow<ServerState> get() = _serverState.asStateFlow()

    private val _diagnostics = MutableStateFlow(DiagnosticsData())
    val diagnostics: StateFlow<DiagnosticsData> get() = _diagnostics.asStateFlow()

    private var monitorJob: Job? = null
    private val _activePhpProcesses = MutableStateFlow(0)

    companion object {
        const val ACTION_START = "com.laragon.android.ACTION_START_SERVER"
        const val ACTION_STOP = "com.laragon.android.ACTION_STOP_SERVER"
        const val EXTRA_PORT = "extra_port"

        private var instance: LaragonService? = null
        fun getInstance(): LaragonService? = instance
        fun getServerState(): StateFlow<ServerState>? = instance?.serverState
        fun getDiagnostics(): StateFlow<DiagnosticsData>? = instance?.diagnostics
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize each component in its own try-catch so one failure doesn't kill the service
        try {
            server = LaragonServer(this)
        } catch (e: Throwable) {
            Log.e(TAG, "LaragonServer init failed", e)
        }

        try {
            logRotator = LogRotator(this)
        } catch (e: Throwable) {
            Log.e(TAG, "LogRotator init failed", e)
        }

        try {
            resourceMonitor = ResourceMonitor(this)
        } catch (e: Throwable) {
            Log.e(TAG, "ResourceMonitor init failed", e)
        }

        try {
            phpBinaryManager = PhpBinaryManager(this)
        } catch (e: Throwable) {
            Log.e(TAG, "PhpBinaryManager init failed", e)
        }

        try {
            prefs = getSharedPreferences(ServerConfig.PREFS_NAME, Context.MODE_PRIVATE)
        } catch (e: Throwable) {
            Log.e(TAG, "SharedPreferences init failed", e)
        }

        try {
            createNotificationChannel()
        } catch (e: Throwable) {
            Log.e(TAG, "NotificationChannel creation failed", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_START -> startServer(intent.getIntExtra(EXTRA_PORT, ServerConfig.DEFAULT_PORT))
                ACTION_STOP -> { stopServer(); stopSelf() }
                else -> startServer(ServerConfig.DEFAULT_PORT)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "onStartCommand error", e)
            _serverState.value = ServerState.ERROR
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try { stopServer() } catch (_: Throwable) {}
        serviceScope.cancel()
        instance = null
        super.onDestroy()
    }

    private fun startServer(port: Int) {
        if (_serverState.value == ServerState.RUNNING) return
        _serverState.value = ServerState.STARTING

        serviceScope.launch {
            try {
                val php = phpBinaryManager
                if (php != null && !php.isInitialized()) {
                    val ok = php.extractBinary()
                    if (!ok) logRotator?.log(ServerConfig.ERROR_LOG, "PHP binary extraction failed", LogRotator.LogLevel.ERROR)
                }

                val srv = server
                if (srv == null) {
                    _serverState.value = ServerState.ERROR
                    logRotator?.log(ServerConfig.ERROR_LOG, "Server not initialized", LogRotator.LogLevel.ERROR)
                    return@launch
                }

                val mode = prefs?.getString(ServerConfig.PREF_SELECTION_MODE, ServerConfig.MODE_PATH) ?: ServerConfig.MODE_PATH

                when (mode) {
                    ServerConfig.MODE_PATH -> {
                        val path = prefs?.getString(ServerConfig.PREF_DIRECT_PATH, null)
                        if (path == null || !File(path).exists()) {
                            _serverState.value = ServerState.ERROR
                            logRotator?.log(ServerConfig.ERROR_LOG, "Invalid path: $path", LogRotator.LogLevel.ERROR)
                            return@launch
                        }
                        srv.startWithPath(path)
                    }
                    ServerConfig.MODE_FILE -> {
                        val directPath = prefs?.getString(ServerConfig.PREF_DIRECT_PATH, null)
                        val fileUri = prefs?.getString(ServerConfig.PREF_FILE_URI, null)
                        val fileName = prefs?.getString(ServerConfig.PREF_FILE_NAME, "file")

                        if (directPath != null && File(directPath).exists()) {
                            srv.startWithDirectFile(directPath, fileName ?: "file")
                        } else if (fileUri != null) {
                            srv.startWithFile(fileUri, fileName ?: "file")
                        } else {
                            _serverState.value = ServerState.ERROR
                            logRotator?.log(ServerConfig.ERROR_LOG, "No file selected", LogRotator.LogLevel.ERROR)
                            return@launch
                        }
                    }
                    ServerConfig.MODE_FOLDER -> {
                        val treeUri = prefs?.getString(ServerConfig.PREF_TREE_URI, null)
                        if (treeUri == null) {
                            _serverState.value = ServerState.ERROR
                            logRotator?.log(ServerConfig.ERROR_LOG, "No folder selected", LogRotator.LogLevel.ERROR)
                            return@launch
                        }
                        srv.start(treeUri)
                    }
                }

                _serverState.value = ServerState.RUNNING
                startForeground(ServerConfig.NOTIFICATION_ID, createNotification(port))
                startMonitoring(port)

            } catch (e: Throwable) {
                _serverState.value = ServerState.ERROR
                logRotator?.log(ServerConfig.ERROR_LOG, "Start failed: ${e.message}", LogRotator.LogLevel.ERROR)
            }
        }
    }

    private fun stopServer() {
        if (_serverState.value == ServerState.STOPPED) return
        monitorJob?.cancel()
        try { server?.stop() } catch (_: Throwable) {}
        _serverState.value = ServerState.STOPPED
    }

    private fun startMonitoring(port: Int) {
        monitorJob = serviceScope.launch {
            while (isActive) {
                try {
                    val resMon = resourceMonitor
                    val logRot = logRotator

                    val usedMem = resMon?.getMemoryInfo()?.first ?: 0L
                    val totalMem = resMon?.getMemoryInfo()?.second ?: 0L
                    val cpu = resMon?.getCpuUsage() ?: 0f
                    val ip = resMon?.getLocalIpAddress() ?: "N/A"

                    _diagnostics.value = DiagnosticsData(
                        serverRunning = _serverState.value == ServerState.RUNNING,
                        ipAddress = ip, port = port,
                        usedMemoryMb = usedMem, totalMemoryMb = totalMem,
                        cpuUsagePercent = cpu, activePhpProcesses = _activePhpProcesses.value,
                        serverLog = logRot?.readLastLines(ServerConfig.SERVER_LOG, 20) ?: emptyList(),
                        phpLog = logRot?.readLastLines(ServerConfig.PHP_LOG, 20) ?: emptyList(),
                        errorLog = logRot?.readLastLines(ServerConfig.ERROR_LOG, 10) ?: emptyList()
                    )
                    updateNotification(port, ip)
                    withContext(Dispatchers.IO) {
                        try {
                            val out = Runtime.getRuntime().exec("ps").inputStream.bufferedReader().readText()
                            _activePhpProcesses.value = out.split("\n").count { it.contains("php-cgi") }
                        } catch (_: Throwable) { _activePhpProcesses.value = 0 }
                    }
                } catch (_: Throwable) {}
                delay(3000)
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            ServerConfig.NOTIFICATION_CHANNEL_ID,
            ServerConfig.NOTIFICATION_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Laragon server notification"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(port: Int): Notification {
        val intent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = Intent(this, LaragonService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, ServerConfig.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Laragon Server Active")
            .setContentText("Running on port $port")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    private fun updateNotification(port: Int, ip: String) {
        try {
            val n = NotificationCompat.Builder(this, ServerConfig.NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Laragon Server Active")
                .setContentText("http://$ip:$port")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            getSystemService(NotificationManager::class.java).notify(ServerConfig.NOTIFICATION_ID, n)
        } catch (_: Throwable) {}
    }

    enum class ServerState { STOPPED, STARTING, RUNNING, ERROR }

    data class DiagnosticsData(
        val serverRunning: Boolean = false,
        val ipAddress: String = "N/A",
        val port: Int = ServerConfig.DEFAULT_PORT,
        val usedMemoryMb: Long = 0,
        val totalMemoryMb: Long = 0,
        val cpuUsagePercent: Float = 0f,
        val activePhpProcesses: Int = 0,
        val serverLog: List<String> = emptyList(),
        val phpLog: List<String> = emptyList(),
        val errorLog: List<String> = emptyList()
    )
}
