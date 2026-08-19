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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

class JellyfinService : Service() {

    private val binder = LocalBinder()
    private var jellyfinProcess: Process? = null
    private val logBuffer = StringBuilder()
    private var logListener: ((String) -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    var isRunning = false
        private set

    inner class LocalBinder : Binder() {
        fun getService(): JellyfinService = this@JellyfinService
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
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
        this.logListener = listener
        if (listener != null) {
            val current = synchronized(logBuffer) { logBuffer.toString() }
            listener.invoke(current)
        }
    }

    fun getLogs(): String = synchronized(logBuffer) { logBuffer.toString() }

    private fun startServer() {
        if (isRunning) return
        Thread {
            try {
                isRunning = true
                logAndNotify("Initializing Jellyfin Server...")

                val jellyfinHome = File(filesDir, "jellyfin")
                jellyfinHome.mkdirs()

                val markerFile = File(jellyfinHome, ".extracted_marker")
                if (!markerFile.exists()) {
                    logAndNotify("Extracting assets (first launch — please wait)...")
                    extractAssets(jellyfinHome)
                    markerFile.createNewFile()
                    logAndNotify("Assets extracted successfully.")
                }

                val nativeLibDir = applicationInfo.nativeLibraryDir
                val loaderPath = File(nativeLibDir, "libld.so").absolutePath
                val jellyfinBin = File(nativeLibDir, "libjellyfin.so").absolutePath

                logAndNotify("Starting Jellyfin process...")

                val dataDir   = File(jellyfinHome, "data").also { it.mkdirs() }
                val configDir = File(jellyfinHome, "config").also { it.mkdirs() }
                val cacheDir  = File(jellyfinHome, "cache").also { it.mkdirs() }
                val logDir    = File(jellyfinHome, "log").also { it.mkdirs() }
                val webDir    = File(jellyfinHome, "jellyfin-web")

                val processBuilder = ProcessBuilder(
                    loaderPath,
                    jellyfinBin,
                    "--nonetchange",
                    "--datadir",   dataDir.absolutePath,
                    "--configdir", configDir.absolutePath,
                    "--cachedir",  cacheDir.absolutePath,
                    "--logdir",    logDir.absolutePath,
                    "--webdir",    webDir.absolutePath
                )

                val env = processBuilder.environment()
                env.remove("LD_PRELOAD")
                env["LD_LIBRARY_PATH"] = nativeLibDir
                env["DOTNET_SYSTEM_GLOBALIZATION_INVARIANT"] = "1"
                env["DOTNET_gcServer"] = "0"
                env["DOTNET_System_GC_Server"] = "false"
                env["DOTNET_GCHeapHardLimit"] = "200000000"
                processBuilder.redirectErrorStream(true)

                val process = processBuilder.start()
                jellyfinProcess = process

                logAndNotify("Jellyfin process started. Access at http://127.0.0.1:8096")

                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    logAndNotify(line ?: "")
                }

                val exitCode = process.waitFor()
                logAndNotify("Jellyfin exited with code $exitCode")
            } catch (e: Exception) {
                logAndNotify("Error: ${e.message}")
                Log.e("JellyfinService", "Server error", e)
            } finally {
                isRunning = false
                jellyfinProcess = null
                stopSelf()
            }
        }.start()
    }

    fun stopServer() {
        logAndNotify("Stopping Jellyfin Server...")
        jellyfinProcess?.destroy()
        jellyfinProcess = null
        isRunning = false
    }

    private fun extractAssets(destDir: File) {
        val zipInputStream = ZipInputStream(assets.open("jellyfin_assets.zip"))
        val buffer = ByteArray(8192)
        var entry = zipInputStream.nextEntry
        while (entry != null) {
            val file = File(destDir, entry.name)
            if (entry.isDirectory) {
                file.mkdirs()
            } else {
                file.parentFile?.mkdirs()
                FileOutputStream(file).use { fos ->
                    var len: Int
                    while (zipInputStream.read(buffer).also { len = it } > 0) {
                        fos.write(buffer, 0, len)
                    }
                }
            }
            zipInputStream.closeEntry()
            entry = zipInputStream.nextEntry
        }
        zipInputStream.close()
    }

    private fun logAndNotify(message: String) {
        Log.d("JellyfinService", message)
        synchronized(logBuffer) {
            if (logBuffer.length > 50000) logBuffer.delete(0, 10000)
            logBuffer.append(message).append("\n")
        }
        mainHandler.post {
            logListener?.invoke(message)
        }
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
            )
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
            .setContentText("Media server is running on port 8096")
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
