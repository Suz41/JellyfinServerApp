package com.example.jellyfinserver.core

import android.content.Context
import android.os.Build
import com.example.jellyfinserver.core.LogManager.log
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.util.zip.ZipInputStream

class ServerManager(private val context: Context) {

    private var jellyfinProcess: Process? = null
    var state = ServerState.STOPPED
        private set

    private var onStateChangedListener: ((ServerState) -> Unit)? = null
    private var kestrelListeningInLog = false

    companion object {
        private val startupLock = Object()
    }

    data class HttpProbeResult(
        val url: String,
        val success: Boolean,
        val statusCode: Int,
        val responseTimeMs: Long,
        val responseBody: String,
        val headers: Map<String, List<String>>,
        val exceptionClass: String,
        val exceptionMessage: String,
        val stackTrace: String
    )

    private fun updateState(newState: ServerState) {
        state = newState
        onStateChangedListener?.invoke(newState)
    }

    fun startServer(onStateChanged: (ServerState) -> Unit) {
        this.onStateChangedListener = onStateChanged
        if (state != ServerState.STOPPED && state != ServerState.START_FAILED && state != ServerState.PROCESS_EXITED && state != ServerState.TCP_BIND_FAILED && state != ServerState.HTTP_NOT_READY) return
        Thread {
            synchronized(startupLock) {
                var currentPid = -1L
                var processStartTime = 0L
                var tcpStatus = "FAIL"
                var detectedUrls = "N/A"
                var ffmpegBinaryFound = false
                var ffmpegArch = "UNKNOWN"
                var ffmpegDynLinking = "UNKNOWN"
                
                var failureCategory = ""
                var failureReason = ""
                var recommendedDiagnostic = ""
                kestrelListeningInLog = false

                val probeResults = mutableMapOf<String, HttpProbeResult>()

                try {
                    updateState(ServerState.STARTING)
                    log("=== PHASE 1: JELLYFIN .NET POC STARTUP ===")

                    // [1] Android environment
                    val deviceAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
                    log("[1] Android environment:")
                    log("    Android API: ${Build.VERSION.SDK_INT}")
                    log("    Device ABI: $deviceAbi")

                    if (!deviceAbi.contains("arm64") && !deviceAbi.contains("aarch64")) {
                        failureCategory = "ABI_VERIFICATION"
                        failureReason = "Unsupported device ABI $deviceAbi. Jellyfin requires arm64-v8a."
                        recommendedDiagnostic = "Ensure the device is an ARM64-v8a device."
                        log("FAILED STAGE: ABI Verification")
                        log("REASON: $failureReason")
                        updateState(ServerState.START_FAILED)
                        printFinalSummary(deviceAbi, currentPid, tcpStatus, detectedUrls, ffmpegBinaryFound, ffmpegArch, ffmpegDynLinking, failureCategory, failureReason, recommendedDiagnostic, probeResults)
                        return@Thread
                    }

                    // [2] Runtime & Directory Initialization
                    val nativeLibDir = context.applicationInfo.nativeLibraryDir
                    val jellyfinHome = File(context.filesDir, "jellyfin")
                    val dataDir   = File(jellyfinHome, "data").also { it.mkdirs() }
                    val configDir = File(jellyfinHome, "config").also { it.mkdirs() }
                    val cacheDir  = File(jellyfinHome, "cache").also { it.mkdirs() }
                    val logDir    = File(jellyfinHome, "log").also { it.mkdirs() }
                    val webDir    = File(jellyfinHome, "jellyfin-web")
                    val dotnetRoot = File(context.filesDir, "dotnet")

                    log("[2] Runtime initialization:")
                    log("    Native library directory: $nativeLibDir")
                    log("    Jellyfin root: ${jellyfinHome.absolutePath}")
                    log("    .NET root: ${dotnetRoot.absolutePath}")

                    // Extract assets if missing
                    val markerFile = File(jellyfinHome, ".extracted_marker")
                    if (!markerFile.exists()) {
                        log("Extracting Jellyfin managed assets...")
                        val tempDir = File(context.filesDir, "jellyfin_temp")
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
                            log("Assets extracted successfully.")
                        } catch (e: Exception) {
                            failureCategory = "ASSETS_EXTRACTION"
                            failureReason = "Assets extraction failed: ${e.message}"
                            recommendedDiagnostic = "Check device free disk space and write permissions to context.filesDir."
                            log("FAILED STAGE: Assets Extraction")
                            log("REASON: $failureReason")
                            if (tempDir.exists()) tempDir.deleteRecursively()
                            updateState(ServerState.START_FAILED)
                            printFinalSummary(deviceAbi, currentPid, tcpStatus, detectedUrls, ffmpegBinaryFound, ffmpegArch, ffmpegDynLinking, failureCategory, failureReason, recommendedDiagnostic, probeResults)
                            return@Thread
                        }
                    }

                    updateState(ServerState.RUNTIME_INITIALIZED)

                    // [3] Jellyfin Assembly Discovery & Symlink Layout
                    log("[3] Jellyfin assembly discovery:")
                    val fxrDir = File(dotnetRoot, "host/fxr/9.0.16")
                    val sharedDir = File(dotnetRoot, "shared/Microsoft.NETCore.App/9.0.16")
                    fxrDir.mkdirs()
                    sharedDir.mkdirs()

                    val libFiles = File(nativeLibDir).listFiles()
                    if (libFiles != null) {
                        for (lib in libFiles) {
                            if (lib.name == "libhostfxr.so") {
                                RuntimeManager.safeCreateSymlink(lib, File(fxrDir, "libhostfxr.so"))
                            }
                            RuntimeManager.safeCreateSymlink(lib, File(jellyfinHome, lib.name))
                            RuntimeManager.safeCreateSymlink(lib, File(dotnetRoot, lib.name))
                            RuntimeManager.safeCreateSymlink(lib, File(sharedDir, lib.name))

                            if (lib.name == "libssl.so") {
                                RuntimeManager.safeCreateSymlink(lib, File(jellyfinHome, "libssl.so.3"))
                                RuntimeManager.safeCreateSymlink(lib, File(dotnetRoot, "libssl.so.3"))
                            } else if (lib.name == "libcrypto.so") {
                                RuntimeManager.safeCreateSymlink(lib, File(jellyfinHome, "libcrypto.so.3"))
                                RuntimeManager.safeCreateSymlink(lib, File(dotnetRoot, "libcrypto.so.3"))
                            } else if (lib.name == "libg_libc.so") {
                                RuntimeManager.safeCreateSymlink(lib, File(jellyfinHome, "libc.so.6"))
                                RuntimeManager.safeCreateSymlink(lib, File(jellyfinHome, "ld-linux-aarch64.so.1"))
                            } else if (lib.name == "libg_m.so") {
                                RuntimeManager.safeCreateSymlink(lib, File(jellyfinHome, "libm.so.6"))
                            } else if (lib.name == "libg_dl.so") {
                                RuntimeManager.safeCreateSymlink(lib, File(jellyfinHome, "libdl.so.2"))
                            } else if (lib.name == "libg_pthread.so") {
                                RuntimeManager.safeCreateSymlink(lib, File(jellyfinHome, "libpthread.so.0"))
                            } else if (lib.name == "libg_rt.so") {
                                RuntimeManager.safeCreateSymlink(lib, File(jellyfinHome, "librt.so.1"))
                            }
                        }
                    }

                    // Verify .NET 9 Runtime
                    val runtimeValid = RuntimeManager.verifyDotNetRuntime(nativeLibDir, jellyfinHome, dotnetRoot)
                    if (!runtimeValid) {
                        failureCategory = "NET_RUNTIME_VALIDATION"
                        failureReason = "Required .NET 9 runtime assemblies are missing or corrupted."
                        recommendedDiagnostic = "Reinstall the app or check if files under $dotnetRoot were deleted by system cleaner."
                        log("FAILED STAGE: .NET Runtime Validation")
                        log("REASON: $failureReason")
                        updateState(ServerState.START_FAILED)
                        printFinalSummary(deviceAbi, currentPid, tcpStatus, detectedUrls, ffmpegBinaryFound, ffmpegArch, ffmpegDynLinking, failureCategory, failureReason, recommendedDiagnostic, probeResults)
                        return@Thread
                    }

                    // Setup and Validate Media Engine (FFmpeg/FFprobe)
                    val mediaEngineValid = MediaEngineManager.setupAndValidateMediaEngine(
                        nativeLibDir = nativeLibDir,
                        jellyfinHome = jellyfinHome,
                        configDir = configDir,
                        assetsExtractor = { assetName, destFile ->
                            extractAssetByName(assetName, destFile)
                        }
                    )
                    
                    ffmpegBinaryFound = File(jellyfinHome, "ffmpeg.bin").exists()
                    if (ffmpegBinaryFound) {
                        ffmpegArch = "ARM64"
                        ffmpegDynLinking = "PASS"
                    }

                    if (!mediaEngineValid) {
                        failureCategory = "MEDIA_ENGINE_VALIDATION"
                        failureReason = "FFmpeg/FFprobe validation failed!"
                        recommendedDiagnostic = "Check the logs to see if libproot.so failed to link or if the ffmpeg wrapper failed to run."
                        log("FAILED STAGE: Media Engine (FFmpeg) Validation")
                        log("REASON: $failureReason")
                        updateState(ServerState.START_FAILED)
                        printFinalSummary(deviceAbi, currentPid, tcpStatus, detectedUrls, ffmpegBinaryFound, ffmpegArch, ffmpegDynLinking, failureCategory, failureReason, recommendedDiagnostic, probeResults)
                        return@Thread
                    }

                    NetworkManager.configureNetworkSettings(configDir)

                    updateState(ServerState.JELLYFIN_INITIALIZING)

                    // [4] Jellyfin Process/Runtime Startup
                    log("[4] Jellyfin process/runtime startup:")
                    
                    processStartTime = System.currentTimeMillis()
                    val process = NativeProcessLauncher.launchJellyfinProcess(
                        nativeLibDir,
                        jellyfinHome,
                        dotnetRoot,
                        dataDir,
                        configDir,
                        cacheDir,
                        logDir,
                        webDir
                    )
                    jellyfinProcess = process

                    // Background thread to read logs and monitor Kestrel binding
                    Thread {
                        try {
                            val reader = BufferedReader(InputStreamReader(process.inputStream))
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                val currentLine = line ?: ""
                                LogManager.log(currentLine)
                                if (currentLine.contains("Now listening on:") || currentLine.contains("Listening on")) {
                                    log("[KESTREL DETECTED] Server listening detected in log stream: $currentLine")
                                    kestrelListeningInLog = true
                                }
                            }
                        } catch (_: Exception) {}
                    }.start()

                    currentPid = try {
                        val field = process.javaClass.getDeclaredField("pid")
                        field.isAccessible = true
                        (field.get(process) as Int).toLong()
                    } catch (_: Exception) {
                        try {
                            val method = process.javaClass.getMethod("pid")
                            method.invoke(process) as Long
                        } catch (_: Exception) {
                            -1L
                        }
                    }

                    log("=== PROCESS ===")
                    log("PID: $currentPid")
                    log("Start Time: $processStartTime")
                    log("Working Directory: ${jellyfinHome.absolutePath}")
                    log("Alive: ${process.isAlive}")

                    updateState(ServerState.PROCESS_STARTED)

                    // Port ownership debug check
                    logSocketOwnershipDiagnostics(8096)

                    // [5] HTTP server startup
                    log("[5] HTTP server startup: Waiting for listening socket on 8096...")
                    updateState(ServerState.HTTP_WAITING)

                    // Check process alive status continuously and poll TCP
                    var isPortOpen = false
                    val maxTcpAttempts = 60
                    log("=== TCP ===")
                    for (attempt in 1..maxTcpAttempts) {
                        if (!process.isAlive) {
                            failureCategory = "PROCESS_CRASHED"
                            val exitVal = try { process.exitValue() } catch (_: Exception) { -1 }
                            failureReason = "Jellyfin process exited prematurely with code $exitVal during TCP check phase."
                            recommendedDiagnostic = "Inspect the Jellyfin/Kestrel logs below for startup exceptions, SocketExceptions, or permission issues."
                            log("FAILED STAGE: Process Exit Detected")
                            log("REASON: $failureReason")
                            updateState(ServerState.PROCESS_EXITED)
                            printFinalSummary(deviceAbi, currentPid, tcpStatus, detectedUrls, ffmpegBinaryFound, ffmpegArch, ffmpegDynLinking, failureCategory, failureReason, recommendedDiagnostic, probeResults)
                            return@Thread
                        }

                        updateState(ServerState.TCP_CHECK)
                        log("[TCP] Checking TCP 127.0.0.1:8096 (Attempt $attempt/$maxTcpAttempts)...")
                        if (checkTcpPort("127.0.0.1", 8096, 1000)) {
                            isPortOpen = true
                            tcpStatus = "PASS"
                            log("[TCP] TCP connection: PASS")
                            break
                        }
                        Thread.sleep(1000)
                    }

                    if (!isPortOpen) {
                        failureCategory = "TCP_BIND_FAILED"
                        failureReason = "Kestrel did not bind to port 8096 within 60 seconds."
                        recommendedDiagnostic = "Ensure no other app is using port 8096, and verify Kestrel's configuration."
                        log("FAILED STAGE: TCP Port Binding Verification")
                        log("REASON: $failureReason")
                        updateState(ServerState.TCP_BIND_FAILED)
                        printFinalSummary(deviceAbi, currentPid, tcpStatus, detectedUrls, ffmpegBinaryFound, ffmpegArch, ffmpegDynLinking, failureCategory, failureReason, recommendedDiagnostic, probeResults)
                        return@Thread
                    }

                    // [6] HTTP server readiness check on separate endpoints
                    log("=== HTTP ===")
                    var isHttpReady = false
                    val maxHttpAttempts = 240
                    val endpoints = listOf(
                        "http://127.0.0.1:8096/",
                        "http://127.0.0.1:8096/web/",
                        "http://127.0.0.1:8096/health",
                        "http://127.0.0.1:8096/System/Info/Public"
                    )

                    for (attempt in 1..maxHttpAttempts) {
                        if (!process.isAlive) {
                            failureCategory = "PROCESS_CRASHED"
                            val exitVal = try { process.exitValue() } catch (_: Exception) { -1 }
                            failureReason = "Jellyfin process exited prematurely with code $exitVal during HTTP check phase."
                            recommendedDiagnostic = "Inspect the Jellyfin/Kestrel logs below for runtime errors or initialization crashes."
                            log("FAILED STAGE: Process Exit Detected")
                            log("REASON: $failureReason")
                            updateState(ServerState.PROCESS_EXITED)
                            printFinalSummary(deviceAbi, currentPid, tcpStatus, detectedUrls, ffmpegBinaryFound, ffmpegArch, ffmpegDynLinking, failureCategory, failureReason, recommendedDiagnostic, probeResults)
                            return@Thread
                        }

                        log("[HTTP] Attempt $attempt/$maxHttpAttempts: Probing endpoints...")
                        
                        for (endpoint in endpoints) {
                            val result = probeHttpEndpoint(endpoint, 3000)
                            probeResults[endpoint] = result
                            
                            log("[HTTP PROBE] Endpoint: ${result.url}")
                            log("  Success: ${result.success}")
                            log("  Status Code: ${result.statusCode}")
                            log("  Response Time: ${result.responseTimeMs} ms")
                            if (result.exceptionClass.isNotEmpty()) {
                                log("  Exception: ${result.exceptionClass} - ${result.exceptionMessage}")
                                log("  StackTrace: ${result.stackTrace.take(300)}")
                            }
                        }

                        val resPublic = probeResults["http://127.0.0.1:8096/System/Info/Public"]
                        val resWeb = probeResults["http://127.0.0.1:8096/web/"]
                        val resRoot = probeResults["http://127.0.0.1:8096/"]

                        val isApiReady = resPublic != null && resPublic.success && 
                                (resPublic.statusCode in 200..299 || resPublic.statusCode == 302 || resPublic.statusCode == 401)
                        val isWebReady = resWeb != null && resWeb.success && resWeb.statusCode == 200
                        val isRootReady = resRoot != null && resRoot.success && (resRoot.statusCode == 200 || resRoot.statusCode == 302)

                        val isWeb503 = (resWeb != null && resWeb.statusCode == 503) || (resRoot != null && resRoot.statusCode == 503)
                        val isServerFullyReady = isApiReady && (isWebReady || isRootReady) && !isWeb503

                        if (isServerFullyReady) {
                            isHttpReady = true
                            updateState(ServerState.RUNNING)
                            break
                        } else if (isWebReady || isWeb503) {
                            updateState(ServerState.WEB_STATIC_ONLY)
                        } else {
                            updateState(ServerState.API_NOT_READY)
                        }

                        Thread.sleep(1000)
                    }

                    if (isHttpReady) {
                        // [7] SERVER RUNNING
                        log("=== FINAL STATUS ===")
                        log("JELLYFIN SERVER READY")
                        detectedUrls = "http://127.0.0.1:8096"
                        updateState(ServerState.RUNNING)
                        printFinalSummary(deviceAbi, currentPid, tcpStatus, detectedUrls, ffmpegBinaryFound, ffmpegArch, ffmpegDynLinking, failureCategory, failureReason, recommendedDiagnostic, probeResults)
                    } else {
                        failureCategory = "HTTP_NOT_READY"
                        failureReason = "Jellyfin HTTP port 8096 is open but did not return a valid HTTP response within the timeout."
                        recommendedDiagnostic = "Ensure 'usesCleartextTraffic' is enabled in AndroidManifest.xml and Kestrel is not crashing or blocking requests."
                        log("FAILED STAGE: HTTP Server Verification")
                        log("REASON: $failureReason")
                        updateState(ServerState.HTTP_NOT_READY)
                        printFinalSummary(deviceAbi, currentPid, tcpStatus, detectedUrls, ffmpegBinaryFound, ffmpegArch, ffmpegDynLinking, failureCategory, failureReason, recommendedDiagnostic, probeResults)
                    }

                    // Keep thread waiting for process to finish
                    val exitCode = process.waitFor()
                    log("Jellyfin process exited with code $exitCode")
                    
                    if (state == ServerState.RUNNING) {
                        updateState(ServerState.PROCESS_EXITED)
                    }
                } catch (e: Exception) {
                    log("ERROR: ${e.message}")
                    failureCategory = "PROCESS_LAUNCH_FAILED"
                    failureReason = "Process launch or pipeline exception: ${e.message}"
                    recommendedDiagnostic = "Ensure all binary configurations and environment parameters are valid."
                    updateState(ServerState.START_FAILED)
                    printFinalSummary("unknown", currentPid, tcpStatus, detectedUrls, ffmpegBinaryFound, ffmpegArch, ffmpegDynLinking, failureCategory, failureReason, recommendedDiagnostic, probeResults)
                } finally {
                    jellyfinProcess = null
                }
            }
        }.start()
    }

    private fun printFinalSummary(
        abi: String,
        pid: Long,
        tcpStatus: String,
        detectedUrls: String,
        ffmpegBinaryFound: Boolean,
        ffmpegArch: String,
        ffmpegDynLinking: String,
        failureCategory: String,
        failureReason: String,
        recommendedDiagnostic: String,
        probeResults: Map<String, HttpProbeResult>
    ) {
        val appCache = File(context.cacheDir, "tmp")
        val jellyfinHome = File(context.filesDir, "jellyfin").absolutePath

        val isProcessAlive = jellyfinProcess?.isAlive == true
        val isKestrelListening = kestrelListeningInLog || tcpStatus == "PASS"

        log("[DIAGNOSTIC DETAILS]")
        log("  Device ABI: $abi")
        log("  Jellyfin PID: $pid")
        log("  Detected URL: $detectedUrls")
        log("  FFmpeg Arch: $ffmpegArch")
        log("  FFmpeg Binary: ${if (ffmpegBinaryFound) "FOUND" else "MISSING"}")
        log("  FFmpeg Linking: $ffmpegDynLinking")
        log("  Cache Temp: ${appCache.absolutePath}")
        log("  Jellyfin Home: $jellyfinHome")
        log("--------------------")
        
        val resRoot = probeResults["http://127.0.0.1:8096/"]
        val resWeb = probeResults["http://127.0.0.1:8096/web/"]
        val resHealth = probeResults["http://127.0.0.1:8096/health"]
        val resPublic = probeResults["http://127.0.0.1:8096/System/Info/Public"]

        val isApiReady = resPublic != null && resPublic.success && 
                (resPublic.statusCode in 200..299 || resPublic.statusCode == 302 || resPublic.statusCode == 401)

        val finalStateStr = when (state) {
            ServerState.RUNNING -> "RUNNING"
            ServerState.API_NOT_READY -> "API_NOT_READY"
            ServerState.WEB_STATIC_ONLY -> "API_NOT_READY"
            ServerState.HTTP_NOT_READY -> "HTTP_NOT_READY"
            else -> "ERROR"
        }

        log("=== JELLYFIN SERVER DIAGNOSTICS ===")
        log("")
        log("Process:")
        log("    ${if (isProcessAlive) "ALIVE" else "DEAD"}")
        log("")
        log("Kestrel:")
        log("    ${if (isKestrelListening) "LISTENING" else "NOT LISTENING"}")
        log("")
        log("TCP 127.0.0.1:8096:")
        log("    $tcpStatus")
        log("")
        
        log("GET /:")
        if (resRoot != null) {
            log("    ${if (resRoot.success) "PASS" else "FAIL"}")
            log("    HTTP STATUS: ${resRoot.statusCode}")
        } else {
            log("    FAIL")
            log("    HTTP STATUS: N/A")
        }
        log("")

        log("GET /web/:")
        if (resWeb != null) {
            log("    ${if (resWeb.success) "PASS" else "FAIL"}")
            log("    HTTP STATUS: ${resWeb.statusCode}")
        } else {
            log("    FAIL")
            log("    HTTP STATUS: N/A")
        }
        log("")

        log("GET /health:")
        if (resHealth != null) {
            log("    ${if (resHealth.success) "PASS" else "FAIL"}")
            log("    HTTP STATUS: ${resHealth.statusCode}")
        } else {
            log("    FAIL")
            log("    HTTP STATUS: N/A")
        }
        log("")

        log("GET /System/Info/Public:")
        if (resPublic != null) {
            log("    ${if (resPublic.success) "PASS" else "FAIL"}")
            log("    HTTP STATUS: ${resPublic.statusCode}")
        } else {
            log("    FAIL")
            log("    HTTP STATUS: N/A")
        }
        log("")

        log("Jellyfin API:")
        log("    ${if (isApiReady) "READY" else "NOT READY"}")
        log("")
        
        log("FFmpeg:")
        log("    ${if (ffmpegBinaryFound && ffmpegDynLinking == "PASS") "PASS" else "FAIL"}")
        log("")

        log("Web Client:")
        log("    ${if (isApiReady) "CONNECTED" else "SERVER UNAVAILABLE"}")
        log("")

        log("FINAL STATE:")
        log("    $finalStateStr")
        log("===================================")

        // Find the first failed or problematic probe to print the root exception details
        val firstFailedProbe = probeResults.values.firstOrNull { 
            !it.success || (it.statusCode != -1 && it.statusCode !in 200..399 && it.statusCode != 401)
        }
        if (firstFailedProbe != null) {
            log("=== ROOT EXCEPTION DETAILS (${firstFailedProbe.url}) ===")
            if (firstFailedProbe.exceptionClass.isNotEmpty()) {
                log("Exception: ${firstFailedProbe.exceptionClass}")
                log("Message: ${firstFailedProbe.exceptionMessage}")
                log("Stacktrace:")
                log(firstFailedProbe.stackTrace)
            } else {
                log("HTTP Status: ${firstFailedProbe.statusCode}")
                log("Response Headers:")
                firstFailedProbe.headers.forEach { (k, v) -> log("  $k: $v") }
                log("Response Body (first 2KB):")
                log(firstFailedProbe.responseBody.take(2048))
            }
            log("==================================================")
        }

        if (state != ServerState.RUNNING && state != ServerState.STOPPED && state != ServerState.STOPPING) {
            val lastLogs = getLastLogLines(50)
            log("=== FAILURE DETAIL ===")
            log("FAILURE CATEGORY: $failureCategory")
            log("FAILURE REASON: $failureReason")
            log("PROCESS STATE: ${if (isProcessAlive) "ALIVE" else "DEAD"}")
            log("LAST JELLYFIN STDOUT/LOGS (Last 50 lines):")
            log(lastLogs)
            log("RECOMMENDED NEXT DIAGNOSTIC: $recommendedDiagnostic")
            log("==================================================")
        }
    }

    private fun logSocketOwnershipDiagnostics(targetPort: Int) {
        log("=== PORT OWNERSHIP DIAGNOSTICS ===")
        log("Checking ownership of port $targetPort...")
        
        // Try reading /proc/net/tcp
        val tcpFile = File("/proc/net/tcp")
        if (tcpFile.exists() && tcpFile.canRead()) {
            try {
                val lines = tcpFile.readLines()
                log("Successfully read /proc/net/tcp (${lines.size} entries):")
                lines.take(10).forEach { log("  $it") }
            } catch (e: Exception) {
                log("Failed to read /proc/net/tcp: ${e.message}")
            }
        } else {
            log("Access to /proc/net/tcp is RESTRICTED (standard Android security policy on API 28+).")
        }

        // Try running netstat
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("netstat", "-an"))
            val reader = proc.inputStream.bufferedReader()
            val lines = reader.readLines()
            if (lines.isNotEmpty()) {
                log("netstat output (${lines.size} lines):")
                lines.take(15).forEach { log("  $it") }
            } else {
                log("netstat returned no output.")
            }
        } catch (e: Exception) {
            log("Cannot execute netstat command: ${e.message}")
        }
        log("==================================")
    }

    private fun getLastLogLines(n: Int): String {
        val logs = LogManager.getLogs()
        val lines = logs.split("\n")
        if (lines.size <= n) return logs
        return lines.takeLast(n).joinToString("\n")
    }

    private fun checkTcpPort(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun probeHttpEndpoint(urlStr: String, timeoutMs: Int): HttpProbeResult {
        val startTime = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        var success = false
        var statusCode = -1
        var responseBody = ""
        var headers: Map<String, List<String>> = emptyMap()
        var exceptionClass = ""
        var exceptionMessage = ""
        var stackTrace = ""

        try {
            val url = URL(urlStr)
            conn = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.requestMethod = "GET"
            
            statusCode = conn.responseCode
            headers = conn.headerFields ?: emptyMap()
            
            val stream = if (statusCode in 200..299) conn.inputStream else conn.errorStream
            if (stream != null) {
                responseBody = stream.bufferedReader().use { it.readText() }
            }
            success = true
        } catch (e: Exception) {
            exceptionClass = e.javaClass.name
            exceptionMessage = e.message ?: ""
            val writer = StringWriter()
            e.printStackTrace(PrintWriter(writer))
            stackTrace = writer.toString()
        } finally {
            conn?.disconnect()
        }

        val elapsed = System.currentTimeMillis() - startTime
        return HttpProbeResult(
            url = urlStr,
            success = success,
            statusCode = statusCode,
            responseTimeMs = elapsed,
            responseBody = responseBody,
            headers = headers,
            exceptionClass = exceptionClass,
            exceptionMessage = exceptionMessage,
            stackTrace = stackTrace
        )
    }

    fun stopServer() {
        if (state == ServerState.STOPPED) return
        updateState(ServerState.STOPPING)
        log("Stopping Jellyfin Server...")
        jellyfinProcess?.destroy()
        jellyfinProcess = null
        updateState(ServerState.STOPPED)
    }

    private fun extractAssetByName(assetName: String, destFile: File) {
        val zipInputStream = ZipInputStream(context.assets.open("jellyfin_assets.zip"))
        var entry = zipInputStream.nextEntry
        while (entry != null) {
            if (entry.name == assetName) {
                destFile.outputStream().use { out ->
                    zipInputStream.copyTo(out)
                }
                break
            }
            zipInputStream.closeEntry()
            entry = zipInputStream.nextEntry
        }
        zipInputStream.close()
    }

    private fun extractAssets(destDir: File) {
        val zipInputStream = ZipInputStream(context.assets.open("jellyfin_assets.zip"))
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
}
