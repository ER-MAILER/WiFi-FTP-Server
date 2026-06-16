package com.example.server

import com.example.MainActivity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.Locale

class ScreenMirroringService : Service() {

    companion object {
        const val CHANNEL_ID = "ScreenMirroringChannel"
        const val NOTIFICATION_ID = 9922
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"

        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    private var serverSocket: ServerSocket? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var latestFrame: ByteArray? = null

    // Downscaled stream resolution for buttery smooth remote stream (540x960)
    private val streamWidth = 540
    private val streamHeight = 960

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopService()
            return START_NOT_STICKY
        }

        if (action == ACTION_START) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            val resultData = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                }
            } catch (e: Exception) {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            } ?: (intent.extras?.get(EXTRA_RESULT_DATA) as? Intent)

            if (resultCode != 0 && resultData != null) {
                startService(resultCode, resultData)
            } else {
                ScreenMirroringState.addLog("Failed to start: Invalid launch parameters.")
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startService(resultCode: Int, resultData: Intent) {
        val currentIp = getLocalIpAddress()
        val currentPort = ScreenMirroringState.port.value

        ScreenMirroringState.ipAddress.value = currentIp
        ScreenMirroringState.isRunning.value = true
        ScreenMirroringState.addLog("Screen Sharing Server starting on http://$currentIp:$currentPort")

        // 1. Run Foreground service notification
        val notification = buildNotification(currentIp, currentPort)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, 
                notification, 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // 2. Setup Screen Capture via MediaProjection (with a tiny delay to ensure foreground service is fully registered by system)
        serviceScope.launch(Dispatchers.Main) {
            kotlinx.coroutines.delay(500)
            setupScreenCapture(resultCode, resultData)
        }

        // 3. Setup Web Server
        startWebServer(currentPort)

        // 4. Listen for tunnel configuration changes dynamically
        serviceScope.launch {
            ScreenMirroringState.isTunnelEnabled.collect { enabled ->
                if (enabled) {
                    SshTunnelManager.startTunnelIfNeeded(currentPort)
                } else {
                    SshTunnelManager.stopTunnel()
                }
            }
        }
    }

    private fun setupScreenCapture(resultCode: Int, resultData: Intent) {
        try {
            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, resultData)

            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display?.getRealMetrics(metrics)
            } else {
                wm.defaultDisplay.getRealMetrics(metrics)
            }

            // Create Image Reader with custom downscaled resolution (540x960) for network efficiency
            imageReader = ImageReader.newInstance(
                streamWidth,
                streamHeight,
                PixelFormat.RGBA_8888,
                2
            )

            handlerThread = HandlerThread("ScreenCaptureThread")
            handlerThread?.start()
            handler = Handler(handlerThread!!.looper)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenMirrorVirtualDisplay",
                streamWidth,
                streamHeight,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null,
                null
            )

            imageReader?.setOnImageAvailableListener({ reader ->
                var image: android.media.Image? = null
                try {
                    image = reader.acquireLatestImage()
                    if (image != null) {
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * streamWidth

                        val bitmap = Bitmap.createBitmap(
                            streamWidth + rowPadding / pixelStride,
                            streamHeight,
                            Bitmap.Config.ARGB_8888
                        )
                        bitmap.copyPixelsFromBuffer(buffer)

                        // Crop row padded pixels
                        val croppedBitmap = if (bitmap.width > streamWidth) {
                            Bitmap.createBitmap(bitmap, 0, 0, streamWidth, streamHeight)
                        } else {
                            bitmap
                        }

                        val baos = ByteArrayOutputStream()
                        croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos)
                        latestFrame = baos.toByteArray()

                        if (croppedBitmap !== bitmap) {
                            croppedBitmap.recycle()
                        }
                        bitmap.recycle()
                    }
                } catch (e: Exception) {
                    // Fail gracefully
                } finally {
                    image?.close()
                }
            }, handler)

            ScreenMirroringState.addLog("MediaProjection Capture setup successful.")
        } catch (e: Exception) {
            ScreenMirroringState.addLog("Failed to configure capture: ${e.message}")
            stopService()
        }
    }

    private fun startWebServer(port: Int) {
        serviceScope.launch {
            try {
                serverSocket = ServerSocket(port)
                while (ScreenMirroringState.isRunning.value) {
                    val socket = serverSocket?.accept() ?: break
                    serviceScope.launch {
                        handleClient(socket)
                    }
                }
            } catch (e: Exception) {
                ScreenMirroringState.addLog("Server socket error: ${e.message}")
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), "UTF-8"))
            val firstLine = reader.readLine() ?: return
            val parts = firstLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0]
            val pathWithParams = parts[1]

            // Basic parsing of query string parameters
            val path = pathWithParams.split("?")[0]
            val queryParams = parseQueryParams(pathWithParams)

            val out = socket.getOutputStream()

            when {
                path == "/" -> {
                    val html = getHtmlDashboard()
                    val bytes = html.toByteArray(Charsets.UTF_8)
                    out.write("HTTP/1.1 200 OK\r\n".toByteArray())
                    out.write("Content-Type: text/html; charset=utf-8\r\n".toByteArray())
                    out.write("Content-Length: ${bytes.size}\r\n".toByteArray())
                    out.write("Connection: close\r\n\r\n".toByteArray())
                    out.write(bytes)
                    out.flush()
                }

                path == "/stream" -> {
                    out.write("HTTP/1.1 200 OK\r\n".toByteArray())
                    out.write("Content-Type: multipart/x-mixed-replace; boundary=--frame\r\n".toByteArray())
                    out.write("Cache-Control: no-store, no-cache, must-revalidate, pre-check=0, post-check=0, max-age=0\r\n".toByteArray())
                    out.write("Pragma: no-cache\r\n".toByteArray())
                    out.write("Connection: keep-alive\r\n\r\n".toByteArray())
                    out.flush()

                    ScreenMirroringState.clientCount.value++
                    try {
                        var lastSendTime = 0L
                        while (ScreenMirroringState.isRunning.value && !socket.isClosed) {
                            val frame = latestFrame
                            if (frame != null) {
                                val now = System.currentTimeMillis()
                                // Cap stream frame rate around ~15 FPS to avoid extreme resource allocation
                                if (now - lastSendTime >= 65L) {
                                    out.write("--frame\r\n".toByteArray())
                                    out.write("Content-Type: image/jpeg\r\n".toByteArray())
                                    out.write("Content-Length: ${frame.size}\r\n\r\n".toByteArray())
                                    out.write(frame)
                                    out.write("\r\n".toByteArray())
                                    out.flush()
                                    lastSendTime = now
                                }
                            }
                            Thread.sleep(30L)
                        }
                    } catch (e: Exception) {
                        // Disconnect gracefully
                    } finally {
                        ScreenMirroringState.clientCount.value = maxOf(0, ScreenMirroringState.clientCount.value - 1)
                    }
                }

                path.startsWith("/action/") -> {
                    val actionName = path.substringAfter("/action/")
                    var resultMsg = "Action Ignored"

                    val accInstance = RemoteControlAccessibilityService.instance
                    if (accInstance != null) {
                        when (actionName) {
                            "back" -> {
                                val ok = accInstance.pressBackButton()
                                resultMsg = "Back Result: $ok"
                            }
                            "home" -> {
                                val ok = accInstance.pressHomeButton()
                                resultMsg = "Home Result: $ok"
                            }
                            "recents" -> {
                                val ok = accInstance.pressRecentsButton()
                                resultMsg = "Recents Result: $ok"
                            }
                            "tap" -> {
                                val xRaw = queryParams["x"]?.toFloatOrNull() ?: -1f
                                val yRaw = queryParams["y"]?.toFloatOrNull() ?: -1f

                                if (xRaw >= 0f && yRaw >= 0f) {
                                    // Scale coordinates from 540x960 to actual screen pixel boundaries
                                    val mapped = mapCoordinates(xRaw, yRaw)
                                    val ok = accInstance.tap(mapped.first, mapped.second)
                                    resultMsg = "Tap Result: $ok at mapped(${mapped.first}, ${mapped.second})"
                                } else {
                                    resultMsg = "Invalid tap coordinates"
                                }
                            }
                            "swipe" -> {
                                val x1Raw = queryParams["x1"]?.toFloatOrNull() ?: -1f
                                val y1Raw = queryParams["y1"]?.toFloatOrNull() ?: -1f
                                val x2Raw = queryParams["x2"]?.toFloatOrNull() ?: -1f
                                val y2Raw = queryParams["y2"]?.toFloatOrNull() ?: -1f
                                val duration = queryParams["duration"]?.toLongOrNull() ?: 300L

                                if (x1Raw >= 0f && y1Raw >= 0f && x2Raw >= 0f && y2Raw >= 0f) {
                                    val startMapped = mapCoordinates(x1Raw, y1Raw)
                                    val endMapped = mapCoordinates(x2Raw, y2Raw)
                                    val ok = accInstance.swipe(
                                        startMapped.first, startMapped.second,
                                        endMapped.first, endMapped.second,
                                        duration
                                    )
                                    resultMsg = "Swipe Result: $ok"
                                } else {
                                    resultMsg = "Invalid swipe coordinates"
                                }
                            }
                        }
                    } else {
                        resultMsg = "Ignored: Accessibility Service is not enabled."
                    }

                    val jsonResp = "{\"status\":\"ok\",\"message\":\"$resultMsg\"}"
                    val respBytes = jsonResp.toByteArray(Charsets.UTF_8)
                    out.write("HTTP/1.1 200 OK\r\n".toByteArray())
                    out.write("Content-Type: application/json\r\n".toByteArray())
                    out.write("Content-Length: ${respBytes.size}\r\n".toByteArray())
                    out.write("Connection: close\r\n\r\n".toByteArray())
                    out.write(respBytes)
                    out.flush()
                }

                else -> {
                    out.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                    out.flush()
                }
            }
        } catch (e: Exception) {
            // Graceful read/write failures
        } finally {
            try { socket.close() } catch (ignored: Exception) {}
        }
    }

    private fun mapCoordinates(streamX: Float, streamY: Float): Pair<Float, Float> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.getRealMetrics(metrics)
        } else {
            wm.defaultDisplay.getRealMetrics(metrics)
        }

        val realW = metrics.widthPixels.toFloat()
        val realH = metrics.heightPixels.toFloat()

        val scaleX = realW / streamWidth.toFloat()
        val scaleY = realH / streamHeight.toFloat()

        return Pair(streamX * scaleX, streamY * scaleY)
    }

    private fun parseQueryParams(path: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val parts = path.split("?")
        if (parts.size > 1) {
            val query = parts[1]
            val params = query.split("&")
            for (p in params) {
                val pair = p.split("=")
                if (pair.size > 1) {
                    map[pair[0]] = pair[1]
                }
            }
        }
        return map
    }

    private fun stopService() {
        ScreenMirroringState.addLog("Stopping Screen Sharing Service...")
        SshTunnelManager.stopTunnel()
        latestFrame = null

        try {
            serverSocket?.close()
        } catch (ignored: Exception) {}
        serverSocket = null

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        handlerThread?.quitSafely()
        handlerThread = null
        handler = null

        mediaProjection?.stop()
        mediaProjection = null

        ScreenMirroringState.isRunning.value = false
        ScreenMirroringState.clientCount.value = 0

        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        stopService()
        super.onDestroy()
    }

    private fun getLocalIpAddress(): String {
        val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = cm.activeNetwork ?: return "0.0.0.0"
        val linkProperties = cm.getLinkProperties(activeNetwork) ?: return "0.0.0.0"
        for (linkAddress in linkProperties.linkAddresses) {
            val address = linkAddress.address
            if (!address.isLoopbackAddress && address.hostAddress?.indexOf(':') == -1) {
                return address.hostAddress ?: "0.0.0.0"
            }
        }
        return "0.0.0.0"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Screen Share Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun buildNotification(ip: String, port: Int): Notification {
        val stopIntent = Intent(this, ScreenMirroringService::class.java).apply {
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
            .setContentTitle("WiFi Screen Share Running")
            .setContentText("Address: http://$ip:$port")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(mainPendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Console", stopPendingIntent)
            .build()
    }

    private fun getHtmlDashboard(): String {
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>WiFi Screen Share & Remote Control - ER DATAHUB</title>
                <link href="https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@400;500;700&family=JetBrains+Mono:wght@500&display=swap" rel="stylesheet">
                <style>
                    :root {
                        --bg-dark: #0B0F19;
                        --glass-bg: rgba(17, 24, 39, 0.7);
                        --primary: #3B82F6;
                        --accent: #10B981;
                        --danger: #EF4444;
                        --text-primary: #F3F4F6;
                        --text-secondary: #9CA3AF;
                        --border-gray: rgba(255, 255, 255, 0.08);
                    }
                    * {
                        box-sizing: border-box;
                        margin: 0;
                        padding: 0;
                        font-family: 'Space Grotesk', sans-serif;
                    }
                    body {
                        background: radial-gradient(circle at 50% 50%, #152238 0%, var(--bg-dark) 100%);
                        color: var(--text-primary);
                        height: 100vh;
                        overflow: hidden;
                        display: flex;
                        flex-direction: column;
                    }
                    header {
                        padding: 16px 24px;
                        background: rgba(11, 15, 25, 0.85);
                        backdrop-filter: blur(12px);
                        border-bottom: 1px solid var(--border-gray);
                        display: flex;
                        align-items: center;
                        justify-content: space-between;
                        z-index: 10;
                    }
                    .header-logo {
                        font-weight: 700;
                        font-size: 1.3rem;
                        background: linear-gradient(135deg, #60A5FA 0%, #3B82F6 100%);
                        -webkit-background-clip: text;
                        -webkit-text-fill-color: transparent;
                        letter-spacing: 1.5px;
                    }
                    .header-status {
                        display: flex;
                        align-items: center;
                        gap: 8px;
                        font-size: 0.9rem;
                        color: var(--text-secondary);
                    }
                    .status-indicator {
                        width: 10px;
                        height: 10px;
                        border-radius: 50%;
                        background-color: var(--accent);
                        box-shadow: 0 0 8px var(--accent);
                        animation: pulse 2s infinite;
                    }
                    @keyframes pulse {
                        0% { opacity: 0.4; }
                        50% { opacity: 1; }
                        100% { opacity: 0.4; }
                    }
                    .main-container {
                        display: flex;
                        flex: 1;
                        overflow: hidden;
                        padding: 24px;
                        gap: 24px;
                    }
                    .control-sidebar {
                        width: 320px;
                        background: var(--glass-bg);
                        backdrop-filter: blur(16px);
                        border: 1px solid var(--border-gray);
                        border-radius: 24px;
                        padding: 24px;
                        display: flex;
                        flex-direction: column;
                        gap: 24px;
                    }
                    .screen-wrapper {
                        flex: 1;
                        background: var(--glass-bg);
                        backdrop-filter: blur(16px);
                        border: 1px solid var(--border-gray);
                        border-radius: 24px;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        overflow: hidden;
                        position: relative;
                        padding: 16px;
                    }
                    #screen-view {
                        height: 100%;
                        max-width: 100%;
                        object-fit: contain;
                        box-shadow: 0 20px 40px rgba(0,0,0,0.4);
                        border-radius: 12px;
                        cursor: pointer;
                        user-select: none;
                        -webkit-user-drag: none;
                    }
                    .section-title {
                        font-size: 0.8rem;
                        font-weight: 700;
                        color: #60A5FA;
                        letter-spacing: 2px;
                        text-transform: uppercase;
                    }
                    .system-keys {
                        display: grid;
                        grid-template-columns: repeat(3, 1fr);
                        gap: 12px;
                    }
                    .btn {
                        background: rgba(255, 255, 255, 0.05);
                        border: 1.5px solid var(--border-gray);
                        color: var(--text-primary);
                        padding: 12px 8px;
                        border-radius: 14px;
                        font-weight: 700;
                        cursor: pointer;
                        transition: all 0.2s ease;
                        display: flex;
                        flex-direction: column;
                        align-items: center;
                        justify-content: center;
                        gap: 6px;
                        font-size: 0.8rem;
                    }
                    .btn:hover {
                        background: rgba(59, 130, 246, 0.15);
                        border-color: var(--primary);
                        transform: translateY(-2px);
                    }
                    .btn:active {
                        transform: translateY(0);
                    }
                    .btn-accent {
                        background: linear-gradient(135deg, #3B82F6 0%, #1D4ED8 100%);
                        border: none;
                    }
                    .btn-accent:hover {
                        background: linear-gradient(135deg, #4F46E5 0%, #3730A3 100%);
                    }
                    .console-card {
                        background: rgba(0, 0, 0, 0.3);
                        border-radius: 16px;
                        padding: 16px;
                        border: 1px solid var(--border-gray);
                        font-family: 'JetBrains Mono', monospace;
                        font-size: 0.8rem;
                        flex: 1;
                        overflow-y: auto;
                        display: flex;
                        flex-direction: column;
                        gap: 6px;
                    }
                    .log-line {
                        line-height: 1.4;
                    }
                    .log-success { color: var(--accent); }
                    .log-info { color: #60A5FA; }
                    .about-company {
                        font-size: 0.75rem;
                        color: var(--text-secondary);
                        line-height: 1.5;
                        border-top: 1px dashed var(--border-gray);
                        padding-top: 16px;
                    }
                    .about-company header {
                        background: none;
                        border: none;
                        padding: 0;
                        font-weight: 700;
                        color: var(--text-primary);
                        margin-bottom: 4px;
                        text-transform: uppercase;
                        font-size: 0.85rem;
                    }
                </style>
            </head>
            <body>
                <header>
                    <div class="header-logo">WiFi Screen Control</div>
                    <div class="header-status">
                        <div class="status-indicator"></div>
                        <span>Live with ER DATAHUB Server</span>
                    </div>
                </header>

                <div class="main-container">
                    <div class="control-sidebar">
                        <div>
                            <div class="section-title">Navigation Keys</div>
                            <div class="system-keys" style="margin-top: 10px;">
                                <button class="btn" onclick="sendAction('back')">
                                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M19 12H5M12 19l-7-7 7-7"/></svg>
                                    <span>BACK</span>
                                </button>
                                <button class="btn" onclick="sendAction('home')">
                                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/></svg>
                                    <span>HOME</span>
                                </button>
                                <button class="btn" onclick="sendAction('recents')">
                                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"/></svg>
                                    <span>RECENTS</span>
                                </button>
                            </div>
                        </div>

                        <div style="display:flex; flex-direction:column; flex:1; gap:10px;">
                            <div class="section-title">Activity Console</div>
                            <div class="console-card" id="console">
                                <div class="log-line log-success">> Console initialized successfully.</div>
                                <div class="log-line log-info">> Ready for touch input gestures...</div>
                            </div>
                        </div>

                        <div class="about-company">
                            <h4 style="color:#60A5FA; margin-bottom: 5px; font-weight:700;">ER DATAHUB INC</h4>
                            <p>We craft elite digital solutions, personalized software architectures, and automated system utilities. For application expansion or customized systems development, reach out anytime.</p>
                            <p style="margin-top: 8px; font-weight:700; color:var(--text-primary);">WhatsApp: 01940841273</p>
                        </div>
                    </div>

                    <div class="screen-wrapper">
                        <img id="screen-view" src="/stream" alt="Live Android Stream" />
                    </div>
                </div>

                <script>
                    const screen = document.getElementById('screen-view');
                    const consoleLogs = document.getElementById('console');

                    function addLog(message, type = 'info') {
                        const line = document.createElement('div');
                        line.className = 'log-line ' + (type === 'success' ? 'log-success' : 'log-info');
                        line.textContent = '> ' + message;
                        consoleLogs.appendChild(line);
                        consoleLogs.scrollTop = consoleLogs.scrollHeight;
                    }

                    // Touch/Mouse event variables for tracking Swipes
                    let startX = 0;
                    let startY = 0;
                    let startTime = 0;

                    screen.addEventListener('mousedown', (e) => {
                        const coords = getRelativeCoords(e);
                        startX = coords.x;
                        startY = coords.y;
                        startTime = Date.now();
                        e.preventDefault();
                    });

                    screen.addEventListener('mouseup', (e) => {
                        const coords = getRelativeCoords(e);
                        const endX = coords.x;
                        const endY = coords.y;
                        const duration = Date.now() - startTime;

                        const diffX = endX - startX;
                        const diffY = endY - startY;
                        const distance = Math.sqrt(diffX*diffX + diffY*diffY);

                        if (distance < 12 && duration < 250) {
                            // Touch Tap action
                            sendTap(startX, startY);
                        } else {
                            // Drag Swipe gesture
                            sendSwipe(startX, startY, endX, endY, Math.max(duration, 100));
                        }
                        e.preventDefault();
                    });

                    // Match screen touch drag event mapping for mobile testing browsers 
                    screen.addEventListener('touchstart', (e) => {
                        if (e.touches.length > 0) {
                            const touch = e.touches[0];
                            const coords = getRelativeCoords(touch);
                            startX = coords.x;
                            startY = coords.y;
                            startTime = Date.now();
                        }
                    });

                    screen.addEventListener('touchend', (e) => {
                        if (e.changedTouches.length > 0) {
                            const touch = e.changedTouches[0];
                            const coords = getRelativeCoords(touch);
                            const endX = coords.x;
                            const endY = coords.y;
                            const duration = Date.now() - startTime;
                            const diffX = endX - startX;
                            const diffY = endY - startY;
                            const distance = Math.sqrt(diffX*diffX + diffY*diffY);

                            if (distance < 12 && duration < 250) {
                                sendTap(startX, startY);
                            } else {
                                sendSwipe(startX, startY, endX, endY, Math.max(duration, 100));
                            }
                        }
                    });

                    function getRelativeCoords(e) {
                        const rect = screen.getBoundingClientRect();
                        const clientX = e.clientX || (e.touches && e.touches[0] ? e.touches[0].clientX : 0);
                        const clientY = e.clientY || (e.touches && e.touches[0] ? e.touches[0].clientY : 0);
                        const x = clientX - rect.left;
                        const y = clientY - rect.top;

                        // Map scaled HTML element pixel coordinate boundaries directly back to downscaled 540x960 native matrix coordinates
                        const mappedX = (x / rect.width) * 540;
                        const mappedY = (y / rect.height) * 960;

                        return { x: Math.round(mappedX), y: Math.round(mappedY) };
                    }

                    function sendAction(action) {
                        addLog('Triggering action: ' + action.toUpperCase() + '...');
                        fetch('/action/' + action)
                            .then(r => r.json())
                            .then(data => {
                                addLog(data.message, 'success');
                            })
                            .catch(err => {
                                addLog('Action execution failed - ensure Accessibility Service is running.', 'danger');
                            });
                    }

                    function sendTap(x, y) {
                        addLog('Sending point-tap gesture at (' + x + ', ' + y + ')...');
                        fetch('/action/tap?x=' + x + '&y=' + y)
                            .then(r => r.json())
                            .then(data => {
                                addLog(data.message, 'success');
                            })
                            .catch(err => {
                                addLog('Connection failed. Make sure Accessibility Service is enabled.', 'danger');
                            });
                    }

                    function sendSwipe(x1, y1, x2, y2, duration) {
                        addLog('Sending swipe gesture: (' + x1 + ',' + y1 + ') -> (' + x2 + ',' + y2 + ') duration ' + duration + 'ms...');
                        fetch('/action/swipe?x1=' + x1 + '&y1=' + y1 + '&x2=' + x2 + '&y2=' + y2 + '&duration=' + duration)
                            .then(r => r.json())
                            .then(data => {
                                addLog(data.message, 'success');
                            })
                            .catch(err => {
                                addLog('Swipe injection failed.', 'danger');
                            });
                    }
                </script>
            </body>
            </html>
        """.trimIndent()
    }
}
