package com.example.server

import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

object SshTunnelManager {
    private const val TAG = "SshTunnelManager"
    private var tunnelJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    val isTunnelEnabled = ScreenMirroringState.isTunnelEnabled
    val isTunnelConnected = ScreenMirroringState.isTunnelConnected
    val publicUrl = ScreenMirroringState.publicUrl

    fun startTunnelIfNeeded(localPort: Int) {
        if (!isTunnelEnabled.value || !ScreenMirroringState.isRunning.value) {
            stopTunnel()
            return
        }
        if (tunnelJob != null && tunnelJob?.isActive == true) {
            return
        }

        addLog("Initializing secure global online tunnel...")
        tunnelJob = scope.launch {
            var useLocalhostRun = true
            while (ScreenMirroringState.isRunning.value && isTunnelEnabled.value) {
                var session: Session? = null
                var channel: ChannelExec? = null
                try {
                    isTunnelConnected.value = false
                    publicUrl.value = null

                    val jsch = JSch()
                    val host = if (useLocalhostRun) "localhost.run" else "serveo.net"
                    addLog("Connecting to SSH tunnel via $host on local port $localPort...")

                    session = jsch.getSession("tunnel", host, 22)
                    session.setConfig("StrictHostKeyChecking", "no")
                    session.timeout = 15000 // 15 seconds timeout
                    session.connect()

                    // Request remote port forwarding (Host assigns a public subdomain/domain)
                    // Requesting 80 tells the remote that we want HTTP forwarding
                    session.setPortForwardingR(80, "127.0.0.1", localPort)

                    isTunnelConnected.value = true
                    addLog("Server connection established. Awaiting URL generation...")

                    // Open channel to read greet printed outputs with the actual URL
                    channel = session.openChannel("exec") as ChannelExec
                    channel.setCommand("")
                    val inputStream = channel.inputStream
                    channel.connect()

                    val reader = BufferedReader(InputStreamReader(inputStream))
                    var urlFound = false
                    val startTime = System.currentTimeMillis()

                    while (ScreenMirroringState.isRunning.value && isTunnelEnabled.value && session.isConnected) {
                        if (reader.ready()) {
                            val line = reader.readLine()
                            if (line != null) {
                                Log.d(TAG, "[$host] $line")
                                
                                // Parse localhost.run pattern: https://xxxx.lhr.life or https://xxxx.lhr.rocks
                                val lhrRegex = "(https://[a-zA-Z0-9.-]+\\.(lhr\\.life|lhr\\.rocks))".toRegex()
                                val lhrMatch = lhrRegex.find(line)
                                if (lhrMatch != null) {
                                    val url = lhrMatch.value
                                    publicUrl.value = url
                                    urlFound = true
                                    addLog("Public URL Generated: $url")
                                }

                                // Parse serveo.net pattern: https://xxxx.serveo.net
                                val serveoRegex = "(https://[a-zA-Z0-9.-]+\\.serveo\\.net)".toRegex()
                                val serveoMatch = serveoRegex.find(line)
                                if (serveoMatch != null) {
                                    val url = serveoMatch.value
                                    publicUrl.value = url
                                    urlFound = true
                                    addLog("Public URL Generated: $url")
                                }
                            }
                        } else {
                            if (!urlFound && System.currentTimeMillis() - startTime > 12000) {
                                // If no URL found in 12s, fallback option or reconnect
                                addLog("URL generation timed out. Retrying tunnel...")
                                break
                            }
                            delay(500)
                        }
                    }

                } catch (e: Exception) {
                    addLog("Tunnel connection failed: ${e.message}")
                    Log.e(TAG, "Tunnel Error", e)
                    // Flip host to try alternative
                    useLocalhostRun = !useLocalhostRun
                } finally {
                    isTunnelConnected.value = false
                    try { channel?.disconnect() } catch (ignored: Exception) {}
                    try { session?.disconnect() } catch (ignored: Exception) {}
                }

                // Delay before retrying connection
                if (ScreenMirroringState.isRunning.value && isTunnelEnabled.value) {
                    delay(6000)
                }
            }
        }
    }

    fun stopTunnel() {
        if (tunnelJob != null) {
            addLog("Global tunnel stopped.")
            tunnelJob?.cancel()
            tunnelJob = null
        }
        isTunnelConnected.value = false
        publicUrl.value = null
    }

    private fun addLog(msg: String) {
        ScreenMirroringState.addLog("[Tunnel] $msg")
    }
}
