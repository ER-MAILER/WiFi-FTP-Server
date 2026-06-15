package com.example

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.AppDatabase
import com.example.data.FtpConfigEntity
import com.example.data.FtpConfigRepository
import com.example.ui.components.QrCodeView
import com.example.ui.theme.*
import com.example.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val db by lazy { AppDatabase.getDatabase(applicationContext) }
    private val repository by lazy { FtpConfigRepository(db.ftpConfigDao()) }
    private val viewModel: MainViewModel by viewModels {
        MainViewModel.Factory(repository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    FtpServerDashboard(
                        viewModel = viewModel,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FtpServerDashboard(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isRunning by viewModel.isRunning.collectAsStateWithLifecycle()
    val ipAddress by viewModel.ipAddress.collectAsStateWithLifecycle()
    val port by viewModel.activePort.collectAsStateWithLifecycle()
    val clientCount by viewModel.clientCount.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val savedConfig by viewModel.savedConfig.collectAsStateWithLifecycle()

    var showSettingsDialog by remember { mutableStateOf(false) }
    var isWifiConnectedState by remember { mutableStateOf(isWifiConnected(context)) }

    // Periodically update connectivity check
    LaunchedEffect(isRunning) {
        isWifiConnectedState = isWifiConnected(context)
    }

    // Native permissions launcher
    val requestPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val writeGranted = results[Manifest.permission.WRITE_EXTERNAL_STORAGE] ?: false
        if (writeGranted) {
            Toast.makeText(context, "Storage permissions granted.", Toast.LENGTH_SHORT).show()
        }
    }

    val manageAllFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                Toast.makeText(context, "All files access granted.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Helper to check and request storage permissions
    fun requestStoragePermissionAndSave(newConfig: FtpConfigEntity) {
        if (newConfig.rootDirType == "INTERNAL") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        manageAllFilesLauncher.launch(intent)
                    } catch (e: Exception) {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        manageAllFilesLauncher.launch(intent)
                    }
                    viewModel.updateConfig(newConfig)
                    return
                }
            } else {
                val hasPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED

                if (!hasPermission) {
                    requestPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        )
                    )
                    viewModel.updateConfig(newConfig)
                    return
                }
            }
        }
        viewModel.updateConfig(newConfig)
    }

    fun hasRequiredStoragePermission(): Boolean {
        if (savedConfig.rootDirType == "INTERNAL") {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        return true
    }

    fun requestStoragePermissionForStart(onGranted: () -> Unit) {
        if (savedConfig.rootDirType == "INTERNAL") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        manageAllFilesLauncher.launch(intent)
                    } catch (e: Exception) {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        manageAllFilesLauncher.launch(intent)
                    }
                    return
                }
            } else {
                val hasPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED

                if (!hasPermission) {
                    requestPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        )
                    )
                    return
                }
            }
        }
        onGranted()
    }

    val ftpUrl = if (ipAddress != "127.0.0.1" && ipAddress.isNotEmpty()) {
        "ftp://$ipAddress:$port"
    } else {
        "ftp://192.168.1.100:$port"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SleekBackground)
    ) {
        // App bar styled per specifications (No background bar, custom inline padding)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "WiFi FTP Server",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 26.sp,
                    letterSpacing = (-0.5).sp,
                    color = SleekTextDark
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Service is",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = SleekTextMedium
                    )
                    Text(
                        text = if (isRunning) "active" else "inactive",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isRunning) SleekGreenAccent else SleekRedAccent
                    )
                }
            }

            // High-fidelity settings toggle conforming to prompt spec
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(SleekBlueLight)
                    .clickable { showSettingsDialog = true }
                    .testTag("settings_button"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Open Settings",
                    tint = SleekTextDark,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Connectivity warning or status banner
            if (!isWifiConnectedState) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SleekRedAccent.copy(alpha = 0.1f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Wifi disconnected",
                            tint = SleekRedAccent
                        )
                        Column {
                            Text(
                                "No Active WiFi Detected",
                                fontWeight = FontWeight.Bold,
                                color = SleekRedAccent,
                                fontSize = 14.sp
                            )
                            Text(
                                "Connect WiFi to share and access files with PCs/clients.",
                                color = SleekTextMedium,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            } else {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SleekGreenAccent.copy(alpha = 0.1f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Wifi active",
                            tint = SleekGreenAccent
                        )
                        Column {
                            Text(
                                "WiFi Ready",
                                fontWeight = FontWeight.Bold,
                                color = SleekGreenAccent,
                                fontSize = 14.sp
                            )
                            Text(
                                "Your phone is connected to the network.",
                                color = SleekTextMedium,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // Big Start / Stop toggle Button styled elegantly as standard rounded fill button
            Button(
                onClick = {
                    if (isRunning) {
                        viewModel.startStopServer(context)
                    } else {
                        if (hasRequiredStoragePermission()) {
                            viewModel.startStopServer(context)
                        } else {
                            requestStoragePermissionForStart {
                                viewModel.startStopServer(context)
                            }
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) SleekRedAccent else SleekBlueAccent,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(32.dp),
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .height(60.dp)
                    .testTag("start_stop_button"),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = if (isRunning) Icons.Default.Close else Icons.Default.PlayArrow,
                        contentDescription = if (isRunning) "Stop Server" else "Start Server",
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = if (isRunning) "STOP SERVER" else "START SERVER",
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        fontSize = 16.sp
                    )
                }
            }

            // Aspect-Square Aspect ratio QR code representation (Visible only when server is running)
            AnimatedVisibility(
                visible = isRunning,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.95f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(48.dp))
                        .background(SleekLightGray)
                        .border(
                            width = 2.dp,
                            color = SleekBorderDashed,
                            shape = RoundedCornerShape(48.dp)
                        )
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(24.dp))
                                .background(Color.White)
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            QrCodeView(
                                content = ftpUrl,
                                modifier = Modifier.size(200.dp),
                                qrColor = SleekTextDark,
                                backgroundColor = Color.White
                            )
                        }
                        Text(
                            text = "Scan this QR code from your PC to connect instantly",
                            fontSize = 13.sp,
                            color = SleekTextMedium,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            lineHeight = 17.sp,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                }
            }

            // Server Address component styled beautifully
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ftp_url_panel"),
                colors = CardDefaults.cardColors(containerColor = SleekInnerGray),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "SERVER ADDRESS (Tap to copy)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = SleekTextMedium,
                        letterSpacing = 1.5.sp
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable {
                                if (isRunning) {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("FTP Link", ftpUrl)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "FTP Link copied!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Start the server first to copy the address.", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (isRunning) ftpUrl else "ftp://---.---.---.---:----",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isRunning) SleekBlueAccent else SleekTextMedium.copy(alpha = 0.6f),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("ftp_address_text"),
                            textAlign = TextAlign.Center
                        )

                        IconButton(
                            onClick = {
                                if (isRunning) {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("FTP Link", ftpUrl)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "FTP Link copied!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Start the server first to copy the address.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = isRunning,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (isRunning) SleekBlueLight else Color.Transparent)
                                .testTag("copy_link_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Copy FTP Link",
                                tint = if (isRunning) SleekBlueAccent else SleekTextMedium.copy(alpha = 0.4f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // Parameters 2x2 Grid (Using Rows of clean widgets)
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Port Card
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(SleekLightGray)
                            .padding(16.dp)
                    ) {
                        Column {
                            Text(
                                text = "PORT",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = SleekTextSecondary,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = port.toString(),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = SleekTextDark
                            )
                        }
                    }

                    // Security Card
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(SleekLightGray)
                            .padding(16.dp)
                    ) {
                        Column {
                            Text(
                                text = "SECURITY",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = SleekTextSecondary,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (savedConfig.anonymous) "Anonymous" else "Secure Login",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = SleekTextDark
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Shared Root Card
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(SleekLightGray)
                            .padding(16.dp)
                    ) {
                        Column {
                            Text(
                                text = "SHARED PATH",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = SleekTextSecondary,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (savedConfig.rootDirType == "SANDBOX") "App Sandbox" else "Shared Root",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = SleekTextDark
                            )
                        }
                    }

                    // Connected Clients Card
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(SleekLightGray)
                            .padding(16.dp)
                    ) {
                        Column {
                            Text(
                                text = "CLIENT CONNECTIONS",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = SleekTextSecondary,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isRunning) "$clientCount active" else "0 active",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isRunning && clientCount > 0) SleekGreenAccent else SleekTextDark
                            )
                        }
                    }
                }
            }

        }
    }

    // Settings AlertDialog
    if (showSettingsDialog) {
        var portInput by remember { mutableStateOf(savedConfig.port.toString()) }
        var isAnonymousState by remember { mutableStateOf(savedConfig.anonymous) }
        var usernameInput by remember { mutableStateOf(savedConfig.username) }
        var passwordInput by remember { mutableStateOf(savedConfig.password) }
        var isSandboxStorage by remember { mutableStateOf(savedConfig.rootDirType == "SANDBOX") }
        var passwordVisible by remember { mutableStateOf(false) }

        var portError by remember { mutableStateOf<String?>(null) }
        var usernameError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            containerColor = SleekBackground,
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Security Settings",
                        tint = SleekBlueAccent,
                        modifier = Modifier.size(20.dp)
                    )
                    Text("Server Configuration", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = SleekTextDark)
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Port input
                    OutlinedTextField(
                        value = portInput,
                        onValueChange = {
                            portInput = it
                            val value = it.toIntOrNull()
                            portError = if (value == null || value < 1024 || value > 65535) {
                                "Must be a valid port (1024 - 65535)"
                            } else {
                                null
                            }
                        },
                        label = { Text("FTP Server Port") },
                        isError = portError != null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().testTag("port_input_field")
                    )
                    portError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 9.sp)
                    }

                    // Storage Folder Option
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Shared Root Folder",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = SleekTextMedium
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = isSandboxStorage,
                                onClick = { isSandboxStorage = true },
                                modifier = Modifier.testTag("radio_sandbox")
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("App Isolated Storage", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = SleekTextDark)
                                Text("Private storage. No permissions needed.", fontSize = 9.sp, color = SleekTextSecondary)
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = !isSandboxStorage,
                                onClick = { isSandboxStorage = false },
                                modifier = Modifier.testTag("radio_internal")
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Shared Device Storage (/sdcard)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = SleekTextDark)
                                Text("Browse client files dynamically on phone.", fontSize = 9.sp, color = SleekTextSecondary)
                            }
                        }
                    }

                    HorizontalDivider(color = SleekBorderDashed.copy(alpha = 0.5f))

                    // Anonymous login Switch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Anonymous Login (No Password)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = SleekTextDark)
                            Text("Allows client access without credentials", fontSize = 9.sp, color = SleekTextSecondary)
                        }
                        Switch(
                            checked = isAnonymousState,
                            onCheckedChange = { isAnonymousState = it },
                            modifier = Modifier.testTag("anonymous_login_switch")
                        )
                    }

                    // Credentials input (Active when Anonymous is disabled)
                    AnimatedVisibility(visible = !isAnonymousState) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = usernameInput,
                                onValueChange = {
                                    usernameInput = it
                                    usernameError = if (it.isEmpty()) "Username cannot be empty" else null
                                },
                                label = { Text("Username") },
                                isError = usernameError != null,
                                singleLine = true,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth().testTag("username_input_field")
                            )
                            usernameError?.let {
                                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 9.sp)
                            }

                            OutlinedTextField(
                                value = passwordInput,
                                onValueChange = { passwordInput = it },
                                label = { Text("Password") },
                                singleLine = true,
                                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                        Text(
                                            text = if (passwordVisible) "HIDE" else "SHOW",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = SleekBlueAccent,
                                            modifier = Modifier.padding(end = 8.dp)
                                        )
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth().testTag("password_input_field")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    HorizontalDivider(color = SleekBorderDashed.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(4.dp))

                    // About Developer Card (Compact, light, and sleek)
                    Card(
                        modifier = Modifier.fillMaxWidth().testTag("about_developer_card"),
                        colors = CardDefaults.cardColors(containerColor = SleekLightGray),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, SleekBorderDashed.copy(alpha = 0.4f))
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = "About Creator",
                                    tint = SleekBlueAccent,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "ER DATAHUB",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = SleekTextDark
                                )
                            }
                            
                            Text(
                                text = "Our company ER DATAHUB designs and creates various types of applications. We also provide many other digital services. Please contact us to build your customized application.",
                                fontSize = 10.sp,
                                color = SleekTextMedium,
                                lineHeight = 14.sp
                            )
                            
                            HorizontalDivider(color = SleekBorderDashed.copy(alpha = 0.3f))
                            
                            // Contact Items
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Call,
                                        contentDescription = "WhatsApp",
                                        tint = SleekBlueAccent,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = "WhatsApp: 01940841273",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Normal,
                                        color = SleekTextMedium
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Email,
                                        contentDescription = "Email",
                                        tint = SleekBlueAccent,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = "Email: er.mailer2@yahoo.com",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Normal,
                                        color = SleekTextMedium
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = "TikTok",
                                        tint = SleekBlueAccent,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = "TikTok: @ernobab",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Normal,
                                        color = SleekTextMedium
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Send,
                                        contentDescription = "Telegram",
                                        tint = SleekBlueAccent,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = "Telegram: @ER_Mailer",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Normal,
                                        color = SleekTextMedium
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsedPort = portInput.toIntOrNull() ?: 2121
                        if (portError != null || (!isAnonymousState && usernameInput.isEmpty())) {
                            Toast.makeText(context, "Please fix errors before saving.", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        val updatedConfig = FtpConfigEntity(
                            id = 1,
                            port = parsedPort,
                            anonymous = isAnonymousState,
                            username = usernameInput,
                            password = passwordInput,
                            rootDirType = if (isSandboxStorage) "SANDBOX" else "INTERNAL"
                        )

                        requestStoragePermissionAndSave(updatedConfig)
                        showSettingsDialog = false
                        Toast.makeText(context, "Configurations saved. Restart server to apply changes.", Toast.LENGTH_LONG).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SleekBlueAccent),
                    modifier = Modifier.testTag("settings_save_button")
                ) {
                    Text("SAVE")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showSettingsDialog = false },
                    modifier = Modifier.testTag("settings_cancel_button")
                ) {
                    Text("CANCEL", color = SleekBlueAccent)
                }
            }
        )
    }
}

private fun isWifiConnected(context: Context): Boolean {
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = connectivityManager.activeNetwork
    if (network != null) {
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        if (capabilities != null) {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                return true
            }
        }
    }
    
    // Fallback: Check if we have any active WiFi or Hotspot/AP or USB Tethering interface with a non-loopback IP address
    try {
        val interfaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
        for (netInterface in interfaces) {
            if (!netInterface.isUp || netInterface.isLoopback) continue
            val name = netInterface.name.lowercase(java.util.Locale.US)
            if (name.contains("wlan") || name.contains("ap") || name.contains("softap") || name.contains("eth") || name.contains("rndis")) {
                val addresses = java.util.Collections.list(netInterface.inetAddresses)
                for (addr in addresses) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return true
                    }
                }
            }
        }
    } catch (e: Exception) {
        // Safe ignore
    }
    return false
}
