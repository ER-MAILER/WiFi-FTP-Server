package com.example.viewmodel

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.FtpConfigEntity
import com.example.data.FtpConfigRepository
import com.example.data.RemoteServerEntity
import com.example.server.FtpServerService
import com.example.server.FtpServerState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(private val repository: FtpConfigRepository) : ViewModel() {

    // Expose background FTP server states
    val isRunning: StateFlow<Boolean> = FtpServerState.isRunning
    val activePort: StateFlow<Int> = FtpServerState.port
    val ipAddress: StateFlow<String> = FtpServerState.ipAddress
    val clientCount: StateFlow<Int> = FtpServerState.clientCount
    val logs: StateFlow<List<String>> = FtpServerState.logs

    // Expose saved configuration settings flow
    val savedConfig: StateFlow<FtpConfigEntity> = repository.configFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = FtpConfigEntity()
        )

    // Remote server connection profiles
    val remoteServers: StateFlow<List<RemoteServerEntity>> = repository.remoteServersFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun startStopServer(context: Context) {
        if (isRunning.value) {
            stopFtpServer(context)
        } else {
            startFtpServer(context)
        }
    }

    private fun startFtpServer(context: Context) {
        val intent = Intent(context, FtpServerService::class.java).apply {
            action = FtpServerService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun stopFtpServer(context: Context) {
        val intent = Intent(context, FtpServerService::class.java).apply {
            action = FtpServerService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun updateConfig(config: FtpConfigEntity) {
        viewModelScope.launch {
            repository.saveConfig(config)
        }
    }

    fun saveRemoteServer(server: RemoteServerEntity) {
        viewModelScope.launch {
            repository.saveRemoteServer(server)
        }
    }

    fun deleteRemoteServer(server: RemoteServerEntity) {
        viewModelScope.launch {
            repository.deleteRemoteServer(server)
        }
    }

    fun clearLogs() {
        FtpServerState.clearLogs()
    }

    // ViewModelProvider Factory Class
    class Factory(private val repository: FtpConfigRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return MainViewModel(repository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
