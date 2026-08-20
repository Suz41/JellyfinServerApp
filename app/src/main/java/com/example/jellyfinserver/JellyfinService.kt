package com.example.jellyfinserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.jellyfinserver.core.LogManager
import com.example.jellyfinserver.core.ServerManager
import com.example.jellyfinserver.core.ServerState

class JellyfinService : Service() {

    private val binder = LocalBinder()
    private lateinit var serverManager: ServerManager
    
    var state = ServerState.STOPPED
        private set

    private var stateListener: ((ServerState) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): JellyfinService = this@JellyfinService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        serverManager = ServerManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "STOP") {
            stopServer()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, createNotification())
        startServer()
        return START_STICKY
    }

    fun setLogListener(listener: ((String) -> Unit)?) {
        LogManager.setLogListener(listener)
    }

    fun getLogs(): String = LogManager.getLogs()

    fun setStateListener(listener: ((ServerState) -> Unit)?) {
        this.stateListener = listener
        listener?.invoke(state)
    }

    private fun startServer() {
        if (state != ServerState.STOPPED && state != ServerState.START_FAILED && state != ServerState.PROCESS_EXITED && state != ServerState.TCP_BIND_FAILED && state != ServerState.HTTP_NOT_READY) return
        state = ServerState.STARTING
        stateListener?.invoke(state)
        serverManager.startServer { newState ->
            state = newState
            stateListener?.invoke(state)
        }
    }

    fun stopServer() {
        serverManager.stopServer()
        state = ServerState.STOPPED
        stateListener?.invoke(state)
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Jellyfin Service Channel",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Service status updates for Jellyfin background process"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, JellyfinService::class.java).apply { action = "STOP" }
        val pendingStop = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val mainIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jellyfin Server")
            .setContentText("Media server running on port 8096")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(mainIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pendingStop)
            .setOngoing(true)
            .build()
        }

    companion object {
        private const val CHANNEL_ID = "JellyfinServiceChannel"
        private const val NOTIFICATION_ID = 1001
    }
}
