package com.example.server

import android.util.Log
import java.io.*
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class SimpleFtpServer(
    private val port: Int,
    private val isAnonymous: Boolean,
    private val authUser: String,
    private val authPass: String,
    private val rootDir: File,
    private val localIpAddress: String,
    private val listener: FtpServerListener
) {
    interface FtpServerListener {
        fun onLog(message: String)
        fun onClientCountChanged(count: Int)
    }

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val activeClients = ConcurrentHashMap<String, ClientSession>()
    private val clientThreads = CopyOnWriteArrayList<Thread>()

    fun start() {
        if (isRunning) return
        isRunning = true
        Thread {
            try {
                serverSocket = ServerSocket(port)
                listener.onLog("Server started on ftp://$localIpAddress:$port")
                listener.onLog("Root directory: ${rootDir.absolutePath}")

                while (isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    val clientIp = clientSocket.inetAddress.hostAddress ?: "unknown"
                    val clientThread = Thread {
                        handleClient(clientSocket, clientIp)
                    }
                    clientThreads.add(clientThread)
                    clientThread.start()
                }
            } catch (e: Exception) {
                if (isRunning) {
                    listener.onLog("Server Error: ${e.message}")
                }
            } finally {
                stop()
            }
        }.apply {
            name = "FtpServerMainThread"
            start()
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e("SimpleFtpServer", "Error closing server socket", e)
        }
        serverSocket = null

        // Close all active clients
        for (client in activeClients.values) {
            client.close()
        }
        activeClients.clear()
        listener.onClientCountChanged(0)

        // Interrupt threads
        for (thread in clientThreads) {
            try {
                thread.interrupt()
            } catch (e: Exception) {
                Log.e("SimpleFtpServer", "Error interrupting client thread", e)
            }
        }
        clientThreads.clear()
        listener.onLog("Server stopped.")
    }

    private fun handleClient(socket: Socket, clientIp: String) {
        val session = ClientSession(socket, clientIp)
        activeClients[clientIp] = session
        listener.onClientCountChanged(activeClients.size)
        listener.onLog("Client connected from: $clientIp")

        try {
            session.run()
        } catch (e: Exception) {
            Log.e("SimpleFtpServer", "Session handling generated an error", e)
        } finally {
            activeClients.remove(clientIp)
            listener.onClientCountChanged(activeClients.size)
            listener.onLog("Client disconnected: $clientIp")
            session.close()
        }
    }

    inner class ClientSession(private val socket: Socket, private val clientIp: String) {
        private val reader = BufferedReader(InputStreamReader(socket.getInputStream(), "UTF-8"))
        private val writer = BufferedOutputStream(socket.getOutputStream())
        
        private var isAuthed = isAnonymous
        private var userEntered: String? = null
        private var currentPath = "/" // Relative to rootDir
        
        private var passiveServerSocket: ServerSocket? = null
        private var passiveDataSocket: Socket? = null

        fun run() {
            sendResponse("220 Welcome to WiFi FTP Server")
            while (isRunning) {
                val line = reader.readLine() ?: break
                val parts = line.split(" ", limit = 2)
                val command = parts[0].uppercase(Locale.US)
                val args = if (parts.size > 1) parts[1].trim() else ""

                logClientCommand(command, args)

                try {
                    when (command) {
                        "USER" -> handleUser(args)
                        "PASS" -> handlePass(args)
                        "SYST" -> sendResponse("215 UNIX Type: L8")
                        "FEAT" -> {
                            val featResponse = StringBuilder()
                            featResponse.append("211-Features:\r\n")
                            featResponse.append(" UTF8\r\n")
                            featResponse.append(" SIZE\r\n")
                            featResponse.append(" MDTM\r\n")
                            featResponse.append("211 End")
                            sendResponse(featResponse.toString())
                        }
                        "OPTS" -> {
                            if (args.uppercase(Locale.US).startsWith("UTF8 ON")) {
                                sendResponse("200 UTF8 Option enabled")
                            } else {
                                sendResponse("200 Option recognized")
                            }
                        }
                        "PWD" -> sendResponse("257 \"$currentPath\" is current directory")
                        "TYPE" -> sendResponse("200 Type set to $args")
                        "PASV" -> handlePasv()
                        "CWD" -> handleCwd(args)
                        "CDUP" -> handleCwd("..")
                        "LIST", "NLST" -> handleList(command == "NLST", args)
                        "RETR" -> handleRetr(args)
                        "STOR" -> handleStor(args)
                        "MKD" -> handleMkd(args)
                        "RMD" -> handleRmd(args)
                        "DELE" -> handleDele(args)
                        "SIZE" -> handleSize(args)
                        "MDTM" -> handleMdtm(args)
                        "NOOP" -> sendResponse("200 OK")
                        "QUIT" -> {
                            sendResponse("221 Goodbye.")
                            break
                        }
                        else -> sendResponse("500 Command not recognized: $command")
                    }
                } catch (e: Exception) {
                    listener.onLog("Error handling $command: ${e.message}")
                    sendResponse("550 Task failed: ${e.message}")
                    closePassive()
                }
            }
        }

        private fun logClientCommand(command: String, args: String) {
            val displayArgs = if (command == "PASS") "****" else args
            Log.d("SimpleFtpServer", "[$clientIp] Client sent: $command $displayArgs")
        }

        private fun sendResponse(response: String) {
            try {
                val bytes = (response + "\r\n").toByteArray(Charsets.UTF_8)
                writer.write(bytes)
                writer.flush()
            } catch (e: Exception) {
                Log.e("SimpleFtpServer", "Error sending response to client", e)
            }
        }

        private fun handleUser(username: String) {
            userEntered = username
            if (isAnonymous || username.lowercase(Locale.US) == "anonymous") {
                isAuthed = true
                sendResponse("230 Anonymous user logged in.")
                listener.onLog("[$clientIp] Logged in as anonymous.")
            } else {
                isAuthed = false
                sendResponse("331 Username ok, need password.")
            }
        }

        private fun handlePass(password: String) {
            if (isAuthed) {
                sendResponse("230 Password redundant.")
                return
            }

            val user = userEntered ?: ""
            if (user == authUser && password == authPass) {
                isAuthed = true
                sendResponse("230 User logged in, proceed.")
                listener.onLog("[$clientIp] Logged in as '$authUser'.")
            } else {
                sendResponse("530 Security error: invalid username or password.")
                listener.onLog("[$clientIp] Login attempt failed for user: $user")
            }
        }

        private fun checkAuth(): Boolean {
            if (!isAuthed) {
                sendResponse("530 Please login first.")
                return false
            }
            return true
        }

        private fun handlePasv() {
            if (!checkAuth()) return
            
            closePassive()

            try {
                val tempServer = ServerSocket(0)
                tempServer.soTimeout = 10000 // 10 seconds timeout for client to connect
                passiveServerSocket = tempServer
                
                val pLocalPort = tempServer.localPort
                val p1 = pLocalPort / 256
                val p2 = pLocalPort % 256

                val ipParts = localIpAddress.split(".")
                if (ipParts.size == 4) {
                    val ipStr = ipParts.joinToString(",")
                    sendResponse("227 Entering Passive Mode ($ipStr,$p1,$p2).")
                    Log.d("SimpleFtpServer", "Passive Server opened at port $pLocalPort")
                } else {
                    sendResponse("551 Cannot calculate external IP address.")
                    closePassive()
                }
            } catch (e: Exception) {
                sendResponse("425 Can't open passive connection: ${e.message}")
                closePassive()
            }
        }

        private fun acceptPassiveConnection(): Boolean {
            val server = passiveServerSocket ?: return false
            return try {
                passiveDataSocket = server.accept()
                true
            } catch (e: SocketTimeoutException) {
                listener.onLog("Data connection timeout on host port.")
                closePassive()
                false
            } catch (e: Exception) {
                closePassive()
                false
            }
        }

        private fun closePassive() {
            try {
                passiveDataSocket?.close()
            } catch (e: Exception) {
                Log.e("SimpleFtpServer", "Error closing passiveDataSocket", e)
            }
            passiveDataSocket = null

            try {
                passiveServerSocket?.close()
            } catch (e: Exception) {
                Log.e("SimpleFtpServer", "Error closing passiveServerSocket", e)
            }
            passiveServerSocket = null
        }

        private fun resolveFile(pathStr: String): File {
            var inputPath = pathStr
            if (!inputPath.startsWith("/")) {
                // Relative path
                inputPath = if (currentPath.endsWith("/")) {
                    currentPath + inputPath
                } else {
                    "$currentPath/$inputPath"
                }
            }

            // Clean path
            val normalizedParts = Stack<String>()
            val parts = inputPath.split("/")
            for (part in parts) {
                if (part.isEmpty() || part == ".") continue
                if (part == "..") {
                    if (normalizedParts.isNotEmpty()) {
                        normalizedParts.pop()
                    }
                } else {
                    normalizedParts.push(part)
                }
            }

            val relativeResolved = normalizedParts.joinToString("/", prefix = "/")
            val absoluteFile = File(rootDir, relativeResolved.substring(1))

            // Protect against directory traversal attacks (ensure resolved file is inside rootDir or is rootDir itself)
            val rootCanonical = rootDir.canonicalFile
            val fileCanonical = absoluteFile.canonicalFile
            
            var parent: File? = fileCanonical
            var isInside = false
            while (parent != null) {
                if (parent == rootCanonical) {
                    isInside = true
                    break
                }
                parent = parent.parentFile
            }
            return if (isInside) absoluteFile else rootDir
        }

        private fun getRelativePath(file: File): String {
            val rootCanonical = rootDir.canonicalFile
            val fileCanonical = file.canonicalFile
            
            var parent: File? = fileCanonical
            var isInside = false
            while (parent != null) {
                if (parent == rootCanonical) {
                    isInside = true
                    break
                }
                parent = parent.parentFile
            }
            if (!isInside) return "/"
            
            val rootPath = rootCanonical.absolutePath
            val filePath = fileCanonical.absolutePath
            
            if (filePath == rootPath) return "/"
            
            val relative = if (filePath.startsWith(rootPath)) {
                filePath.substring(rootPath.length)
            } else {
                "/"
            }
            return if (relative.isEmpty()) "/" else relative.replace("\\", "/")
        }

        private fun handleCwd(dir: String) {
            if (!checkAuth()) return
            val targetFolder = resolveFile(dir)
            if (targetFolder.exists() && targetFolder.isDirectory) {
                currentPath = getRelativePath(targetFolder)
                sendResponse("250 Directory change successful.")
                listener.onLog("[$clientIp] Directory changed to $currentPath")
            } else {
                sendResponse("550 Directory inside FTP root not found.")
            }
        }

        private fun handleList(nameOnly: Boolean, listArg: String = "") {
            if (!checkAuth()) return
            
            // Some clients send CWD & PWD, some send LIST with direct path, some send LIST -la /path
            // We strip leading options from listArg (e.g., "-la", "-a")
            val cleanArg = if (listArg.startsWith("-")) {
                val lastSpace = listArg.lastIndexOf(" ")
                if (lastSpace != -1 && lastSpace < listArg.length - 1) {
                    listArg.substring(lastSpace + 1).trim()
                } else {
                    ""
                }
            } else {
                listArg
            }

            val targetFolder = if (cleanArg.isNotEmpty()) {
                resolveFile(cleanArg)
            } else {
                resolveFile(currentPath)
            }
            
            if (!acceptPassiveConnection()) {
                sendResponse("425 Can't build passive connection.")
                return
            }

            sendResponse("150 Opening ASCII mode data connection for directory listing.")

            val dataOutput = passiveDataSocket?.getOutputStream()
            if (dataOutput == null) {
                sendResponse("426 Connection lost.")
                closePassive()
                return
            }

            val outWriter = BufferedWriter(OutputStreamWriter(dataOutput, "UTF-8"))
            try {
                val files = targetFolder.listFiles()
                if (files == null) {
                    listener.onLog("Access Denied: Cannot read directory ${targetFolder.absolutePath}. Please check Android Storage Permissions.")
                }
                val safeFiles = files ?: emptyArray()
                val dateFormat = SimpleDateFormat("MMM dd HH:mm", Locale.US)
                
                for (file in safeFiles) {
                    if (nameOnly) {
                        outWriter.write(file.name + "\r\n")
                    } else {
                        val isDir = file.isDirectory
                        val typeChar = if (isDir) "d" else "-"
                        val perms = if (isDir) "rwxr-xr-x" else "rw-r--r--"
                        val size = if (isDir) 0 else file.length()
                        val formattedDate = dateFormat.format(Date(file.lastModified()))
                        
                        val line = String.format(
                             Locale.US,
                             "%s%s   1 ftp      ftp      %10d %s %s\r\n",
                             typeChar, perms, size, formattedDate, file.name
                        )
                        outWriter.write(line)
                    }
                }
                outWriter.flush()
                sendResponse("226 Directory send OK.")
                listener.onLog("[$clientIp] Listed directory ${getRelativePath(targetFolder)}")
            } catch (e: Exception) {
                sendResponse("451 Local transmission error.")
                listener.onLog("[$clientIp] Error listing directory: ${e.message}")
            } finally {
                closePassive()
            }
        }

        private fun handleRetr(fileName: String) {
            if (!checkAuth()) return
            val file = resolveFile(fileName)
            if (!file.exists() || !file.isFile) {
                sendResponse("550 File does not exist or is not a file.")
                closePassive()
                return
            }

            if (!acceptPassiveConnection()) {
                sendResponse("425 Can't verify passive connection.")
                return
            }

            sendResponse("150 Opening BINARY mode data connection for $fileName (${file.length()} bytes).")
            
            val dataOutput = passiveDataSocket?.getOutputStream()
            if (dataOutput == null) {
                sendResponse("426 Cannot establish data channel.")
                closePassive()
                return
            }

            var fileInput: FileInputStream? = null
            try {
                fileInput = FileInputStream(file)
                val buffer = ByteArray(32768)
                var bytesRead: Int
                while (fileInput.read(buffer).also { bytesRead = it } != -1) {
                    dataOutput.write(buffer, 0, bytesRead)
                }
                dataOutput.flush()
                sendResponse("226 Transfer complete.")
                listener.onLog("[$clientIp] Downloaded: ${file.name} (${file.length()} bytes)")
            } catch (e: Exception) {
                sendResponse("451 Transfer aborted due to network or filesystem error.")
                listener.onLog("[$clientIp] Download failed for metadata ${file.name}: ${e.message}")
            } finally {
                try { fileInput?.close() } catch (ignored: Exception) {}
                closePassive()
            }
        }

        private fun handleStor(fileName: String) {
            if (!checkAuth()) return
            val file = resolveFile(fileName)

            // Ensure parent directory exists
            file.parentFile?.mkdirs()

            if (!acceptPassiveConnection()) {
                sendResponse("425 Connection failed: passive channel not established.")
                return
            }

            sendResponse("150 Ok to send data.")

            val dataInput = passiveDataSocket?.getInputStream()
            if (dataInput == null) {
                sendResponse("426 Data channel inaccessible.")
                closePassive()
                return
            }

            var fileOutput: FileOutputStream? = null
            try {
                fileOutput = FileOutputStream(file)
                val buffer = ByteArray(32768)
                var bytesRead: Int
                while (dataInput.read(buffer).also { bytesRead = it } != -1) {
                    fileOutput.write(buffer, 0, bytesRead)
                }
                fileOutput.flush()
                sendResponse("226 Transfer complete.")
                listener.onLog("[$clientIp] Uploaded: ${file.name} (${file.length()} bytes)")
            } catch (e: Exception) {
                sendResponse("451 File upload failed locally.")
                listener.onLog("[$clientIp] Upload failed for filename ${file.name}: ${e.message}")
            } finally {
                try { fileOutput?.close() } catch (ignored: Exception) {}
                closePassive()
            }
        }

        private fun handleMkd(dirName: String) {
            if (!checkAuth()) return
            val targetDir = resolveFile(dirName)
            if (targetDir.exists()) {
                sendResponse("550 Directory or file already exists.")
                return
            }

            if (targetDir.mkdirs()) {
                sendResponse("257 \"$dirName\" directory created.")
                listener.onLog("[$clientIp] Created directory: $dirName")
            } else {
                sendResponse("550 Create directory failed.")
            }
        }

        private fun handleRmd(dirName: String) {
            if (!checkAuth()) return
            val targetDir = resolveFile(dirName)
            if (!targetDir.exists() || !targetDir.isDirectory) {
                sendResponse("550 Directory not found.")
                return
            }

            if (targetDir.delete()) {
                sendResponse("250 Directory deleted.")
                listener.onLog("[$clientIp] Deleted directory: $dirName")
            } else {
                sendResponse("550 Directory is not empty or can't be deleted.")
            }
        }

        private fun handleDele(fileName: String) {
            if (!checkAuth()) return
            val targetFile = resolveFile(fileName)
            if (!targetFile.exists() || !targetFile.isFile) {
                sendResponse("550 File not found.")
                return
            }

            if (targetFile.delete()) {
                sendResponse("250 File deleted successfully.")
                listener.onLog("[$clientIp] Deleted file: $fileName")
            } else {
                sendResponse("550 File could not be deleted.")
            }
        }

        private fun handleSize(fileName: String) {
            if (!checkAuth()) return
            val file = resolveFile(fileName)
            if (file.exists() && file.isFile) {
                sendResponse("213 ${file.length()}")
            } else {
                sendResponse("550 File not found.")
            }
        }

        private fun handleMdtm(fileName: String) {
            if (!checkAuth()) return
            val file = resolveFile(fileName)
            if (file.exists()) {
                val dateFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
                dateFormat.timeZone = TimeZone.getTimeZone("GMT")
                sendResponse("213 ${dateFormat.format(Date(file.lastModified()))}")
            } else {
                sendResponse("550 File not found.")
            }
        }

        fun close() {
            closePassive()
            try {
                socket.close()
            } catch (e: Exception) {
                Log.e("SimpleFtpServer", "Error closing client socket", e)
            }
        }
    }
}
