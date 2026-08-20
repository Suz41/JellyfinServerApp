package com.example.jellyfinserver

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import android.widget.Toast
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import com.example.jellyfinserver.core.ServerState
import java.net.NetworkInterface
import java.net.Inet4Address

class MainActivity : ComponentActivity() {

    private var jellyfinService: JellyfinService? = null
    private var isBound = false

    private val logLines = mutableStateListOf<String>()
    private var serverState by mutableStateOf(ServerState.STOPPED)
    private val ipAddresses = mutableStateListOf<String>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as JellyfinService.LocalBinder
            jellyfinService = binder.getService()
            isBound = true
            
            jellyfinService?.setStateListener { newState ->
                serverState = newState
                if (newState == ServerState.RUNNING) {
                    ipAddresses.clear()
                    ipAddresses.addAll(getLocalIpAddresses())
                }
            }

            val existing = jellyfinService?.getLogs() ?: ""
            if (existing.isNotBlank()) {
                logLines.clear()
                existing.split("\n").filter { it.isNotEmpty() }.forEach { logLines.add(it) }
            }

            jellyfinService?.setLogListener { line ->
                if (line.isNotEmpty()) {
                    logLines.add(line)
                }
                val newState = jellyfinService?.state ?: ServerState.STOPPED
                if (newState != serverState) {
                    serverState = newState
                    if (newState == ServerState.RUNNING) {
                        ipAddresses.clear()
                        ipAddresses.addAll(getLocalIpAddresses())
                    }
                }
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            jellyfinService = null
            isBound = false
            serverState = ServerState.STOPPED
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startJellyfinService() }

