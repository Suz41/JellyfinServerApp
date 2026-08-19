package com.example.jellyfinserver

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var jellyfinService: JellyfinService? = null
    private var isBound = false
    
    private val logLines = mutableStateListOf<String>()
    private var serverRunning by mutableStateOf(false)
    private var ipAddress by mutableStateOf("127.0.0.1")

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as JellyfinService.LocalBinder
            jellyfinService = binder.getService()
            isBound = true
            
            // Sync current server status and logs
            serverRunning = jellyfinService?.isRunning == true
            logLines.clear()
            jellyfinService?.getLogs()?.split("\n")?.forEach { line ->
                if (line.isNotEmpty()) logLines.add(line)
            }
            
            // Set real-time log listener
            jellyfinService?.setLogListener { newLine ->
                if (newLine.isNotEmpty()) {
                    logLines.add(newLine)
                }
                serverRunning = jellyfinService?.isRunning == true
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            jellyfinService = null
            isBound = false
            serverRunning = false
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startJellyfinService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        ipAddress = getWifiIpAddress(this)
        
        setContent {
            JellyfinServerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ServerControlScreen(
                        serverRunning = serverRunning,
                        ipAddress = ipAddress,
                        logs = logLines,
                        onToggleServer = {
                            if (serverRunning) {
                                stopJellyfinService()
                            } else {
                                checkAndStartService()
                            }
                        },
                        onOpenWebUi = {
                            val url = "http://localhost:8096"
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            startActivity(intent)
                        }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Bind to service to receive logs and status if running
        Intent(this, JellyfinService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            jellyfinService?.setLogListener(null)
            unbindService(connection)
            isBound = false
        }
    }

    private fun checkAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        startJellyfinService()
    }

    private fun startJellyfinService() {
        val intent = Intent(this, JellyfinService::class.java)
        ContextCompat.startForegroundService(this, intent)
        // Bind to it
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun stopJellyfinService() {
        val intent = Intent(this, JellyfinService::class.java).apply {
            action = "STOP"
        }
        startService(intent)
        serverRunning = false
    }

    private fun getWifiIpAddress(context: Context): String {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wifiManager.connectionInfo.ipAddress
        return if (ip == 0) {
            "127.0.0.1"
        } else {
            Formatter.formatIpAddress(ip)
        }
    }
}

@Composable
fun ServerControlScreen(
    serverRunning: Boolean,
    ipAddress: String,
    logs: List<String>,
    onToggleServer: () -> Unit,
    onOpenWebUi: () -> Unit
) {
    val listState = rememberLazyListState()
    
    // Auto-scroll logs to bottom
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "🍒 Jellyfin Media Server",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFDE3163), // Cherry Red
            modifier = Modifier.padding(vertical = 12.dp)
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Server Status:", fontWeight = FontWeight.SemiBold)
                    Text(
                        text = if (serverRunning) "RUNNING 🟢" else "STOPPED 🔴",
                        color = if (serverRunning) Color.Green else Color.Red,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Local Web UI:", fontWeight = FontWeight.SemiBold)
                    Text(text = "http://$ipAddress:8096", color = Color(0xFFDE3163))
                }
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
                    containerColor = if (serverRunning) Color.Red else Color(0xFFDE3163)
                )
            ) {
                Text(text = if (serverRunning) "Stop Server" else "Start Server")
            }

            Button(
                onClick = onOpenWebUi,
                enabled = serverRunning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.DarkGray
                )
            ) {
                Text(text = "Open Web UI")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Server Logs",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Start)
        )
        
        Spacer(modifier = Modifier.height(4.dp))
        
        // Log Viewer console
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black, shape = RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
            ) {
                items(logs.size) { index ->
                    Text(
                        text = logs[index],
                        color = Color.LightGray,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

// Light and Dark theme helpers
@Composable
fun JellyfinServerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFDE3163),
            background = Color(0xFF121212),
            surface = Color(0xFF1E1E1E),
            onPrimary = Color.White
        ),
        content = content
    )
}
