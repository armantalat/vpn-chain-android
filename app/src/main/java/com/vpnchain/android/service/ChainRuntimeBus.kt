package com.vpnchain.android.service

import com.vpnchain.android.model.ConnectionPhase
import com.vpnchain.android.model.LogEntry
import com.vpnchain.android.model.LogLevel
import com.vpnchain.android.model.RuntimeStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ChainRuntimeBus {
    private val _status = MutableStateFlow(RuntimeStatus())
    val status: StateFlow<RuntimeStatus> = _status.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun updateStatus(transform: (RuntimeStatus) -> RuntimeStatus) {
        _status.value = transform(_status.value)
    }

    fun setPhase(phase: ConnectionPhase, detail: String) {
        _status.value = _status.value.copy(phase = phase, statusDetail = detail)
    }

    fun log(level: LogLevel, msg: String) {
        val ts = timeFmt.format(Date())
        val entry = LogEntry(ts, level, msg)
        val current = _logs.value
        _logs.value = (current + entry).takeLast(300)
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
