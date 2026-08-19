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

class MainActivity : ComponentActivity() {

    private var jellyfinService: JellyfinService? = null
    private var isBound = false

    private val logLines = mutableStateListOf<String>()
    private var serverRunning by mutableStateOf(false)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as JellyfinService.LocalBinder
            jellyfinService = binder.getService()
            isBound = true
            serverRunning = jellyfinService?.isRunning == true
            val existing = jellyfinService?.getLogs() ?: ""
            if (existing.isNotBlank()) {
                logLines.clear()
                existing.split("\n").filter { it.isNotEmpty() }.forEach { logLines.add(it) }
            }
            jellyfinService?.setLogListener { line ->
                if (line.isNotEmpty()) logLines.add(line)
                serverRunning = jellyfinService?.isRunning == true
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            jellyfinService = null
            isBound = false
            serverRunning = false
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startJellyfinService() }

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
                        serverRunning = serverRunning,
                        logs = logLines,
                        onToggleServer = {
                            if (serverRunning) stopJellyfinService()
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
        serverRunning = false
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
    serverRunning: Boolean,
    logs: List<String>,
    onToggleServer: () -> Unit,
    onOpenWebUi: () -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }

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
                .padding(vertical = 8.dp),
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
                    text = if (serverRunning) "RUNNING 🟢" else "STOPPED 🔴",
                    color = if (serverRunning) Color.Green else Color.Red,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = onToggleServer,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (serverRunning) Color(0xFFB71C1C) else Color(0xFFDE3163)
                )
            ) {
                Text(if (serverRunning) "Stop Server" else "Start Server")
            }
            Button(
                onClick = onOpenWebUi,
                enabled = serverRunning,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333))
            ) {
                Text("Open Web UI")
            }
        }

        Text(
            text = "Server Logs",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Gray,
            modifier = Modifier
                .align(Alignment.Start)
                .padding(bottom = 4.dp)
        )

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
