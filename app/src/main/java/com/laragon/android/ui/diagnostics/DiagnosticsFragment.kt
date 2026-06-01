package com.laragon.android.ui.diagnostics

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.laragon.android.R
import com.laragon.android.service.LaragonService
import com.laragon.android.util.ServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Bottom sheet fragment that displays real-time server diagnostics.
 * Shows: server status, IP, port, RAM, CPU, active PHP processes, and logs.
 */
class DiagnosticsFragment : BottomSheetDialogFragment() {

    private var tvStatus: TextView? = null
    private var tvIp: TextView? = null
    private var tvPort: TextView? = null
    private var tvRam: TextView? = null
    private var tvCpu: TextView? = null
    private var tvPhpProcesses: TextView? = null
    private var tvServerLog: TextView? = null
    private var tvPhpLog: TextView? = null
    private var tvErrorLog: TextView? = null

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    companion object {
        fun newInstance(): DiagnosticsFragment = DiagnosticsFragment()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_diagnostics, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvStatus = view.findViewById(R.id.tv_diag_status)
        tvIp = view.findViewById(R.id.tv_diag_ip)
        tvPort = view.findViewById(R.id.tv_diag_port)
        tvRam = view.findViewById(R.id.tv_diag_ram)
        tvCpu = view.findViewById(R.id.tv_diag_cpu)
        tvPhpProcesses = view.findViewById(R.id.tv_diag_php_processes)
        tvServerLog = view.findViewById(R.id.tv_diag_server_log)
        tvPhpLog = view.findViewById(R.id.tv_diag_php_log)
        tvErrorLog = view.findViewById(R.id.tv_diag_error_log)

        startObserving()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.coroutineContext[Job]?.cancel()
    }

    /**
     * Start observing diagnostics data from the service.
     */
    private fun startObserving() {
        val diagnosticsFlow = LaragonService.getDiagnostics() ?: return
        val stateFlow = LaragonService.getServerState() ?: return

        scope.launch {
            combine(diagnosticsFlow, stateFlow) { diag, state ->
                Pair(diag, state)
            }.catch { _ ->
                // Silently handle errors
            }.collect { (diag, state) ->
                tvStatus?.text = when (state) {
                    LaragonService.ServerState.RUNNING -> "Running"
                    LaragonService.ServerState.STARTING -> "Starting..."
                    LaragonService.ServerState.STOPPED -> "Stopped"
                    LaragonService.ServerState.ERROR -> "Error"
                }

                tvIp?.text = diag.ipAddress
                tvPort?.text = diag.port.toString()
                tvRam?.text = "${diag.usedMemoryMb} MB / ${diag.totalMemoryMb} MB"
                tvCpu?.text = "${"%.1f".format(diag.cpuUsagePercent)}%"
                tvPhpProcesses?.text = diag.activePhpProcesses.toString()

                tvServerLog?.text = if (diag.serverLog.isNotEmpty()) {
                    diag.serverLog.joinToString("\n")
                } else {
                    "No server logs yet"
                }

                tvPhpLog?.text = if (diag.phpLog.isNotEmpty()) {
                    diag.phpLog.joinToString("\n")
                } else {
                    "No PHP logs yet"
                }

                tvErrorLog?.text = if (diag.errorLog.isNotEmpty()) {
                    diag.errorLog.joinToString("\n")
                } else {
                    "No errors"
                }
            }
        }
    }
}
