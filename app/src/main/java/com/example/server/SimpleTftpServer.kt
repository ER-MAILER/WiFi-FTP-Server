package com.example.server

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

class SimpleTftpServer(
    private val port: Int,
    private val rootDir: File,
    private val listener: TftpServerListener
) {
    interface TftpServerListener {
        fun onLog(message: String)
    }

    private var socket: DatagramSocket? = null
    private var isRunning = false
    private val activeTransfers = ConcurrentHashMap<String, Thread>()

    fun start() {
        if (isRunning) return
        isRunning = true
        Thread {
            try {
                socket = DatagramSocket(port)
                listener.onLog("TFTP Server started on UdpPort $port")
                listener.onLog("Root directory: ${rootDir.absolutePath}")

                val buffer = ByteArray(1024)
                while (isRunning) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket?.receive(packet) ?: break
                        handleIncomingRequest(packet)
                    } catch (e: SocketTimeoutException) {
                        continue
                    } catch (e: Exception) {
                        if (isRunning) {
                            Log.e("SimpleTftpServer", "Receive thread loop error", e)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    listener.onLog("TFTP Server Error: ${e.message}")
                }
            } finally {
                stop()
            }
        }.apply {
            name = "TftpServerMainThread"
            start()
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        try {
            socket?.close()
        } catch (e: Exception) {
            Log.e("SimpleTftpServer", "Error closing TFTP server socket", e)
        }
        socket = null

        for (thread in activeTransfers.values) {
            try {
                thread.interrupt()
            } catch (ignored: Exception) {}
        }
        activeTransfers.clear()
        listener.onLog("TFTP Server stopped.")
    }

    private fun handleIncomingRequest(packet: DatagramPacket) {
        val data = packet.data
        val length = packet.length
        if (length < 4) return // Invalid packet minimum size

        val opcode = ((data[0].toInt() and 0xff) shl 8) or (data[1].toInt() and 0xff)
        val clientAddress = packet.address
        val clientPort = packet.port
        val clientKey = "$clientAddress:$clientPort"

        if (activeTransfers.containsKey(clientKey)) {
            // Already transferring with this client socket
            return
        }

        // Parse filename and mode (null-terminated strings)
        var fnEnd = 2
        while (fnEnd < length && data[fnEnd] != 0.toByte()) {
            fnEnd++
        }
        if (fnEnd >= length) return
        val filename = String(data, 2, fnEnd - 2, Charsets.UTF_8).replace('\\', '/')

        var modeEnd = fnEnd + 1
        while (modeEnd < length && data[modeEnd] != 0.toByte()) {
            modeEnd++
        }
        val mode = if (modeEnd <= length) {
            String(data, fnEnd + 1, modeEnd - (fnEnd + 1), Charsets.US_ASCII).lowercase()
        } else {
            "octet"
        }

        val cleanFilename = File(filename).name // Sandbox protection: don't allow directory traversal
        val file = File(rootDir, cleanFilename)

        val transferThread = Thread {
            try {
                if (opcode == 1) { // RRQ (Read Request)
                    listener.onLog("TFTP Client $clientAddress requested READ file: $cleanFilename")
                    handleReadRequest(clientAddress, clientPort, file)
                } else if (opcode == 2) { // WRQ (Write Request)
                    listener.onLog("TFTP Client $clientAddress requested WRITE file: $cleanFilename")
                    handleWriteRequest(clientAddress, clientPort, file)
                }
            } catch (e: Exception) {
                listener.onLog("TFTP Transfer Error with $clientAddress: ${e.message}")
            } finally {
                activeTransfers.remove(clientKey)
            }
        }
        activeTransfers[clientKey] = transferThread
        transferThread.start()
    }

    private fun handleReadRequest(address: InetAddress, port: Int, file: File) {
        val dataSocket = DatagramSocket()
        dataSocket.soTimeout = 5000

        if (!file.exists() || !file.isFile) {
            sendError(dataSocket, address, port, 1, "File not found: ${file.name}")
            dataSocket.close()
            return
        }

        var fileInput: FileInputStream? = null
        try {
            fileInput = FileInputStream(file)
            val fileBytes = file.readBytes()
            var blockNumber = 1
            var offset = 0
            val totalBytes = fileBytes.size

            while (offset <= totalBytes) {
                val chunkSize = minOf(512, totalBytes - offset)
                val chunk = fileBytes.copyOfRange(offset, offset + chunkSize)

                // Build DATA packet
                val dataPacketBytes = ByteArray(4 + chunkSize)
                dataPacketBytes[0] = 0
                dataPacketBytes[1] = 3 // Opcode 3: DATA
                dataPacketBytes[2] = ((blockNumber shr 8) and 0xff).toByte()
                dataPacketBytes[3] = (blockNumber and 0xff).toByte()
                System.arraycopy(chunk, 0, dataPacketBytes, 4, chunkSize)

                val sendPacket = DatagramPacket(dataPacketBytes, dataPacketBytes.size, address, port)
                var acked = false
                var attempts = 0

                while (!acked && attempts < 5) {
                    try {
                        dataSocket.send(sendPacket)

                        // Wait for ACK
                        val ackBuffer = ByteArray(512)
                        val ackPacket = DatagramPacket(ackBuffer, ackBuffer.size)
                        dataSocket.receive(ackPacket)

                        val ackData = ackPacket.data
                        val ackOpcode = ((ackData[0].toInt() and 0xff) shl 8) or (ackData[1].toInt() and 0xff)
                        val ackBlock = ((ackData[2].toInt() and 0xff) shl 8) or (ackData[3].toInt() and 0xff)

                        if (ackOpcode == 4 && ackBlock == blockNumber) {
                            acked = true
                        }
                    } catch (e: SocketTimeoutException) {
                        attempts++
                    }
                }

                if (!acked) {
                    listener.onLog("TFTP READ aborted: Client ACK timeout for block $blockNumber")
                    break
                }

                offset += chunkSize
                blockNumber++
                if (chunkSize < 512) {
                    break // Last packet
                }
            }

            listener.onLog("TFTP READ Complete: Sent ${file.name} (${file.length()} bytes)")
        } catch (e: Exception) {
            Log.e("SimpleTftpServer", "Error sending TFTP data", e)
            sendError(dataSocket, address, port, 0, e.message ?: "Unknown error")
        } finally {
            try { fileInput?.close() } catch (ignored: Exception) {}
            dataSocket.close()
        }
    }

    private fun handleWriteRequest(address: InetAddress, port: Int, file: File) {
        val dataSocket = DatagramSocket()
        dataSocket.soTimeout = 5000

        var fileOutput: FileOutputStream? = null
        try {
            file.parentFile?.mkdirs()
            fileOutput = FileOutputStream(file)

            // Send ACK 0 to start write transfer
            sendAck(dataSocket, address, port, 0)

            var expectedBlockNumber = 1
            var finished = false

            while (!finished) {
                var received = false
                var attempts = 0
                val dataBuffer = ByteArray(1024)
                val dataPacket = DatagramPacket(dataBuffer, dataBuffer.size)

                while (!received && attempts < 5) {
                    try {
                        dataSocket.receive(dataPacket)
                        received = true
                    } catch (e: SocketTimeoutException) {
                        attempts++
                        // Resend ACK for previous block
                        sendAck(dataSocket, address, port, expectedBlockNumber - 1)
                    }
                }

                if (!received) {
                    listener.onLog("TFTP WRITE aborted: Connection timed out.")
                    break
                }

                val receivedData = dataPacket.data
                val length = dataPacket.length
                val opcode = ((receivedData[0].toInt() and 0xff) shl 8) or (receivedData[1].toInt() and 0xff)
                val blockNumber = ((receivedData[2].toInt() and 0xff) shl 8) or (receivedData[3].toInt() and 0xff)

                if (opcode == 3) { // DATA packet
                    if (blockNumber == expectedBlockNumber) {
                        val payloadSize = length - 4
                        if (payloadSize > 0) {
                            fileOutput.write(receivedData, 4, payloadSize)
                        }
                        sendAck(dataSocket, address, port, blockNumber)
                        expectedBlockNumber++
                        if (payloadSize < 512) {
                            finished = true // EOF
                        }
                    } else if (blockNumber == expectedBlockNumber - 1) {
                        // Duplicate packet, re-ack
                        sendAck(dataSocket, address, port, blockNumber)
                    } else {
                        // Protocol error
                        sendError(dataSocket, address, port, 4, "Incorrect block number")
                        break
                    }
                } else if (opcode == 5) {
                    // Client error
                    val errMsg = if (length > 4) String(receivedData, 4, length - 5) else ""
                    listener.onLog("TFTP client sent error: $errMsg")
                    break
                }
            }

            fileOutput.flush()
            listener.onLog("TFTP WRITE Complete: Received ${file.name} (${file.length()} bytes)")
        } catch (e: Exception) {
            Log.e("SimpleTftpServer", "Error receiving TFTP data", e)
            sendError(dataSocket, address, port, 0, e.message ?: "Unknown error")
        } finally {
            try { fileOutput?.close() } catch (ignored: Exception) {}
            dataSocket.close()
        }
    }

    private fun sendAck(socket: DatagramSocket, address: InetAddress, port: Int, blockNumber: Int) {
        val ackBytes = ByteArray(4)
        ackBytes[0] = 0
        ackBytes[1] = 4 // Opcode 4: ACK
        ackBytes[2] = ((blockNumber shr 8) and 0xff).toByte()
        ackBytes[3] = (blockNumber and 0xff).toByte()
        val packet = DatagramPacket(ackBytes, ackBytes.size, address, port)
        socket.send(packet)
    }

    private fun sendError(socket: DatagramSocket, address: InetAddress, port: Int, errCode: Int, message: String) {
        val msgBytes = message.toByteArray(Charsets.UTF_8)
        val errBytes = ByteArray(4 + msgBytes.size + 1)
        errBytes[0] = 0
        errBytes[1] = 5 // Opcode 5: ERROR
        errBytes[2] = ((errCode shr 8) and 0xff).toByte()
        errBytes[3] = (errCode and 0xff).toByte()
        System.arraycopy(msgBytes, 0, errBytes, 4, msgBytes.size)
        errBytes[errBytes.size - 1] = 0 // null terminated
        val packet = DatagramPacket(errBytes, errBytes.size, address, port)
        try {
            socket.send(packet)
        } catch (ignored: Exception) {}
    }
}
