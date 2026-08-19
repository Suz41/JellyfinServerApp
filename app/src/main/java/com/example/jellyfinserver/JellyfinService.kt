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
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import android.system.Os
import android.system.OsConstants
import java.nio.file.Files
import java.nio.file.Paths

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
            synchronized(startupLock) {
                try {
                    logAndNotify("=== JELLYFIN STARTUP DIAGNOSTICS ===")

                    // ── 1. Acquire Lock & Detect API & ABI ──
                    val deviceAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
                    logAndNotify("Android API: ${Build.VERSION.SDK_INT}")
                    logAndNotify("ABI: $deviceAbi")

                    if (!deviceAbi.contains("arm64") && !deviceAbi.contains("aarch64")) {
                        logAndNotify("FAILED STAGE: ABI Verification")
                        logAndNotify("REASON: Unsupported device ABI $deviceAbi. Jellyfin requires arm64-v8a.")
                        isRunning = false
                        return@Thread
                    }

                    // ── 2. Resolve Directory Paths ──
                    val nativeLibDir = applicationInfo.nativeLibraryDir
                    val jellyfinHome = File(filesDir, "jellyfin")
                    val dataDir   = File(jellyfinHome, "data").also { it.mkdirs() }
                    val configDir = File(jellyfinHome, "config").also { it.mkdirs() }
                    val cacheDir  = File(jellyfinHome, "cache").also { it.mkdirs() }
                    val logDir    = File(jellyfinHome, "log").also { it.mkdirs() }
                    val webDir    = File(jellyfinHome, "jellyfin-web")
                    val dotnetRoot = File(filesDir, "dotnet")

                    logAndNotify("Native library directory:\n  $nativeLibDir")
                    logAndNotify("Jellyfin root:\n  ${jellyfinHome.absolutePath}")
                    logAndNotify(".NET root:\n  ${dotnetRoot.absolutePath}")

                    // ── 3. Clean migration step ──
                    runCleanMigration(jellyfinHome, configDir)

                    // Extract assets if missing
                    val markerFile = File(jellyfinHome, ".extracted_marker")
                    if (!markerFile.exists()) {
                        logAndNotify("Extracting assets (first launch)...")
                        val tempDir = File(filesDir, "jellyfin_temp")
                        if (tempDir.exists()) tempDir.deleteRecursively()
                        tempDir.mkdirs()

                        try {
                            extractAssets(tempDir)
                            if (jellyfinHome.exists()) {
                                tempDir.listFiles()?.forEach { file ->
                                    if (file.name != "data" && file.name != "config") {
                                        val target = File(jellyfinHome, file.name)
                                        if (target.exists()) target.deleteRecursively()
                                        file.renameTo(target)
                                    }
                                }
                                tempDir.deleteRecursively()
                            } else {
                                tempDir.renameTo(jellyfinHome)
                            }
                            markerFile.createNewFile()
                            logAndNotify("Assets extracted successfully.")
                        } catch (e: Exception) {
                            logAndNotify("FAILED STAGE: Assets Extraction")
                            logAndNotify("REASON: ${e.message}")
                            if (tempDir.exists()) tempDir.deleteRecursively()
                            isRunning = false
                            return@Thread
                        }
                    }

                    // .NET framework directory setup
                    val fxrDir = File(dotnetRoot, "host/fxr/9.0.16")
                    val sharedDir = File(dotnetRoot, "shared/Microsoft.NETCore.App/9.0.16")
                    fxrDir.mkdirs()
                    sharedDir.mkdirs()

                    // ── 4. Robust & Idempotent Symlink Management ──
                    logAndNotify("--- Performing Safe Symlink Management ---")
                    val libFiles = File(nativeLibDir).listFiles()
                    if (libFiles != null) {
                        for (lib in libFiles) {
                            if (lib.name == "libhostfxr.so") {
                                safeCreateSymlink(lib, File(fxrDir, "libhostfxr.so"))
                            }
                            safeCreateSymlink(lib, File(jellyfinHome, lib.name))
                            safeCreateSymlink(lib, File(dotnetRoot, lib.name))
                            safeCreateSymlink(lib, File(sharedDir, lib.name))

                            if (lib.name == "libssl.so") {
                                safeCreateSymlink(lib, File(jellyfinHome, "libssl.so.3"))
                                safeCreateSymlink(lib, File(dotnetRoot, "libssl.so.3"))
                            } else if (lib.name == "libcrypto.so") {
                                safeCreateSymlink(lib, File(jellyfinHome, "libcrypto.so.3"))
                                safeCreateSymlink(lib, File(dotnetRoot, "libcrypto.so.3"))
                            } else if (lib.name == "libg_libc.so") {
                                safeCreateSymlink(lib, File(jellyfinHome, "libc.so.6"))
                                safeCreateSymlink(lib, File(jellyfinHome, "ld-linux-aarch64.so.1"))
                            } else if (lib.name == "libg_m.so") {
                                safeCreateSymlink(lib, File(jellyfinHome, "libm.so.6"))
                            } else if (lib.name == "libg_dl.so") {
                                safeCreateSymlink(lib, File(jellyfinHome, "libdl.so.2"))
                            } else if (lib.name == "libg_pthread.so") {
                                safeCreateSymlink(lib, File(jellyfinHome, "libpthread.so.0"))
                            }
                        }
                    }

                    // Extract binary components (ffmpeg.bin & ffprobe.bin) from assets
                    val ffmpegBinPath = File(jellyfinHome, "ffmpeg.bin")
                    val ffprobeBinPath = File(jellyfinHome, "ffprobe.bin")

                    for ((assetName, destFile) in listOf("ffmpeg.bin" to ffmpegBinPath, "ffprobe.bin" to ffprobeBinPath)) {
                        if (!destFile.exists() || destFile.length() <= 0) {
                            logAndNotify("Extracting $assetName...")
                            assets.open("jellyfin_assets.zip").use { assetStream ->
                                val zis = ZipInputStream(assetStream)
                                var entry = zis.nextEntry
                                while (entry != null) {
                                    if (entry.name == assetName) {
                                        destFile.outputStream().use { out -> zis.copyTo(out) }
                                        destFile.setExecutable(true, false)
                                        break
                                    }
                                    zis.closeEntry()
                                    entry = zis.nextEntry
                                }
                                zis.close()
                            }
                        }
                    }

                    // ── 5. Native Component & FFmpeg/FFprobe Resolution ──
                    val ffmpegPath = File(nativeLibDir, "libffmpeg.so")
                    val ffprobePath = File(nativeLibDir, "libffprobe.so")
                    val ldLibraryPath = "${jellyfinHome.absolutePath}:$nativeLibDir"

                    logAndNotify("FFmpeg path:\n  ${ffmpegPath.absolutePath}")
                    logAndNotify("FFprobe path:\n  ${ffprobePath.absolutePath}")

                    // Validate FFmpeg
                    val ffmpegVersion = testNativeBinary("FFmpeg", ffmpegPath, ffmpegBinPath, ldLibraryPath)
                    if (ffmpegVersion == null) {
                        logAndNotify("FAILED STAGE: FFmpeg Validation")
                        logAndNotify("REASON: Native FFmpeg execution test failed.")
                        isRunning = false
                        return@Thread
                    }

                    // Validate FFprobe
                    val ffprobeVersion = testNativeBinary("FFprobe", ffprobePath, ffprobeBinPath, ldLibraryPath)
                    if (ffprobeVersion == null) {
                        logAndNotify("FAILED STAGE: FFprobe Validation")
                        logAndNotify("REASON: Native FFprobe execution test failed.")
                        isRunning = false
                        return@Thread
                    }

                    // Configure encoding.xml
                    configureEncodingSettings(configDir, ffmpegPath)

                    // ── 6. Verify .NET Runtime Layout ──
                    val loaderPath = File(nativeLibDir, "libld.so").absolutePath
                    val jellyfinBin = File(nativeLibDir, "libjellyfin.so").absolutePath
                    val jellyfinDll = File(jellyfinHome, "jellyfin.dll")
                    val hostfxrPath = File(fxrDir, "libhostfxr.so")
                    val hostpolicyPath = File(jellyfinHome, "libhostpolicy.so")
                    val coreclrPath = File(jellyfinHome, "libcoreclr.so")

                    logAndNotify(".NET runtime:")
                    logAndNotify("  Version: 9.0.16")
                    logAndNotify("  libhostfxr.so: ${if (hostfxrPath.exists()) "OK" else "MISSING"}")
                    logAndNotify("  libhostpolicy.so: ${if (hostpolicyPath.exists()) "OK" else "MISSING"}")
                    logAndNotify("  libcoreclr.so: ${if (coreclrPath.exists()) "OK" else "MISSING"}")

                    if (!File(loaderPath).exists() || !File(jellyfinBin).exists() || !jellyfinDll.exists() ||
                        !hostfxrPath.exists() || !hostpolicyPath.exists() || !coreclrPath.exists()) {
                        logAndNotify("FAILED STAGE: .NET Runtime Validation")
                        logAndNotify("REASON: One or more required .NET runtime components are missing.")
                        isRunning = false
                        return@Thread
                    }

                    // Network Configuration
                    configureNetworkSettings(configDir)

                    // ── 7. Start Jellyfin Process ──
                    logAndNotify("Jellyfin process started")
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

                    processBuilder.directory(jellyfinHome)

                    val env = processBuilder.environment()
                    env.remove("LD_PRELOAD")
                    env["LD_LIBRARY_PATH"] = ldLibraryPath
                    env["DOTNET_ROOT"] = dotnetRoot.absolutePath
                    env["DOTNET_SYSTEM_GLOBALIZATION_INVARIANT"] = "1"
                    env["DOTNET_gcServer"] = "0"
                    env["DOTNET_System_GC_Server"] = "false"
                    env["DOTNET_GCHeapHardLimit"] = "200000000"
                    env["COREHOST_TRACE"] = "1"

                    val currentPath = env["PATH"] ?: "/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin"
                    env["PATH"] = "$nativeLibDir:${jellyfinHome.absolutePath}:$currentPath"

                    processBuilder.redirectErrorStream(true)

                    val process = processBuilder.start()
                    jellyfinProcess = process

                    // Log output in background
                    Thread {
                        try {
                            val reader = BufferedReader(InputStreamReader(process.inputStream))
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                logAndNotify(line ?: "")
                            }
                        } catch (_: Exception) {}
                    }.start()

                    // ── 8. Verify HTTP Endpoint 127.0.0.1:8096 ──
                    logAndNotify("Checking Jellyfin HTTP endpoint...")
                    val httpSuccess = waitForHttpServer("http://127.0.0.1:8096/health", 60)

                    logAndNotify("HTTP 127.0.0.1:8096: ${if (httpSuccess) "OK" else "FAILED"}")

                    if (httpSuccess) {
                        isRunning = true
                        logAndNotify("=== JELLYFIN SERVER RUNNING ===")
                    } else {
                        logAndNotify("FAILED STAGE: HTTP Health Verification")
                        logAndNotify("REASON: Server process started but http://127.0.0.1:8096 did not respond within timeout.")
                        isRunning = false
                        process.destroy()
                    }

                    val exitCode = process.waitFor()
                    logAndNotify("Jellyfin process exited with code $exitCode")
                } catch (e: Exception) {
                    logAndNotify("ERROR: ${e.message}")
                    Log.e("JellyfinService", "Server error", e)
                } finally {
                    isRunning = false
                    jellyfinProcess = null
                    stopSelf()
                }
            }
        }.start()
    }

    /**
     * Robust, Idempotent Symlink Management Algorithm
     * Handles Cases A, B, C, D, E explicitly with full verification and reporting.
     */
    private fun safeCreateSymlink(sourceFile: File, destFile: File) {
        val srcPath = sourceFile.absolutePath
        val destPath = destFile.absolutePath
        val expectedTarget = srcPath

        logAndNotify("Symlink:")
        logAndNotify("  Source: $srcPath")
        logAndNotify("  Destination: $destPath")

        val destPathObj = Paths.get(destPath)
        val exists = Files.exists(destPathObj) || Files.isSymbolicLink(destPathObj)

        if (!exists) {
            // CASE A: Destination does not exist -> Create
            logAndNotify("  Exists: false")
            logAndNotify("  Action: CREATE")
            try {
                Os.symlink(srcPath, destPath)
                logAndNotify("  Result: SUCCESS")
            } catch (e: Exception) {
                logAndNotify("  Result: FAILED (${e.message})")
                throw e
            }
        } else {
            // Destination exists
            val isSymlink = Files.isSymbolicLink(destPathObj)
            val isDir = destFile.isDirectory && !isSymlink
            val isRegularFile = destFile.isFile && !isSymlink

            logAndNotify("  Exists: true")

            if (isSymlink) {
                logAndNotify("  Type: SYMLINK")
                val existingTarget = try {
                    Files.readSymbolicLink(destPathObj).toString()
                } catch (e: Exception) {
                    "unknown"
                }

                logAndNotify("  Existing target: $existingTarget")
                logAndNotify("  Expected target: $expectedTarget")

                if (existingTarget == expectedTarget) {
                    // CASE B: Already points to correct target -> Reuse
                    logAndNotify("  Action: REUSED")
                    logAndNotify("  Result: SUCCESS")
                } else {
                    // CASE C: Points to wrong target -> Remove symlink ONLY and recreate
                    logAndNotify("  Action: STALE SYMLINK — Recreating")
                    try {
                        Files.delete(destPathObj) // Deletes ONLY the symlink, not target
                        Os.symlink(srcPath, destPath)
                        logAndNotify("  Result: SUCCESS")
                    } catch (e: Exception) {
                        logAndNotify("  Result: FAILED (${e.message})")
                        throw e
                    }
                }
            } else if (isDir) {
                // CASE E: Real Directory -> Refuse deletion
                logAndNotify("  Type: DIRECTORY")
                logAndNotify("  Action: REFUSED (Real directory exists at destination)")
                logAndNotify("  Result: REFUSED")
                return
            } else if (isRegularFile) {
                // CASE D: Real File -> Refuse deletion
                logAndNotify("  Type: FILE")
                logAndNotify("  Action: REFUSED (Real file exists at destination)")
                logAndNotify("  Result: REFUSED")
                return
            }
        }

        // Verification step
        val isLinkNow = Files.isSymbolicLink(destPathObj)
        val resolvedTarget = try { Files.readSymbolicLink(destPathObj).toString() } catch (_: Exception) { "unknown" }
        val targetExists = File(resolvedTarget).exists()
        val verified = isLinkNow && resolvedTarget == expectedTarget && targetExists

        logAndNotify("  Symlink verification:")
        logAndNotify("    Destination exists: true")
        logAndNotify("    Is symbolic link: $isLinkNow")
        logAndNotify("    Resolved target: $resolvedTarget")
        logAndNotify("    Expected target: $expectedTarget")
        logAndNotify("    Target exists: $targetExists")
        logAndNotify("    Verification: ${if (verified) "SUCCESS" else "FAILED"}")

        if (!verified) {
            throw Exception("Symlink verification failed for $destPath")
        }
    }

    private fun testNativeBinary(
        label: String,
        execFile: File,
        elfBinaryFile: File,
        ldLibraryPath: String
    ): String? {
        val exists = execFile.exists()
        val executable = execFile.canExecute()
        val isArm64 = inspectElfArm64(elfBinaryFile)

        logAndNotify("$label:")
        logAndNotify("  Path: ${execFile.absolutePath}")
        logAndNotify("  Exists: $exists")
        logAndNotify("  Executable: $executable")
        logAndNotify("  Architecture: ${if (isArm64) "ARM64" else "UNKNOWN"}")

        if (!exists || !executable || !isArm64) {
            logAndNotify("ERROR: $label failed pre-execution check!")
            return null
        }

        var versionFound: String? = null
        var exitCode = -1
        val output = StringBuilder()

        try {
            val pb = ProcessBuilder(execFile.absolutePath, "-version")
            pb.directory(execFile.parentFile)
            pb.redirectErrorStream(true)
            val pbEnv = pb.environment()
            pbEnv["LD_LIBRARY_PATH"] = ldLibraryPath

            val proc = pb.start()
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            var lineIdx = 0
            var ln: String?
            while (reader.readLine().also { ln = it } != null) {
                val l = ln ?: continue
                output.append(l).append("\n")
                if (lineIdx < 5) {
                    logAndNotify("  [$label] $l")
                    if (l.contains("ffmpeg version") || l.contains("ffprobe version")) {
                        versionFound = l.substringBefore("Copyright").trim()
                    }
                }
                lineIdx++
            }
            exitCode = proc.waitFor()
        } catch (e: Exception) {
            logAndNotify("ERROR: Native execution of $label failed: ${e.message}")
            return null
        }

        logAndNotify("  Exit code: $exitCode")
        if (exitCode == 0 && versionFound != null) {
            logAndNotify("  Version: $versionFound")
            logAndNotify("  Test: SUCCESS")
            return versionFound
        } else {
            logAndNotify("ERROR: $label test failed! Exit code $exitCode. Output:\n${output.take(500)}")
            return null
        }
    }

    private fun waitForHttpServer(urlStr: String, timeoutSeconds: Int): Boolean {
        val startTime = System.currentTimeMillis()
        val endTime = startTime + (timeoutSeconds * 1000)

        while (System.currentTimeMillis() < endTime) {
            try {
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 1000
                conn.readTimeout = 1000
                conn.requestMethod = "GET"
                val code = conn.responseCode
                conn.disconnect()
                if (code == 200 || code == 302 || code == 401) {
                    return true
                }
            } catch (_: Exception) {}
            Thread.sleep(1000)
        }
        return false
    }

    private fun runCleanMigration(jellyfinHome: File, configDir: File) {
        try {
            File(jellyfinHome, "ffmpeg").delete()
            File(jellyfinHome, "ffprobe").delete()

            val encodingXml = File(configDir, "encoding.xml")
            if (encodingXml.exists()) {
                val content = encodingXml.readText()
                if (content.contains("files/jellyfin/ffmpeg")) {
                    logAndNotify("Clean migration: Detected stale encoding.xml path. Cleaning...")
                    encodingXml.delete()
                }
            }
        } catch (e: Exception) {
            logAndNotify("WARNING: Clean migration step encountered error: ${e.message}")
        }
    }

    private fun configureEncodingSettings(configDir: File, ffmpegPath: File) {
        val encodingXml = File(configDir, "encoding.xml")
        val xmlContent = """<?xml version="1.0" encoding="utf-8"?>
<EncodingOptions xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
  <EncoderAppPath>${ffmpegPath.absolutePath}</EncoderAppPath>
  <EncoderAppPathDisplay>${ffmpegPath.absolutePath}</EncoderAppPathDisplay>
</EncodingOptions>"""
        try {
            encodingXml.writeText(xmlContent)
            logAndNotify("encoding.xml:")
            logAndNotify("  FFmpeg path: ${ffmpegPath.absolutePath}")
            logAndNotify("  Configuration: VALID")
        } catch (e: Exception) {
            logAndNotify("WARNING: Failed to write encoding.xml: ${e.message}")
        }
    }

    private fun configureNetworkSettings(configDir: File) {
        val networkXml = File(configDir, "network.xml")
        val defaultXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <NetworkConfiguration xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
              <RequireHttps>false</RequireHttps>
              <CertificatePath />
              <CertificatePassword />
              <BaseUrl />
              <EnableRemoteAccess>true</EnableRemoteAccess>
              <LocalNetworkSubnets />
              <LocalNetworkAddresses />
              <EnableIPv4>true</EnableIPv4>
              <EnableIPv6>false</EnableIPv6>
              <IsStartupWizardCompleted>false</IsStartupWizardCompleted>
            </NetworkConfiguration>
        """.trimIndent()

        try {
            if (!networkXml.exists()) {
                networkXml.writeText(defaultXml)
            } else {
                var content = networkXml.readText()
                if (content.contains("<EnableRemoteAccess>false</EnableRemoteAccess>")) {
                    content = content.replace("<EnableRemoteAccess>false</EnableRemoteAccess>", "<EnableRemoteAccess>true</EnableRemoteAccess>")
                    networkXml.writeText(content)
                }
            }
        } catch (_: Exception) {}
    }

    private fun inspectElfArm64(file: File): Boolean {
        try {
            java.io.FileInputStream(file).use { fis ->
                val header = ByteArray(64)
                if (fis.read(header) == 64) {
                    if (header[0] == 0x7F.toByte() && header[1] == 'E'.code.toByte() &&
                        header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte()) {
                        val is64Bit = header[4] == 2.toByte()
                        val machine = ((header[19].toInt() and 0xFF) shl 8) or (header[18].toInt() and 0xFF)
                        return is64Bit && machine == 183
                    }
                }
            }
        } catch (_: Exception) {}
        return false
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

    fun stopServer() {
        logAndNotify("Stopping Jellyfin Server...")
        jellyfinProcess?.destroy()
        jellyfinProcess = null
        isRunning = false
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
        private val startupLock = Object()
    }
}
