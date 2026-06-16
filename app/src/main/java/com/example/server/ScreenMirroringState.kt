package com.example.server

import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ScreenMirroringState {
    val isRunning = MutableStateFlow(false)
    val ipAddress = MutableStateFlow("0.0.0.0")
    val port = MutableStateFlow(5050)
    val clientCount = MutableStateFlow(0)
    val logs = MutableStateFlow<List<String>>(emptyList())
    
    val isTunnelEnabled = MutableStateFlow(false)
    val isTunnelConnected = MutableStateFlow(false)
    val publicUrl = MutableStateFlow<String?>(null)

    fun addLog(msg: String) {
        val current = logs.value.toMutableList()
        if (current.size > 200) current.removeAt(0)
        current.add("[${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}] $msg")
        logs.value = current
    }

    fun clearLogs() {
        logs.value = emptyList()
    }
}