    private fun getLocalIpAddresses(): List<String> {
        val list = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val element = interfaces.nextElement()
                if (element.isLoopback || !element.isUp) continue
                val addresses = element.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address) {
                        addr.hostAddress?.let { list.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            JellyfinServerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF121212)
                ) {
                    ServerControlScreen(
                        serverState = serverState,
                        logs = logLines,
                        ipAddresses = ipAddresses,
                        onToggleServer = {
                            val active = (serverState != ServerState.STOPPED &&
                                    serverState != ServerState.START_FAILED &&
                                    serverState != ServerState.PROCESS_EXITED &&
                                    serverState != ServerState.TCP_BIND_FAILED &&
                                    serverState != ServerState.HTTP_NOT_READY)
                            if (active) stopJellyfinService()
                            else checkAndStartService()
                        },
                        onOpenWebUi = {
                            startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:8096"))
                            )
                        }
                    )
                }
            }
        }
    }

    private fun checkAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        startJellyfinService()
    }

    private fun startJellyfinService() {
        val intent = Intent(this, JellyfinService::class.java)
        ContextCompat.startForegroundService(this, intent)
        if (!isBound) {
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun stopJellyfinService() {
        val intent = Intent(this, JellyfinService::class.java).apply { action = "STOP" }
        startService(intent)
        serverState = ServerState.STOPPED
        if (isBound) {
            jellyfinService?.setLogListener(null)
            unbindService(connection)
            isBound = false
        }
    }

    override fun onDestroy() {
        if (isBound) {
            jellyfinService?.setLogListener(null)
            unbindService(connection)
            isBound = false
        }
        super.onDestroy()
    }
}

@Composable
fun ServerControlScreen(
    serverState: ServerState,
    logs: List<String>,
    ipAddresses: List<String>,
    onToggleServer: () -> Unit,
    onOpenWebUi: () -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }

    val statusText = when (serverState) {
        ServerState.STOPPED -> "STOPPED 🔴"
        ServerState.STARTING,
        ServerState.PROCESS_STARTED,
        ServerState.RUNTIME_INITIALIZED,
        ServerState.JELLYFIN_INITIALIZING,
        ServerState.HTTP_WAITING,
        ServerState.TCP_CHECK,
        ServerState.HTTP_CHECK -> "STARTING 🟡"
        ServerState.WEB_STATIC_ONLY -> "WEB_STATIC_ONLY 🟡"
        ServerState.API_NOT_READY -> "API_NOT_READY 🟡"
        ServerState.RUNNING -> "RUNNING 🟢"
        ServerState.STOPPING -> "STOPPING 🟠"
        ServerState.START_FAILED,
        ServerState.PROCESS_EXITED,
        ServerState.TCP_BIND_FAILED,
        ServerState.HTTP_NOT_READY -> "ERROR ⚠️"
    }

    val statusColor = when (serverState) {
        ServerState.RUNNING -> Color.Green
        ServerState.STOPPED -> Color.Red
        ServerState.STARTING,
        ServerState.PROCESS_STARTED,
        ServerState.RUNTIME_INITIALIZED,
        ServerState.JELLYFIN_INITIALIZING,
        ServerState.HTTP_WAITING,
        ServerState.TCP_CHECK,
        ServerState.HTTP_CHECK -> Color.Yellow
        ServerState.WEB_STATIC_ONLY -> Color.Yellow
        ServerState.API_NOT_READY -> Color.Yellow
        ServerState.STOPPING -> Color(0xFFFFA500)
        ServerState.START_FAILED,
        ServerState.PROCESS_EXITED,
        ServerState.TCP_BIND_FAILED,
        ServerState.HTTP_NOT_READY -> Color(0xFFFF4444)
    }

    val detailText = when (serverState) {
        ServerState.STOPPED -> ""
        ServerState.RUNNING -> ""
        else -> " (${serverState.name})"
    }

    val buttonText = when (serverState) {
        ServerState.STOPPING -> "Stopping..."
        ServerState.STARTING,
        ServerState.PROCESS_STARTED,
        ServerState.RUNTIME_INITIALIZED,
        ServerState.JELLYFIN_INITIALIZING,
        ServerState.HTTP_WAITING,
        ServerState.TCP_CHECK,
        ServerState.HTTP_CHECK,
        ServerState.WEB_STATIC_ONLY,
        ServerState.API_NOT_READY -> "Starting..."
        ServerState.RUNNING -> "Stop Server"
        else -> "Start Server"
    }

    val canStart = (serverState == ServerState.STOPPED ||
            serverState == ServerState.START_FAILED ||
            serverState == ServerState.PROCESS_EXITED ||
            serverState == ServerState.TCP_BIND_FAILED ||
            serverState == ServerState.HTTP_NOT_READY)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "🍒 Jellyfin Server",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFDE3163),
            modifier = Modifier.padding(vertical = 12.dp)
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Status:", fontWeight = FontWeight.SemiBold, color = Color.White)
                Text(
                    text = "$statusText$detailText",
                    color = statusColor,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (serverState == ServerState.RUNNING && ipAddresses.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Access Server At:",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFDE3163),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    Text(
                        text = "Local: http://127.0.0.1:8096",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                    ipAddresses.forEach { ip ->
                        Text(
                            text = "Network: http://$ip:8096",
                            color = Color.LightGray,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = onToggleServer,
                enabled = canStart || serverState == ServerState.RUNNING,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (serverState == ServerState.RUNNING) Color(0xFFB71C1C) else Color(0xFFDE3163)
                )
            ) {
                Text(buttonText)
            }
            Button(
                onClick = onOpenWebUi,
                enabled = serverState == ServerState.RUNNING,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333))
            ) {
                Text("Open Web UI")
            }
        }

        val clipboardManager = LocalClipboardManager.current
        val context = LocalContext.current

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Server Logs",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Gray
            )
            OutlinedButton(
                onClick = {
                    val fullLogs = logs.joinToString("\n")
                    if (fullLogs.isNotBlank()) {
                        clipboardManager.setText(AnnotatedString(fullLogs))
                        Toast.makeText(context, "Server logs copied to clipboard! 📋", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "No logs to copy", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            ) {
                Text("📋 Copy Logs", fontSize = 12.sp, color = Color.White)
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black, shape = RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(logs.size) { index ->
                    Text(
                        text = logs[index],
                        color = Color(0xFFCCCCCC),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }
    }
}

@Composable
fun JellyfinServerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFDE3163),
            background = Color(0xFF121212),
            surface = Color(0xFF1E1E1E),
            onPrimary = Color.White,
            onBackground = Color.White,
            onSurface = Color.White
        ),
        content = content
    )
}
