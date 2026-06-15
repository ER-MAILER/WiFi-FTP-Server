package com.example.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.AppDatabase
import com.example.data.FtpConfigRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

object FtpServerState {
    val isRunning = MutableStateFlow(false)
    val port = MutableStateFlow(2121)
    val ipAddress = MutableStateFlow("127.0.0.1")
    val clientCount = MutableStateFlow(0)
    val logs = MutableStateFlow<List<String>>(emptyList())

    fun addLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val logLine = "[$time] $message"
        val currentLogs = logs.value.toMutableList()
        currentLogs.add(logLine)
        if (currentLogs.size > 150) {
            currentLogs.removeAt(0)
        }
        logs.value = currentLogs
    }

    fun clearLogs() {
        logs.value = emptyList()
    }
}

class FtpServerService : Service() {

    companion object {
        private const val CHANNEL_ID = "ftp_server_channel"
        private const val NOTIFICATION_ID = 2121
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
    }

    private var ftpServer: SimpleFtpServer? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        if (action == ACTION_STOP) {
            stopServer()
            stopSelf()
            return START_NOT_STICKY
        }

        startServer()
        return START_STICKY
    }

    private fun startServer() {
        if (FtpServerState.isRunning.value) return

        serviceScope.launch {
            try {
                // Fetch latest configurations from Room Database
                val db = AppDatabase.getDatabase(applicationContext)
                val repository = FtpConfigRepository(db.ftpConfigDao())
                val config = repository.getConfig()

                val configuredPort = config.port
                val isAnon = config.anonymous
                val user = config.username
                val pass = config.password
                val rootType = config.rootDirType
                val customPath = config.customPath

                // Resolve target directory
                val targetDir = if (rootType == "SANDBOX") {
                    applicationContext.getExternalFilesDir(null) ?: applicationContext.filesDir
                } else {
                    if (customPath.isNotEmpty()) {
                        File(customPath)
                    } else {
                        // Default to external storage directory
                        @Suppress("DEPRECATION")
                        android.os.Environment.getExternalStorageDirectory()
                    }
                }

                // Ensure directory is created
                if (!targetDir.exists()) {
                    targetDir.mkdirs()
                }

                val currentIp = getWifiIpAddress(applicationContext) ?: "127.0.0.1"

                FtpServerState.port.value = configuredPort
                FtpServerState.ipAddress.value = currentIp
                FtpServerState.isRunning.value = true
                FtpServerState.addLog("Initializing server...")

                // Start Server Instance
                ftpServer = SimpleFtpServer(
                    port = configuredPort,
                    isAnonymous = isAnon,
                    authUser = user,
                    authPass = pass,
                    rootDir = targetDir,
                    localIpAddress = currentIp,
                    listener = object : SimpleFtpServer.FtpServerListener {
                        override fun onLog(message: String) {
                            FtpServerState.addLog(message)
                        }

                        override fun onClientCountChanged(count: Int) {
                            FtpServerState.clientCount.value = count
                        }
                    }
                )

                ftpServer?.start()

                // Register foreground notification
                startForeground(NOTIFICATION_ID, buildNotification(currentIp, configuredPort))

            } catch (e: Exception) {
                FtpServerState.addLog("Failed to start server: ${e.message}")
                stopServer()
            }
        }
    }

    private fun stopServer() {
        if (!FtpServerState.isRunning.value) return
        
        FtpServerState.addLog("Stopping Server...")
        ftpServer?.stop()
        ftpServer = null
        FtpServerState.isRunning.value = false
        FtpServerState.clientCount.value = 0
        
        stopForeground(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "WiFi FTP Server Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Displays the state of the active WiFi FTP Server."
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(ip: String, port: Int): Notification {
        val stopIntent = Intent(this, FtpServerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val mainIntent = Intent(this, MainActivity::class.java)
        val mainPendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WiFi FTP Server Running")
            .setContentText("Access: ftp://$ip:$port")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(mainPendingIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "STOP SERVER",
                stopPendingIntent
            )
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun getWifiIpAddress(context: Context): String? {
        var wlanIp: String? = null
        var apIp: String? = null
        var ethIp: String? = null
        var otherIp: String? = null

        // Try NetworkInterface iteration first, as it is the most reliable way to find specific interfaces
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (netInterface in interfaces) {
                if (!netInterface.isUp || netInterface.isLoopback) continue
                val name = netInterface.name.lowercase(Locale.US)
                val addresses = Collections.list(netInterface.inetAddresses)
                for (addr in addresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        when {
                            name.contains("wlan") -> wlanIp = ip
                            name.contains("ap") || name.contains("softap") -> apIp = ip
                            name.contains("eth") -> ethIp = ip
                            else -> otherIp = ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("FtpServerService", "Error iterating network interfaces", e)
        }

        // Return highest priority IP address found
        wlanIp?.let { return it }
        apIp?.let { return it }
        ethIp?.let { return it }

        // Fallback 1: Link Properties of Active Network
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val linkProperties = connectivityManager.getLinkProperties(connectivityManager.activeNetwork)
            if (linkProperties != null) {
                for (linkAddress in linkProperties.linkAddresses) {
                    val address = linkAddress.address
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("FtpServerService", "Error checking link properties", e)
        }

        // Fallback 2: WifiManager connection info
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ipAddress = wifiManager.connectionInfo.ipAddress
            if (ipAddress != 0) {
                return String.format(
                    Locale.US,
                    "%d.%d.%d.%d",
                    (ipAddress and 0xff),
                    (ipAddress shr 8 and 0xff),
                    (ipAddress shr 16 and 0xff),
                    (ipAddress shr 24 and 0xff)
                )
            }
        } catch (e: Exception) {
            Log.e("FtpServerService", "Error checking wifi connection info", e)
        }

        // Fallback 3: Return any other interface found
        otherIp?.let { return it }

        return null
    }
}
