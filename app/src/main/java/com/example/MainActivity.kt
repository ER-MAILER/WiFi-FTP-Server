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
import android.media.projection.MediaProjectionManager
import android.app.Activity
import com.example.server.ScreenMirroringState
import com.example.server.ScreenMirroringService
import com.example.server.RemoteControlAccessibilityService
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.AppDatabase
import com.example.data.FtpConfigEntity
import com.example.data.FtpConfigRepository
import com.example.data.RemoteServerEntity
import com.example.server.RemoteClient
import com.example.server.RemoteFileItem
import com.example.ui.theme.*
import com.example.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.io.FileOutputStream
import java.util.*

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
                    MainNavigationDashboard(
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
fun MainNavigationDashboard(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("HOST SERVER", "REMOTE CONNECT")

    val isFtpServerRunning by viewModel.isRunning.collectAsStateWithLifecycle()
    val isScreenMirroringRunning by ScreenMirroringState.isRunning.collectAsStateWithLifecycle()

    val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    val startMediaProjectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(context, ScreenMirroringService::class.java).apply {
                action = ScreenMirroringService.ACTION_START
                putExtra(ScreenMirroringService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenMirroringService.EXTRA_RESULT_DATA, result.data)
            }
            ContextCompat.startForegroundService(context, intent)
        } else {
            Toast.makeText(context, "Permission denied for Screen Sharing.", Toast.LENGTH_SHORT).show()
        }
    }

    // Track state to prevent launch loops and redundant prompts
    var lastFtpRunningByPrompt by remember { mutableStateOf(false) }

    // Auto-trigger screen sharing when Host Server starts
    LaunchedEffect(isFtpServerRunning, isScreenMirroringRunning) {
        if (!isScreenMirroringRunning) {
            val shouldPrompt = isFtpServerRunning && !lastFtpRunningByPrompt
            
            if (shouldPrompt) {
                lastFtpRunningByPrompt = isFtpServerRunning
                try {
                    startMediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                } catch (e: Exception) {
                    Toast.makeText(context, "Error requesting screen capture: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            lastFtpRunningByPrompt = isFtpServerRunning
        }
    }

    LaunchedEffect(isFtpServerRunning) {
        if (!isFtpServerRunning) {
            lastFtpRunningByPrompt = false
            if (isScreenMirroringRunning) {
                val intent = Intent(context, ScreenMirroringService::class.java).apply {
                    action = ScreenMirroringService.ACTION_STOP
                }
                context.startService(intent)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SleekBackground)
    ) {
        // Tab indicator top heading bar
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = SleekBackground,
            contentColor = SleekBlueAccent,
            modifier = Modifier.fillMaxWidth()
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            letterSpacing = 1.sp
                        )
                    }
                )
            }
        }

        Divider(color = SleekLightGray, modifier = Modifier.height(1.dp))

        // Navigation screens
        Crossfade(targetState = selectedTab, label = "TabNavigation") { tab ->
            when (tab) {
                0 -> FtpServerDashboard(viewModel = viewModel)
                1 -> RemoteClientDashboard(viewModel = viewModel)
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

    // Native permissions launchers
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

    val scheme = savedConfig.serverProtocol.lowercase(Locale.getDefault())
    val ftpUrl = "$scheme://$ipAddress:$port"

    val lazyListState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            lazyListState.animateScrollToItem(logs.size - 1)
        }
    }

    Box(modifier = modifier
        .fillMaxSize()
        .background(SleekBackground)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header panel with start/stop option
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "WIFI SERVER MANAGER",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = SleekTextSecondary,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Host Console",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = SleekTextDark
                    )
                }

                IconButton(
                    onClick = { showSettingsDialog = true },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(SleekLightGray)
                        .testTag("settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Open Settings",
                        tint = SleekTextDark,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Connection Status Panel
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = if (isRunning) SleekBlueLight else SleekLightGray),
                shape = RoundedCornerShape(24.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = if (isRunning) "SERVER IS ACTIVE" else "SERVER STOPPED",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (isRunning) SleekBlueAccent else SleekTextMedium,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = if (isRunning) "Protocol: ${savedConfig.serverProtocol}" else "Start below to host",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SleekTextDark
                        )
                    }

                    Switch(
                        checked = isRunning,
                        onCheckedChange = { _ ->
                            if (!isWifiConnectedState && !isRunning) {
                                Toast.makeText(context, "No active WiFi/Hotspot Interface found!", Toast.LENGTH_SHORT).show()
                            }
                            requestStoragePermissionForStart {
                                viewModel.startStopServer(context)
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = SleekBlueAccent,
                            uncheckedThumbColor = SleekTextSecondary,
                            uncheckedTrackColor = SleekInnerGray
                        ),
                        modifier = Modifier.testTag("server_toggle_switch")
                    )
                }
            }

            // Server Address component
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
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "HOST SERVER ADDRESS (Tap to copy)",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = SleekTextMedium,
                        letterSpacing = 1.2.sp
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
                                    Toast.makeText(context, "Host Link copied!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Start the server first to copy the address.", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = if (isRunning) ftpUrl else "$scheme://---.---.---.---:----",
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
                                    Toast.makeText(context, "Host Link copied!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = isRunning,
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (isRunning) SleekBlueLight else Color.Transparent)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Copy Host Link",
                                tint = if (isRunning) SleekBlueAccent else SleekTextMedium.copy(alpha = 0.4f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // Parameters 2x2 Grid using clean widgets
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Port Card
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(SleekLightGray)
                            .padding(14.dp)
                    ) {
                        Column {
                            Text(
                                text = "PORT",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = SleekTextSecondary,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = port.toString(),
                                fontSize = 16.sp,
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
                            .padding(14.dp)
                    ) {
                        Column {
                            Text(
                                text = "SECURITY",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = SleekTextSecondary,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (savedConfig.anonymous) "Anonymous" else "Secure",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = SleekTextDark
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Shared Root Card
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(SleekLightGray)
                            .padding(14.dp)
                    ) {
                        Column {
                            Text(
                                text = "SHARED PATH",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = SleekTextSecondary,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (savedConfig.rootDirType == "SANDBOX") "Sandbox" else "Device Storage",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = SleekTextDark,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Connected Clients Card
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(SleekLightGray)
                            .padding(14.dp)
                    ) {
                        Column {
                            Text(
                                text = "CONNECTIONS",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = SleekTextSecondary,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isRunning) "$clientCount active" else "0 active",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isRunning && clientCount > 0) SleekGreenAccent else SleekTextDark
                            )
                        }
                    }
                }
            }
        
            ScreenMirroringStatusPanel()

            // Real-time Console Logs Terminal Box
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "REAL-TIME LIVE LOGS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = SleekTextSecondary,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Clear Logs",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = SleekBlueAccent,
                        modifier = Modifier
                            .clickable { viewModel.clearLogs() }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(SleekTextDark)
                        .padding(14.dp)
                ) {
                    if (logs.isEmpty()) {
                        Text(
                            text = "Console is empty. Start server or connect clients to stream console system activities...",
                            color = SleekTextSecondary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.align(Alignment.Center),
                            textAlign = TextAlign.Center
                        )
                    } else {
                        LazyColumn(
                            state = lazyListState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(logs) { log ->
                                Text(
                                    text = log,
                                    color = Color(0xFF6CF09F),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 15.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Settings Configuration AlertDialog
    if (showSettingsDialog) {
        var selectedProtocol by remember { mutableStateOf(savedConfig.serverProtocol) }
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
                        contentDescription = "Configuration Details",
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
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Protocol selection
                    Column {
                        Text("Active Protocol Mode", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SleekTextMedium)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            listOf("FTP", "FTPS", "SFTP", "TFTP").forEach { proto ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (selectedProtocol == proto) SleekBlueAccent else SleekLightGray)
                                        .clickable { selectedProtocol = proto }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                   ) {
                                    Text(
                                        text = proto,
                                        color = if (selectedProtocol == proto) Color.White else SleekTextDark,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

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
                        label = { Text("Server Port") },
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

                    // Column only relevant to authenticated protocols
                    if (selectedProtocol != "TFTP") {
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
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    HorizontalDivider(color = SleekBorderDashed.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(4.dp))

                    // About Developer Card
                    Card(
                        modifier = Modifier.fillMaxWidth().testTag("about_developer_card"),
                        colors = CardDefaults.cardColors(containerColor = SleekLightGray),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, SleekBorderDashed.copy(alpha = 0.4f))
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
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
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.Call, contentDescription = "WhatsApp", tint = SleekBlueAccent, modifier = Modifier.size(12.dp))
                                    Text(text = "WhatsApp: 01940841273", fontSize = 10.sp, color = SleekTextMedium)
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(imageVector = Icons.Default.Email, contentDescription = "Email", tint = SleekBlueAccent, modifier = Modifier.size(12.dp))
                                    Text(text = "Email: er.mailer2@yahoo.com", fontSize = 10.sp, color = SleekTextMedium)
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
                        if (portError != null || (selectedProtocol != "TFTP" && !isAnonymousState && usernameInput.isEmpty())) {
                            Toast.makeText(context, "Please fix errors before saving.", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        val updatedConfig = FtpConfigEntity(
                            id = 1,
                            port = parsedPort,
                            anonymous = isAnonymousState,
                            username = usernameInput,
                            password = passwordInput,
                            rootDirType = if (isSandboxStorage) "SANDBOX" else "INTERNAL",
                            serverProtocol = selectedProtocol
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteClientDashboard(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val savedServers by viewModel.remoteServers.collectAsStateWithLifecycle()

    var showAddProfileDialog by remember { mutableStateOf(false) }

    // Client connection states
    var clientConnection: RemoteClient? by remember { mutableStateOf(null) }
    var activeMirrorUrl by remember { mutableStateOf<String?>(null) }
    var explorerCurrentPath by remember { mutableStateOf("/") }
    var remoteFiles by remember { mutableStateOf<List<RemoteFileItem>>(emptyList()) }
    var isFetchingFiles by remember { mutableStateOf(false) }

    val uploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        val client = clientConnection
        if (uri != null && client != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    // Write picked file to a temporary file, then upload it!
                    val contentResolver = context.contentResolver
                    val cursor = contentResolver.query(uri, null, null, null, null)
                    var name = "uploaded_file.bin"
                    cursor?.use {
                        if (it.moveToFirst()) {
                            val nameColumnIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (nameColumnIndex != -1) {
                                name = it.getString(nameColumnIndex)
                            }
                        }
                    }

                    val tempFile = File(context.cacheDir, name)
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }

                    val success = client.uploadFile(tempFile, "/$name")
                    withContext(Dispatchers.Main) {
                        if (success) {
                            Toast.makeText(context, "Uploaded '$name' successfully!", Toast.LENGTH_SHORT).show()
                            // Refresh listing
                            isFetchingFiles = true
                            coroutineScope.launch(Dispatchers.IO) {
                                val list = client.listFiles(explorerCurrentPath)
                                withContext(Dispatchers.Main) {
                                    remoteFiles = list
                                    isFetchingFiles = false
                                }
                            }
                        } else {
                            Toast.makeText(context, "Failed to upload file structure.", Toast.LENGTH_SHORT).show()
                        }
                        tempFile.delete()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Upload failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    if (activeMirrorUrl != null) {
        RemoteScreenMirrorView(url = activeMirrorUrl!!) {
            activeMirrorUrl = null
        }
    } else if (clientConnection != null) {
        // Active Remote Explorer Overlay
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = {
                            clientConnection?.close()
                            clientConnection = null
                            remoteFiles = emptyList()
                        }
                    ) {
                        Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Disconnect")
                    }
                    Column {
                        Text(
                            text = "CONNECTED REMOTE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = SleekTextSecondary
                        )
                        Text(
                            text = clientConnection?.host ?: "Server",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = SleekTextDark
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = {
                            uploadLauncher.launch("*/*")
                        },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(SleekBlueLight)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Upload File",
                            tint = SleekBlueAccent,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            val client = clientConnection ?: return@IconButton
                            isFetchingFiles = true
                            coroutineScope.launch(Dispatchers.IO) {
                                val list = client.listFiles(explorerCurrentPath)
                                withContext(Dispatchers.Main) {
                                    remoteFiles = list
                                    isFetchingFiles = false
                                    Toast.makeText(context, "Directory list refreshed!", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(SleekLightGray)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh Directory",
                            tint = SleekTextDark,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Path Box indicators
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SleekLightGray)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(imageVector = Icons.Default.LocationOn, contentDescription = "Current path", tint = SleekBlueAccent, modifier = Modifier.size(16.dp))
                Text(
                    text = explorerCurrentPath,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = SleekTextDark,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (explorerCurrentPath != "/") {
                    Text(
                        text = "UP",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = SleekBlueAccent,
                        modifier = Modifier
                            .clickable {
                                val parent = explorerCurrentPath.substringBeforeLast('/')
                                explorerCurrentPath = if (parent.isEmpty()) "/" else parent
                                isFetchingFiles = true
                                val client = clientConnection ?: return@clickable
                                coroutineScope.launch(Dispatchers.IO) {
                                    val list = client.listFiles(explorerCurrentPath)
                                    withContext(Dispatchers.Main) {
                                        remoteFiles = list
                                        isFetchingFiles = false
                                    }
                                }
                            }
                            .padding(4.dp)
                    )
                }
            }

            // Files Listing Panel
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White)
                    .border(1.dp, SleekLightGray, RoundedCornerShape(20.dp))
            ) {
                if (isFetchingFiles) {
                    CircularProgressIndicator(
                        color = SleekBlueAccent,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else if (remoteFiles.isEmpty()) {
                    Text(
                        text = "No files found in directory.\nClick the '+' icon to upload a file into this remote server directory.",
                        color = SleekTextSecondary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(remoteFiles) { file ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (file.isDirectory) {
                                            explorerCurrentPath = file.path
                                            isFetchingFiles = true
                                            val client = clientConnection ?: return@clickable
                                            coroutineScope.launch(Dispatchers.IO) {
                                                val list = client.listFiles(file.path)
                                                withContext(Dispatchers.Main) {
                                                    remoteFiles = list
                                                    isFetchingFiles = false
                                                }
                                            }
                                        }
                                    },
                                colors = CardDefaults.cardColors(containerColor = SleekBackground),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, SleekLightGray)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                 ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = if (file.isDirectory) Icons.Default.List else Icons.Default.Info,
                                            contentDescription = if (file.isDirectory) "Directory" else "File",
                                            tint = if (file.isDirectory) Color(0xFFFFB300) else SleekBlueAccent,
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Column {
                                            Text(
                                                text = file.name,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = SleekTextDark,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (!file.isDirectory) {
                                                Text(
                                                    text = "Size: ${file.size} bytes",
                                                    fontSize = 10.sp,
                                                    color = SleekTextSecondary
                                                )
                                            }
                                        }
                                    }

                                    if (!file.isDirectory) {
                                        IconButton(
                                            onClick = {
                                                val client = clientConnection ?: return@IconButton
                                                Toast.makeText(context, "Starting download of ${file.name}...", Toast.LENGTH_SHORT).show()
                                                coroutineScope.launch(Dispatchers.IO) {
                                                    val localFilesDir = context.getExternalFilesDir(null) ?: context.filesDir
                                                    val localFile = File(localFilesDir, file.name)
                                                    val success = client.downloadFile(file.path, localFile)
                                                    withContext(Dispatchers.Main) {
                                                        if (success) {
                                                            Toast.makeText(context, "Downloaded to app sandbox:\n${localFile.absolutePath}", Toast.LENGTH_LONG).show()
                                                        } else {
                                                            Toast.makeText(context, "Download failed.", Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                }
                                            },
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(SleekBlueLight)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = "Download File",
                                                tint = SleekBlueAccent,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    } else {
        // Profiles Listing & Form Screen
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "EXPLORE EXTERNAL TARGETS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = SleekTextSecondary,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Remote Client",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = SleekTextDark
                    )
                }

                Button(
                    onClick = { showAddProfileDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = SleekBlueAccent),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = "Add Server", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Server", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            Text(
                text = "Connect to other FTP, FTPS, SFTP, or TFTP servers to browse remote files, download materials, and upload local files securely in a beautiful Explorer.",
                fontSize = 12.sp,
                color = SleekTextMedium,
                lineHeight = 16.sp
            )

            // Saved connection lists
            Text(
                text = "SAVED SERVER PROFILES",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = SleekTextSecondary,
                letterSpacing = 1.sp
            )

            if (savedServers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(SleekLightGray)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Info, contentDescription = "No Servers", tint = SleekTextSecondary, modifier = Modifier.size(38.dp))
                        Text(
                            text = "No remote profiles added yet.\nPress 'Add Server' above to save server details.",
                            fontSize = 12.sp,
                            color = SleekTextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(savedServers) { server ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = SleekLightGray),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = server.label,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SleekTextDark
                                    )
                                    Text(
                                        text = if (server.protocol == "MIRROR") "MIRROR • ${server.host}" else "${server.protocol} • ${server.host}:${server.port}",
                                        fontSize = 11.sp,
                                        color = SleekTextSecondary
                                    )
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Button(
                                        onClick = {
                                            if (server.protocol == "MIRROR") {
                                                activeMirrorUrl = if (server.host.startsWith("http://") || server.host.startsWith("https://")) {
                                                    server.host
                                                } else {
                                                    "http://${server.host}:${server.port}"
                                                }
                                                Toast.makeText(context, "Opening Remote Screen View...", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "Connecting to ${server.label}...", Toast.LENGTH_SHORT).show()
                                                isFetchingFiles = true
                                                val client = RemoteClient(
                                                    host = server.host,
                                                    port = server.port,
                                                    protocol = server.protocol,
                                                    username = server.username,
                                                    password = server.password,
                                                    anonymous = server.anonymous
                                                )
                                                clientConnection = client
                                                coroutineScope.launch(Dispatchers.IO) {
                                                    val success = client.connect()
                                                    if (success) {
                                                        val list = client.listFiles("/")
                                                        withContext(Dispatchers.Main) {
                                                            remoteFiles = list
                                                            isFetchingFiles = false
                                                            Toast.makeText(context, "Connected successfully!", Toast.LENGTH_SHORT).show()
                                                        }
                                                    } else {
                                                        withContext(Dispatchers.Main) {
                                                            clientConnection = null
                                                            isFetchingFiles = false
                                                            Toast.makeText(context, "Failed to connect. Check address, port, and credentials.", Toast.LENGTH_LONG).show()
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = SleekBlueAccent),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text("CONNECT", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }

                                    IconButton(
                                        onClick = { viewModel.deleteRemoteServer(server) },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete Server Profile", tint = SleekRedAccent)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            ScreenMirroringStatusPanel()
        }
    }

    // Add Remote Server Dialog
    if (showAddProfileDialog) {
        var labelInput by remember { mutableStateOf("") }
        var selectedClientProtocol by remember { mutableStateOf("FTP") }
        var hostInput by remember { mutableStateOf("") }
        var portInput by remember { mutableStateOf("21") }
        var isAnonymousClient by remember { mutableStateOf(false) }
        var usernameInput by remember { mutableStateOf("") }
        var passwordInput by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddProfileDialog = false },
            containerColor = SleekBackground,
            title = {
                Text("Add New Remote Profile", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = SleekTextDark)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = labelInput,
                        onValueChange = { labelInput = it },
                        label = { Text("Profile Name (e.g. My PC Server)") },
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Protocol Selection Row
                    Column {
                        Text("Protocol Type", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SleekTextMedium)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            listOf("FTP", "FTPS", "SFTP", "TFTP", "MIRROR").forEach { proto ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (selectedClientProtocol == proto) SleekBlueAccent else SleekLightGray)
                                        .clickable {
                                            selectedClientProtocol = proto
                                            portInput = when (proto) {
                                                "FTP" -> "21"
                                                "FTPS" -> "990"
                                                "SFTP" -> "22"
                                                "TFTP" -> "69"
                                                "MIRROR" -> "5050"
                                                else -> "21"
                                            }
                                        }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = proto,
                                        color = if (selectedClientProtocol == proto) Color.White else SleekTextDark,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = hostInput,
                        onValueChange = { hostInput = it },
                        label = { Text(if (selectedClientProtocol == "MIRROR") "Casting URL / Host IP" else "Host Address (IP / Domain)") },
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    AnimatedVisibility(visible = selectedClientProtocol != "MIRROR") {
                        OutlinedTextField(
                            value = portInput,
                            onValueChange = { portInput = it },
                            label = { Text("Port") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                    }

                    if (selectedClientProtocol != "TFTP" && selectedClientProtocol != "MIRROR") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Anonymous Login", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = SleekTextDark)
                            Switch(
                                checked = isAnonymousClient,
                                onCheckedChange = { isAnonymousClient = it }
                            )
                        }

                        AnimatedVisibility(visible = !isAnonymousClient) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedTextField(
                                    value = usernameInput,
                                    onValueChange = { usernameInput = it },
                                    label = { Text("Username") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                OutlinedTextField(
                                    value = passwordInput,
                                    onValueChange = { passwordInput = it },
                                    label = { Text("Password") },
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsedPort = portInput.toIntOrNull() ?: 21
                        if (labelInput.isEmpty() || hostInput.isEmpty()) {
                            Toast.makeText(context, "Please complete all fields.", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        val newProfile = RemoteServerEntity(
                            label = labelInput,
                            host = hostInput,
                            port = parsedPort,
                            protocol = selectedClientProtocol,
                            username = usernameInput,
                            password = passwordInput,
                            anonymous = isAnonymousClient
                        )

                        viewModel.saveRemoteServer(newProfile)
                        showAddProfileDialog = false
                        Toast.makeText(context, "Remote profile saved successfully!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SleekBlueAccent)
                ) {
                    Text("SAVE")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddProfileDialog = false }) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenMirroringStatusPanel() {
    val context = LocalContext.current
    val isRunning by ScreenMirroringState.isRunning.collectAsStateWithLifecycle()
    val ipAddress by ScreenMirroringState.ipAddress.collectAsStateWithLifecycle()
    val port by ScreenMirroringState.port.collectAsStateWithLifecycle()
    val clientCount by ScreenMirroringState.clientCount.collectAsStateWithLifecycle()
    val logs by ScreenMirroringState.logs.collectAsStateWithLifecycle()

    val isTunnelEnabled by ScreenMirroringState.isTunnelEnabled.collectAsStateWithLifecycle()
    val isTunnelConnected by ScreenMirroringState.isTunnelConnected.collectAsStateWithLifecycle()
    val publicUrl by ScreenMirroringState.publicUrl.collectAsStateWithLifecycle()

    var isExpanded by remember { mutableStateOf(false) }
    var isAccEnabled by remember { mutableStateOf(RemoteControlAccessibilityService.isServiceRunning) }

    // Update accessibility status on resume
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isAccEnabled = RemoteControlAccessibilityService.isServiceRunning
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val lazyListState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            lazyListState.animateScrollToItem(logs.size - 1)
        }
    }

    if (!isRunning) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, SleekInnerGray)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Cast Screen",
                        tint = SleekBlueAccent,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(
                            text = "SCREEN MIRRORING ROUTED ACTIVE",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = SleekBlueAccent,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "http://$ipAddress:$port",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = SleekTextDark
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFE8F5E9)
                    ) {
                        Text(
                            text = "$clientCount Viewers",
                            color = Color(0xFF2E7D32),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = "Expand/Collapse",
                        tint = SleekTextSecondary
                    )
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Divider(color = SleekInnerGray, modifier = Modifier.padding(vertical = 4.dp))

                    // SSH Tunnel Status & Toggle Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isTunnelEnabled) {
                                if (isTunnelConnected && publicUrl != null) Color(0xFFE8F5E9) else Color(0xFFE3F2FD)
                            } else {
                                Color(0xFFF5F5F5)
                            }
                        ),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, SleekInnerGray)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isTunnelConnected) Icons.Default.CheckCircle else Icons.Default.Refresh,
                                        contentDescription = "Global Access icon",
                                        tint = if (isTunnelConnected) Color(0xFF2E7D32) else SleekBlueAccent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Column {
                                        Text(
                                            text = "GLOBAL ONLINE CHANNEL",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = SleekBlueAccent,
                                            letterSpacing = 0.5.sp
                                        )
                                        Text(
                                            text = "Bypass Local Wi-Fi Restrictions",
                                            fontSize = 9.sp,
                                            color = SleekTextSecondary
                                        )
                                    }
                                }

                                Switch(
                                    checked = isTunnelEnabled,
                                    onCheckedChange = { checked ->
                                        ScreenMirroringState.isTunnelEnabled.value = checked
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = SleekBlueAccent,
                                        checkedTrackColor = SleekBlueAccent.copy(alpha = 0.5f)
                                    )
                                )
                            }

                            if (isTunnelEnabled) {
                                Divider(color = SleekInnerGray.copy(alpha = 0.5f))

                                if (isTunnelConnected && publicUrl != null) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(Color.White, RoundedCornerShape(6.dp))
                                            .border(1.dp, SleekInnerGray, RoundedCornerShape(6.dp))
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "SECURE ONLINE URL",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF2E7D32),
                                                letterSpacing = 0.5.sp
                                            )
                                            Text(
                                                text = publicUrl ?: "Generating...",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = SleekTextDark
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                publicUrl?.let { url ->
                                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                    val clip = ClipData.newPlainText("Casting Online URL", url)
                                                    clipboard.setPrimaryClip(clip)
                                                    Toast.makeText(context, "Online Link copied!", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Share,
                                                contentDescription = "Copy Online Url",
                                                tint = Color(0xFF2E7D32),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    
                                    Text(
                                        text = "⚡ Real-time cloud tunnel active. You can control this screen from cellular data anywhere in the world!",
                                        fontSize = 10.sp,
                                        color = Color(0xFF1B5E20),
                                        lineHeight = 13.sp
                                    )
                                } else {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(12.dp),
                                            color = SleekBlueAccent,
                                            strokeWidth = 1.5.dp
                                        )
                                        Text(
                                            text = "Connecting to secure cloud server...",
                                            fontSize = 11.sp,
                                            color = SleekTextSecondary,
                                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                                        )
                                    }
                                }
                            } else {
                                Text(
                                    text = "Enable to establish a secure reverse cloud tunnel, allowing you to access the mirroring session over cellular data or from external networks.",
                                    fontSize = 10.sp,
                                    color = SleekTextSecondary,
                                    lineHeight = 13.sp
                                )
                            }
                        }
                    }

                    // Accessibility Warning
                    if (!isAccEnabled) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFFFF3E0), RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0xFFFFE0B2), RoundedCornerShape(8.dp))
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = "Accessibility", tint = Color(0xFFEF6C00), modifier = Modifier.size(20.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Accessibility Access Required",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SleekTextDark
                                )
                                Text(
                                    text = "Enable 'WiFi Control Service' in system settings to permit web browser remote control gestures.",
                                    fontSize = 10.sp,
                                    color = SleekTextSecondary,
                                    lineHeight = 13.sp
                                )
                            }
                            Button(
                                onClick = {
                                    try {
                                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Open settings failed", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF6C00)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text("ENABLE", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFE8F5E9), RoundedCornerShape(8.dp))
                                .border(1.dp, Color(0xFFC8E6C9), RoundedCornerShape(8.dp))
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = "Accessibility Ok", tint = Color(0xFF2E7D32), modifier = Modifier.size(16.dp))
                            Text(
                                text = "Web browser touch controller enabled using Accessibility API.",
                                fontSize = 10.sp,
                                color = Color(0xFF2E7D32),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // Console Logs Terminal inside panel
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        border = BorderStroke(1.dp, SleekInnerGray),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "CONSOLE STREAM",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SleekBlueAccent
                                )
                                Text(
                                    text = "CLEAR LOGS",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SleekTextSecondary,
                                    modifier = Modifier.clickable { ScreenMirroringState.clearLogs() }
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.05f), RoundedCornerShape(4.dp))
                                    .padding(4.dp)
                            ) {
                                if (logs.isEmpty()) {
                                    Text(
                                        text = "Server idle. Start server to stream logs.",
                                        fontSize = 10.sp,
                                        color = SleekTextSecondary,
                                        modifier = Modifier.align(Alignment.Center)
                                    )
                                } else {
                                    LazyColumn(
                                        state = lazyListState,
                                        modifier = Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        items(logs) { log ->
                                            Text(
                                                text = log,
                                                color = if (log.contains("error", ignoreCase = true) || log.contains("failed", ignoreCase = true)) Color(0xFFEF4444) else Color(0xFF1B5E20),
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Bottom buttons line
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Any same-WiFi device web browser can control this device.",
                            fontSize = 10.sp,
                            color = SleekTextSecondary,
                            modifier = Modifier.weight(1f),
                            lineHeight = 13.sp
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Copy URL button
                            TextButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("Casting URL", "http://$ipAddress:$port")
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Casting Link copied!", Toast.LENGTH_SHORT).show()
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = "Copy", modifier = Modifier.size(14.dp), tint = SleekBlueAccent)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("COPY URL", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = SleekBlueAccent)
                            }

                            // Manual STOP button
                            Button(
                                onClick = {
                                    val intent = Intent(context, ScreenMirroringService::class.java).apply {
                                        action = ScreenMirroringService.ACTION_STOP
                                    }
                                    context.startService(intent)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = SleekRedAccent),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("STOP MIRROR", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RemoteScreenMirrorView(url: String, onBack: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black)
    ) {
        // Simple Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SleekBackground)
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = SleekTextDark
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "MIRRORED REMOTE PHONE",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = SleekBlueAccent,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = url,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = SleekTextDark,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        builtInZoomControls = true
                        displayZoomControls = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }
                    webViewClient = WebViewClient()
                }
            },
            update = { webView ->
                val finalUrl = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    "http://$url"
                } else {
                    url
                }
                webView.loadUrl(finalUrl)
            },
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
    }
}

