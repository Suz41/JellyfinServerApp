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
import android.system.Os

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
                val markerFile = File(jellyfinHome, ".extracted_marker")

                // Robust extraction: Extract to temp directory first, then atomically rename
                if (!markerFile.exists()) {
                    logAndNotify("Extracting assets (first launch — please wait)...")
                    val tempDir = File(filesDir, "jellyfin_temp")
                    if (tempDir.exists()) tempDir.deleteRecursively()
                    tempDir.mkdirs()

                    try {
                        extractAssets(tempDir)
                        if (jellyfinHome.exists()) jellyfinHome.deleteRecursively()
                        if (tempDir.renameTo(jellyfinHome)) {
                            markerFile.createNewFile()
                            logAndNotify("Assets extracted successfully.")
                        } else {
                            throw Exception("Failed to rename temp directory to jellyfin home")
                        }
                    } catch (e: Exception) {
                        logAndNotify("ERROR: Extraction failed: ${e.message}")
                        if (tempDir.exists()) tempDir.deleteRecursively()
                        isRunning = false
                        return@Thread
                    }
                }

                val nativeLibDir = applicationInfo.nativeLibraryDir
                val dotnetRoot = File(filesDir, "dotnet")
                val fxrDir = File(dotnetRoot, "host/fxr/9.0.16")
                val sharedDir = File(dotnetRoot, "shared/Microsoft.NETCore.App/9.0.16")

                // Construct .NET runtime structure with symbolic links to nativeLibDir
                logAndNotify("Setting up .NET 9 ARM64 runtime layout...")
                try {
                    if (dotnetRoot.exists()) dotnetRoot.deleteRecursively()
                    fxrDir.mkdirs()
                    sharedDir.mkdirs()

                    // Clean up old .so symlinks and binaries in jellyfinHome first (since native library dir path changes on upgrades)
                    val jellyfinHomeFiles = jellyfinHome.listFiles()
                    if (jellyfinHomeFiles != null) {
                        for (file in jellyfinHomeFiles) {
                            if (file.name.endsWith(".so") || file.name.contains(".so.") || file.name == "ffmpeg" || file.name == "ffprobe") {
                                file.delete()
                            }
                        }
                    }

                    val libFiles = File(nativeLibDir).listFiles()
                    if (libFiles != null) {
                        for (lib in libFiles) {
                            if (lib.name.endsWith(".so")) {
                                // Symlink hostfxr to host/fxr/9.0.16/
                                if (lib.name == "libhostfxr.so") {
                                    val symlinkFile = File(fxrDir, "libhostfxr.so")
                                    Os.symlink(lib.absolutePath, symlinkFile.absolutePath)
                                }
                                // Symlink all libraries directly to jellyfinHome (for self-contained layout next to jellyfin.dll)
                                val symlinkAppFile = File(jellyfinHome, lib.name)
                                Os.symlink(lib.absolutePath, symlinkAppFile.absolutePath)

                                // Symlink all libraries directly to the dotnet root (for fallback self-contained layout)
                                val symlinkRootFile = File(dotnetRoot, lib.name)
                                Os.symlink(lib.absolutePath, symlinkRootFile.absolutePath)

                                // If this is libssl or libcrypto, create versioned symlinks that .NET dynamic prober expects
                                if (lib.name == "libssl.so") {
                                    Os.symlink(lib.absolutePath, File(jellyfinHome, "libssl.so.3").absolutePath)
                                    Os.symlink(lib.absolutePath, File(jellyfinHome, "libssl.so.1.1").absolutePath)
                                    Os.symlink(lib.absolutePath, File(dotnetRoot, "libssl.so.3").absolutePath)
                                    Os.symlink(lib.absolutePath, File(dotnetRoot, "libssl.so.1.1").absolutePath)
                                } else if (lib.name == "libcrypto.so") {
                                    Os.symlink(lib.absolutePath, File(jellyfinHome, "libcrypto.so.3").absolutePath)
                                    Os.symlink(lib.absolutePath, File(jellyfinHome, "libcrypto.so.1.1").absolutePath)
                                    Os.symlink(lib.absolutePath, File(dotnetRoot, "libcrypto.so.3").absolutePath)
                                    Os.symlink(lib.absolutePath, File(dotnetRoot, "libcrypto.so.1.1").absolutePath)
                                } else if (lib.name == "libffmpeg.so") {
                                    // Symlink target executable in read-only /data/app/.../lib/arm64 to bypass Android W^X restriction
                                    val symlinkExec = File(jellyfinHome, "ffmpeg")
                                    Os.symlink(lib.absolutePath, symlinkExec.absolutePath)
                                    logAndNotify("Created symlink: ffmpeg -> ${lib.absolutePath}")
                                } else if (lib.name == "libffprobe.so") {
                                    // Symlink target executable in read-only /data/app/.../lib/arm64 to bypass Android W^X restriction
                                    val symlinkExec = File(jellyfinHome, "ffprobe")
                                    Os.symlink(lib.absolutePath, symlinkExec.absolutePath)
                                    logAndNotify("Created symlink: ffprobe -> ${lib.absolutePath}")
                                }

                                // Symlink all libraries to shared framework directory (for fallback framework-dependent layout)
                                val symlinkFile = File(sharedDir, lib.name)
                                Os.symlink(lib.absolutePath, symlinkFile.absolutePath)
                            }
                        }
                    } else {
                        logAndNotify("WARNING: No native libraries found in $nativeLibDir")
                    }
                } catch (e: Exception) {
                    logAndNotify("ERROR: Failed to initialize .NET runtime layout: ${e.message}")
                    isRunning = false
                    return@Thread
                }

                // Verify file existence
                val loaderPath = File(nativeLibDir, "libld.so").absolutePath
                val jellyfinBin = File(nativeLibDir, "libjellyfin.so").absolutePath
                val jellyfinDll = File(jellyfinHome, "jellyfin.dll")
                val hostfxrPath = File(fxrDir, "libhostfxr.so")
                val hostpolicyPath = File(jellyfinHome, "libhostpolicy.so")
                val coreclrPath = File(jellyfinHome, "libcoreclr.so")
                val sslPath = File(jellyfinHome, "libssl.so")
                val cryptoPath = File(jellyfinHome, "libcrypto.so")
                val ffmpegPath = File(jellyfinHome, "ffmpeg")
                val ffprobePath = File(jellyfinHome, "ffprobe")
                val runtimeconfigPath = File(jellyfinHome, "jellyfin.runtimeconfig.json")
                val depsPath = File(jellyfinHome, "jellyfin.deps.json")

                logAndNotify("Verifying required files before launch:")
                logAndNotify("  Loader exists: ${File(loaderPath).exists()}")
                logAndNotify("  Apphost exists: ${File(jellyfinBin).exists()}")
                logAndNotify("  Jellyfin DLL exists: ${jellyfinDll.exists()}")
                logAndNotify("  hostfxr exists: ${hostfxrPath.exists()}")
                logAndNotify("  hostpolicy exists: ${hostpolicyPath.exists()}")
                logAndNotify("  coreclr exists: ${coreclrPath.exists()}")
                logAndNotify("  libssl exists: ${sslPath.exists()}")
                logAndNotify("  libcrypto exists: ${cryptoPath.exists()}")
                logAndNotify("  ffmpeg exists: ${ffmpegPath.exists()} (canExecute: ${ffmpegPath.canExecute()})")
                logAndNotify("  ffprobe exists: ${ffprobePath.exists()} (canExecute: ${ffprobePath.canExecute()})")
                logAndNotify("  runtimeconfig exists: ${runtimeconfigPath.exists()}")
                logAndNotify("  deps exists: ${depsPath.exists()}")

                if (!File(loaderPath).exists()) {
                    logAndNotify("ERROR: libld.so loader not found at $loaderPath")
                    isRunning = false
                    return@Thread
                }
                if (!File(jellyfinBin).exists()) {
                    logAndNotify("ERROR: libjellyfin.so apphost not found at $jellyfinBin")
                    isRunning = false
                    return@Thread
                }
                if (!jellyfinDll.exists()) {
                    logAndNotify("ERROR: Jellyfin assembly not found at ${jellyfinDll.absolutePath}")
                    isRunning = false
                    return@Thread
                }
                if (!hostfxrPath.exists()) {
                    logAndNotify("ERROR: .NET 9 ARM64 runtime is missing.\nExpected libhostfxr.so at: ${hostfxrPath.absolutePath}")
                    isRunning = false
                    return@Thread
                }
                if (!hostpolicyPath.exists()) {
                    logAndNotify("ERROR: .NET 9 ARM64 hostpolicy is missing.\nExpected libhostpolicy.so at: ${hostpolicyPath.absolutePath}")
                    isRunning = false
                    return@Thread
                }
                if (!coreclrPath.exists()) {
                    logAndNotify("ERROR: .NET 9 ARM64 coreclr is missing.\nExpected libcoreclr.so at: ${coreclrPath.absolutePath}")
                    isRunning = false
                    return@Thread
                }
                if (!sslPath.exists()) {
                    logAndNotify("ERROR: OpenSSL libssl.so is missing at: ${sslPath.absolutePath}")
                    isRunning = false
                    return@Thread
                }
                if (!cryptoPath.exists()) {
                    logAndNotify("ERROR: OpenSSL libcrypto.so is missing at: ${cryptoPath.absolutePath}")
                    isRunning = false
                    return@Thread
                }
                if (!runtimeconfigPath.exists()) {
                    logAndNotify("ERROR: runtimeconfig.json is missing at: ${runtimeconfigPath.absolutePath}")
                    isRunning = false
                    return@Thread
                }
                if (!depsPath.exists()) {
                    logAndNotify("ERROR: deps.json is missing at: ${depsPath.absolutePath}")
                    isRunning = false
                    return@Thread
                }

                // Log launch parameters
                logAndNotify("Jellyfin root: ${jellyfinHome.absolutePath}")
                logAndNotify("Jellyfin DLL: ${jellyfinDll.absolutePath}")
                logAndNotify("runtimeconfig.json: ${runtimeconfigPath.absolutePath}")
                logAndNotify("deps.json: ${depsPath.absolutePath}")
                logAndNotify(".NET root: ${dotnetRoot.absolutePath}")
                logAndNotify("hostfxr: ${hostfxrPath.absolutePath}")
                logAndNotify("hostpolicy: ${hostpolicyPath.absolutePath}")
                logAndNotify("coreclr: ${coreclrPath.absolutePath}")
                logAndNotify("libssl: ${sslPath.absolutePath}")
                logAndNotify("libcrypto: ${cryptoPath.absolutePath}")
                logAndNotify("runtime version: 9.0.16")
                logAndNotify("target RID: linux-arm64")
                logAndNotify("deployment model: self-contained")

                // Run diagnostic check on ffmpeg to log any startup library/SELinux/SECCOMP issues
                diagnoseFFmpeg(ffmpegPath.absolutePath)

                logAndNotify("Starting Jellyfin process...")

                val dataDir   = File(jellyfinHome, "data").also { it.mkdirs() }
                val configDir = File(jellyfinHome, "config").also { it.mkdirs() }
                val cacheDir  = File(jellyfinHome, "cache").also { it.mkdirs() }
                val logDir    = File(jellyfinHome, "log").also { it.mkdirs() }
                val webDir    = File(jellyfinHome, "jellyfin-web")

                // Auto-configure IP binding and remote access
                configureNetworkSettings(configDir)

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

                // Set working directory
                processBuilder.directory(jellyfinHome)

                val env = processBuilder.environment()
                env.remove("LD_PRELOAD")
                env["LD_LIBRARY_PATH"] = "${jellyfinHome.absolutePath}:$nativeLibDir"
                env["DOTNET_ROOT"] = dotnetRoot.absolutePath
                env["DOTNET_SYSTEM_GLOBALIZATION_INVARIANT"] = "1"
                env["DOTNET_gcServer"] = "0"
                env["DOTNET_System_GC_Server"] = "false"
                env["DOTNET_GCHeapHardLimit"] = "200000000"
                env["COREHOST_TRACE"] = "1"

                // Set PATH to include jellyfinHome so spawned processes (ffmpeg/ffprobe) can be found
                val currentPath = env["PATH"] ?: "/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin"
                env["PATH"] = "${jellyfinHome.absolutePath}:$currentPath"

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

    private fun configureNetworkSettings(configDir: File) {
        val networkXml = File(configDir, "network.xml")
        val defaultXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <NetworkConfiguration xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
              <RequireHttps>false</RequireHttps>
              <CertificatePath />
              <CertificatePassword />
              <EnableHttps>false</EnableHttps>
              <PublicHttpsPort>8920</PublicHttpsPort>
              <HttpServerPortNumber>8096</HttpServerPortNumber>
              <HttpsPortNumber>8920</HttpsPortNumber>
              <EnableHttp2>true</EnableHttp2>
              <EnableHttp3>false</EnableHttp3>
              <EnableRemoteAccess>true</EnableRemoteAccess>
              <BindInterfaceAddress />
            </NetworkConfiguration>
        """.trimIndent()

        try {
            if (!networkXml.exists()) {
                networkXml.writeText(defaultXml)
                logAndNotify("Created default network.xml with remote access enabled.")
            } else {
                var content = networkXml.readText()
                if (content.contains("<EnableRemoteAccess>false</EnableRemoteAccess>")) {
                    content = content.replace("<EnableRemoteAccess>false</EnableRemoteAccess>", "<EnableRemoteAccess>true</EnableRemoteAccess>")
                    networkXml.writeText(content)
                    logAndNotify("Auto-configured network.xml: enabled remote access.")
                }
            }
        } catch (e: Exception) {
            logAndNotify("WARNING: Failed to auto-configure network.xml: ${e.message}")
        }
    }

    private fun diagnoseFFmpeg(ffmpegPath: String) {
        logAndNotify("Diagnosing ffmpeg launch at $ffmpegPath...")
        try {
            val process = ProcessBuilder(ffmpegPath, "-version")
                .redirectErrorStream(true)
                .start()
            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            var line: String?
            var lineCount = 0
            while (reader.readLine().also { line = it } != null) {
                if (lineCount < 20) { // Limit output lines to avoid clobbering UI logs
                    logAndNotify("  [ffmpeg] $line")
                }
                lineCount++
            }
            val exitCode = process.waitFor()
            logAndNotify("ffmpeg exit code: $exitCode")
        } catch (e: Exception) {
            logAndNotify("ffmpeg execution failed: ${e.message}")
        }
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
