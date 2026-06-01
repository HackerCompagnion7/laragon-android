package com.laragon.android.util

import android.app.ActivityManager
import android.content.Context
import java.io.RandomAccessFile

/**
 * System resource monitor for diagnostics.
 * Provides approximate RAM and CPU usage information.
 * Uses only NetworkInterface (no deprecated WifiManager APIs).
 */
class ResourceMonitor(private val context: Context) {

    private val activityManager by lazy {
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    }

    /**
     * Get approximate memory info for the app process.
     * Returns used MB / total MB.
     */
    fun getMemoryInfo(): Pair<Long, Long> {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val runtime = Runtime.getRuntime()
        val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val totalMem = memInfo.totalMem / (1024 * 1024)

        return Pair(usedMem, totalMem)
    }

    /**
     * Get the device's local IP address.
     * Enumerates network interfaces instead of using deprecated WifiManager.
     * Returns "N/A" if not connected.
     */
    fun getLocalIpAddress(): String {
        return tryGetIpFromInterfaces() ?: "N/A"
    }

    /**
     * Enumerate network interfaces to find local IP.
     * Works on all Android versions without deprecated APIs.
     */
    private fun tryGetIpFromInterfaces(): String? {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Get approximate CPU usage as a percentage (0-100).
     * Uses /proc/stat parsing for a rough estimate.
     */
    fun getCpuUsage(): Float {
        return try {
            val first = readCpuStats()
            Thread.sleep(500)
            val second = readCpuStats()

            if (first != null && second != null) {
                val idleDiff = second.idle - first.idle
                val totalDiff = second.total - first.total
                if (totalDiff > 0) {
                    ((totalDiff - idleDiff).toFloat() / totalDiff.toFloat()) * 100f
                } else 0f
            } else 0f
        } catch (_: Exception) {
            0f
        }
    }

    /**
     * Read CPU stats from /proc/stat.
     */
    private fun readCpuStats(): CpuStats? {
        return try {
            val reader = RandomAccessFile("/proc/stat", "r")
            val line = reader.readLine()
            reader.close()

            val parts = line.split("\\s+".toRegex())
            if (parts.size < 5) return null

            val user = parts[1].toLong()
            val nice = parts[2].toLong()
            val system = parts[3].toLong()
            val idle = parts[4].toLong()

            CpuStats(idle, user + nice + system + idle)
        } catch (_: Exception) {
            null
        }
    }

    private data class CpuStats(val idle: Long, val total: Long)
}
