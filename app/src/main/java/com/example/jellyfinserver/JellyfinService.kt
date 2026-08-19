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

                // .NET shared framework directory
                val dotnetRoot = File(jellyfinHome, "dotnet")
                val fxrDir = File(dotnetRoot, "host/fxr/9.0.16")
                val sharedDir = File(dotnetRoot, "shared/Microsoft.NETCore.App/9.0.16")
                fxrDir.mkdirs()
                sharedDir.mkdirs()

                // Construct .NET runtime structure with symbolic links to nativeLibDir
                try {
                    // Clean up old .so symlinks, versioned lib symlinks, and old binaries
                    jellyfinHome.listFiles()?.forEach { file ->
                        if (file.name.endsWith(".so") || file.name.contains(".so.") ||
                            file.name == "ffmpeg" || file.name == "ffprobe" ||
                            file.name == "ffmpeg.bin" || file.name == "ffprobe.bin") {
                            file.delete()
                        }
                    }

                    val libFiles = File(nativeLibDir).listFiles()
                    if (libFiles != null && libFiles.isNotEmpty()) {
                        for (lib in libFiles) {
                            if (lib.name == "libhostfxr.so") {
                                val symlinkFile = File(fxrDir, "libhostfxr.so")
                                Os.symlink(lib.absolutePath, symlinkFile.absolutePath)
                            }

                            // Skip libffmpeg.so / libffprobe.so — we now use jellyfin-ffmpeg from assets
                            if (lib.name == "libffmpeg.so" || lib.name == "libffprobe.so") continue

                            val symlinkAppFile = File(jellyfinHome, lib.name)
                            Os.symlink(lib.absolutePath, symlinkAppFile.absolutePath)

                            val symlinkRootFile = File(dotnetRoot, lib.name)
                            Os.symlink(lib.absolutePath, symlinkRootFile.absolutePath)

                            // Versioned OpenSSL symlinks for .NET
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
                            }

                            // Versioned glibc symlinks for jellyfin-ffmpeg:
                            // jellyfin-ffmpeg is patched to use libg_*.so names, which are already
                            // symlinked to jellyfinHome. But we also add standard versioned names
                            // (libc.so.6 etc.) as symlinks pointing to the same libg_*.so files,
                            // so that any uninstrumented dependency resolution also works.
                            when (lib.name) {
                                "libg_libc.so" -> {
                                    Os.symlink(lib.absolutePath, File(jellyfinHome, "libc.so.6").absolutePath)
                                    Os.symlink(lib.absolutePath, File(jellyfinHome, "ld-linux-aarch64.so.1").absolutePath)
                                }
                                "libg_m.so" -> Os.symlink(lib.absolutePath, File(jellyfinHome, "libm.so.6").absolutePath)
                                "libg_dl.so" -> Os.symlink(lib.absolutePath, File(jellyfinHome, "libdl.so.2").absolutePath)
                                "libg_pthread.so" -> Os.symlink(lib.absolutePath, File(jellyfinHome, "libpthread.so.0").absolutePath)
                            }

                            // Symlink to shared framework directory
                            val symlinkFile = File(sharedDir, lib.name)
                            Os.symlink(lib.absolutePath, symlinkFile.absolutePath)
                        }
                    } else {
                        logAndNotify("WARNING: No native libraries found in $nativeLibDir")
                    }
                } catch (e: Exception) {
                    logAndNotify("ERROR: Failed to initialize .NET runtime layout: ${e.message}")
                    isRunning = false
                    return@Thread
                }

                // ── Extract and set up jellyfin-ffmpeg (glibc-linked, patched to use libg_*.so) ──
                val loaderPath = File(nativeLibDir, "libld.so").absolutePath
                val ffmpegBinPath = File(jellyfinHome, "ffmpeg.bin")
                val ffprobeBinPath = File(jellyfinHome, "ffprobe.bin")
                val ffmpegPath = File(jellyfinHome, "ffmpeg")      // wrapper script
                val ffprobePath = File(jellyfinHome, "ffprobe")    // wrapper script

                try {
                    // Extract ffmpeg.bin and ffprobe.bin from the assets zip if not already current
                    for ((assetName, destFile) in listOf(
                        "ffmpeg.bin" to ffmpegBinPath,
                        "ffprobe.bin" to ffprobeBinPath
                    )) {
                        logAndNotify("Extracting $assetName from assets...")
                        assets.open("jellyfin_assets.zip").use { assetStream ->
                            val zis = java.util.zip.ZipInputStream(assetStream)
                            var entry = zis.nextEntry
                            while (entry != null) {
                                if (entry.name == assetName) {
                                    destFile.outputStream().use { out ->
                                        zis.copyTo(out)
                                    }
                                    destFile.setExecutable(true, false)
                                    logAndNotify("  Extracted: ${destFile.absolutePath} (${destFile.length() / 1024 / 1024}MB)")
                                    break
                                }
                                zis.closeEntry()
                                entry = zis.nextEntry
                            }
                            zis.close()
                        }
                    }

                    // Write wrapper shell scripts:
                    // #!/system/bin/sh
                    // exec /path/to/libld.so /path/to/ffmpeg.bin "$@"
                    // This makes our glibc loader run the jellyfin-ffmpeg binary with our libg_*.so deps.
                    for ((wrapperFile, binFile) in listOf(
                        ffmpegPath to ffmpegBinPath,
                        ffprobePath to ffprobeBinPath
                    )) {
                        wrapperFile.writeText(
                            "#!/system/bin/sh\nexec $loaderPath ${binFile.absolutePath} \"\$@\"\n"
                        )
                        wrapperFile.setExecutable(true, false)
                        logAndNotify("Wrapper: ${wrapperFile.name} -> $loaderPath ${binFile.absolutePath}")
                    }
                } catch (e: Exception) {
                    logAndNotify("ERROR: Failed to set up jellyfin-ffmpeg: ${e.message}")
                    isRunning = false
                    return@Thread
                }

                // Verify file existence
                val jellyfinBin = File(nativeLibDir, "libjellyfin.so").absolutePath
                val jellyfinDll = File(jellyfinHome, "jellyfin.dll")
                val hostfxrPath = File(fxrDir, "libhostfxr.so")
                val hostpolicyPath = File(jellyfinHome, "libhostpolicy.so")
                val coreclrPath = File(jellyfinHome, "libcoreclr.so")
                val sslPath = File(jellyfinHome, "libssl.so")
                val cryptoPath = File(jellyfinHome, "libcrypto.so")
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

                val dataDir   = File(jellyfinHome, "data").also { it.mkdirs() }
                val configDir = File(jellyfinHome, "config").also { it.mkdirs() }
                val cacheDir  = File(jellyfinHome, "cache").also { it.mkdirs() }
                val logDir    = File(jellyfinHome, "log").also { it.mkdirs() }
                val webDir    = File(jellyfinHome, "jellyfin-web")

                // Auto-configure IP binding and remote access
                configureNetworkSettings(configDir)

                // Always force-overwrite encoding.xml with correct absolute FFmpeg path.
                // This ensures stale paths from previous installs or old binaries never persist.
                configureEncodingSettings(configDir, ffmpegPath)

                // Build the LD_LIBRARY_PATH that the spawned processes will use
                val ldLibraryPath = "${jellyfinHome.absolutePath}:$nativeLibDir"

                // === FFMPEG PREFLIGHT ===
                // Run the exact binaries Jellyfin will use.
                // These run with the same LD_LIBRARY_PATH as the Jellyfin process.
                // Results are logged but do NOT abort startup — Jellyfin's EncoderValidator is the final judge.
                logAndNotify("=== FFMPEG PREFLIGHT ===")
                runFFmpegPreflight(ffmpegPath, ffprobePath, ldLibraryPath)

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
                env["LD_LIBRARY_PATH"] = ldLibraryPath
                env["DOTNET_ROOT"] = dotnetRoot.absolutePath
                env["DOTNET_SYSTEM_GLOBALIZATION_INVARIANT"] = "1"
                env["DOTNET_gcServer"] = "0"
                env["DOTNET_System_GC_Server"] = "false"
                env["DOTNET_GCHeapHardLimit"] = "200000000"
                env["COREHOST_TRACE"] = "1"

                // Set PATH to include jellyfinHome so spawned ffmpeg/ffprobe subprocesses can be found by name
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

    // -------------------------------------------------------------------------
    // FFmpeg Preflight — inspects and tests the exact binaries Jellyfin will use
    // -------------------------------------------------------------------------

    /**
     * Inspect ELF header bytes to determine:
     * - Is it an ELF file at all?
     * - Is it 64-bit AArch64?
     * - Is it ET_EXEC (standalone), ET_DYN (shared/PIE)?
     * - Does it have the PIE flag (i.e. a PIE executable disguised as ET_DYN)?
     */
    private data class ElfInfo(
        val isElf: Boolean,
        val is64Bit: Boolean,
        val isArm64: Boolean,
        val elfType: String,         // "ET_EXEC", "ET_DYN", "ET_DYN+PIE", "ET_DYN+NOPIE", "UNKNOWN"
        val isExecutable: Boolean    // true if ET_EXEC or ET_DYN+PIE
    )

    private fun inspectElf(file: File): ElfInfo {
        try {
            java.io.FileInputStream(file).use { fis ->
                val header = ByteArray(64)
                if (fis.read(header) < 64) return ElfInfo(false, false, false, "TRUNCATED", false)

                // ELF magic
                if (header[0] != 0x7F.toByte() || header[1] != 'E'.code.toByte() ||
                    header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()) {
                    return ElfInfo(false, false, false, "NOT_ELF", false)
                }

                val is64Bit = header[4] == 2.toByte()
                // e_machine at offset 18 (little-endian 16-bit), AArch64 = 183 = 0xB7
                val machine = (header[19].toInt() and 0xFF).shl(8) or (header[18].toInt() and 0xFF)
                val isArm64 = is64Bit && machine == 183

                // e_type at offset 16 (little-endian 16-bit)
                val eType = (header[17].toInt() and 0xFF).shl(8) or (header[16].toInt() and 0xFF)

                // Read dynamic section to check for DF_1_PIE flag
                // We'll parse PT_DYNAMIC from program headers to find FLAGS_1 tag
                // For simplicity: read first 4KB to find DT_FLAGS_1
                val hasPieFlag = hasDynPieFlag(file)

                val elfType = when (eType) {
                    2 -> "ET_EXEC"             // traditional standalone executable
                    3 -> if (hasPieFlag) "ET_DYN+PIE" else "ET_DYN+NOPIE"
                    else -> "UNKNOWN($eType)"
                }
                val isExecutable = eType == 2 || (eType == 3 && hasPieFlag)

                return ElfInfo(true, is64Bit, isArm64, elfType, isExecutable)
            }
        } catch (e: Exception) {
            return ElfInfo(false, false, false, "READ_ERROR:${e.message}", false)
        }
    }

    /** Check for DT_FLAGS_1 with DF_1_PIE (0x08000000) in the dynamic section. */
    private fun hasDynPieFlag(file: File): Boolean {
        try {
            // Read up to 1MB to find the dynamic section
            val bytes = file.readBytes().let { if (it.size > 1_048_576) it.copyOf(1_048_576) else it }
            // DT_FLAGS_1 = 0x6ffffffb, DF_1_PIE = 0x08000000
            // Search for the FLAGS_1 tag in 8-byte aligned chunks (little-endian 64-bit)
            var i = 0
            while (i + 16 <= bytes.size) {
                val tag = readLe64(bytes, i)
                if (tag == 0x6ffffffbL) {
                    val value = readLe64(bytes, i + 8)
                    return (value and 0x08000000L) != 0L
                }
                i += 8
            }
        } catch (_: Exception) {}
        return false
    }

    private fun readLe64(bytes: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0..7) {
            result = result or ((bytes[offset + i].toLong() and 0xFF).shl(i * 8))
        }
        return result
    }

    private fun resolveSymlink(file: File): File {
        return try {
            val path = java.nio.file.Paths.get(file.absolutePath)
            if (java.nio.file.Files.isSymbolicLink(path)) {
                val target = java.nio.file.Files.readSymbolicLink(path)
                if (target.isAbsolute) target.toFile() else File(file.parentFile, target.toString())
            } else file
        } catch (_: Exception) { file }
    }

    /**
     * Run the FFmpeg preflight test.
     * Executes both binaries with the same LD_LIBRARY_PATH that Jellyfin will use.
     * Reports full diagnostics. Does NOT abort startup — only logs.
     */
    private fun runFFmpegPreflight(ffmpegPath: File, ffprobePath: File, ldLibraryPath: String) {
        preflightOne("FFmpeg", ffmpegPath, ldLibraryPath)
        preflightOne("FFprobe", ffprobePath, ldLibraryPath)
    }

    private fun preflightOne(label: String, binaryPath: File, ldLibraryPath: String) {
        logAndNotify("")
        logAndNotify("$label path: ${binaryPath.absolutePath}")
        logAndNotify("$label exists: ${binaryPath.exists()}")

        if (!binaryPath.exists()) {
            logAndNotify("$label executable: false")
            logAndNotify("$label ELF type: N/A")
            logAndNotify("$label architecture: N/A")
            logAndNotify("$label version: N/A")
            logAndNotify("$label exit code: N/A")
            logAndNotify("$label test: FAILED (not found)")
            return
        }

        val targetFile = resolveSymlink(binaryPath)
        // If binaryPath is a shell wrapper script, find the .bin for ELF inspection
        val elfFile = if (targetFile.canRead() && targetFile.length() < 4096) {
            // Small file -> likely a shell script; look for ffmpeg.bin / ffprobe.bin sibling
            val binSibling = File(binaryPath.parentFile, binaryPath.name + ".bin")
            if (binSibling.exists()) binSibling else targetFile
        } else targetFile
        logAndNotify("$label script: ${targetFile.absolutePath}")
        logAndNotify("$label executable binary: ${elfFile.absolutePath}")
        logAndNotify("$label executable: ${binaryPath.canExecute()}")

        val elf = inspectElf(elfFile)
        logAndNotify("$label ELF type: ${elf.elfType}")
        logAndNotify("$label architecture: ${if (elf.isArm64) "ARM64 (AArch64)" else "UNKNOWN"}")
        logAndNotify("$label is valid executable: ${elf.isExecutable}")

        // Read NEEDED libs from dynamic section
        val neededLibs = readNeededLibs(elfFile)
        logAndNotify("$label native dependencies: ${neededLibs.joinToString(", ").ifEmpty { "(none)" }}")

        // Classify deps — jellyfin-ffmpeg uses libg_*.so (patched glibc names)
        val knownGlibcNames = setOf("libg_libc.so","libg_m.so","libg_dl.so","libg_pthread.so",
            "libc.so","libm.so","libdl.so","libpthread.so","libz.so")
        val unknownLibs = neededLibs.filterNot { it in knownGlibcNames }
        logAndNotify("$label glibc/bionic compatibility: ${if (unknownLibs.isEmpty()) "OK (only basic C runtime deps)" else "WARNING — unexpected deps: $unknownLibs"}")

        // Execute with the correct environment
        var versionLine = ""
        var exitCode = -1
        val output = StringBuilder()
        var execException: String? = null

        try {
            val pb = ProcessBuilder(binaryPath.absolutePath, "-version")
            pb.redirectErrorStream(true)
            val pbEnv = pb.environment()
            pbEnv["LD_LIBRARY_PATH"] = ldLibraryPath
            val proc = pb.start()
            val reader = java.io.BufferedReader(java.io.InputStreamReader(proc.inputStream))
            var lineIdx = 0
            var ln: String?
            while (reader.readLine().also { ln = it } != null) {
                val l = ln ?: continue
                output.append(l).append("\n")
                if (lineIdx < 5) {
                    logAndNotify("  [$label] $l")
                    if (l.contains("ffmpeg version") || l.contains("ffprobe version")) versionLine = l
                }
                lineIdx++
            }
            exitCode = proc.waitFor()
        } catch (e: Exception) {
            execException = e.message
            logAndNotify("  [$label] EXEC EXCEPTION: ${e.message}")
        }

        logAndNotify("$label version: ${if (versionLine.isNotEmpty()) versionLine else "(not detected)"}")
        logAndNotify("$label exit code: $exitCode")

        if (exitCode == 159) {
            logAndNotify("$label test: FAILED (exit code 159 = SIGSYS — binary blocked by Android SECCOMP/Bionic sandbox)")
            logAndNotify("$label SIGSYS diagnosis: binary is likely a statically-linked glibc binary (e.g. John Van Sickle build).")
            logAndNotify("$label SIGSYS diagnosis: glibc static init calls arch_prctl/set_robust_list which are blocked on Android.")
            logAndNotify("$label SIGSYS diagnosis: Replace with an Android NDK / Bionic-compiled binary.")
            captureLogcat()
        } else if (exitCode == 0 && versionLine.isNotEmpty()) {
            logAndNotify("$label test: SUCCESS")
        } else if (execException != null) {
            logAndNotify("$label test: FAILED (exception: $execException)")
            captureLogcat()
        } else {
            logAndNotify("$label test: FAILED (exit $exitCode, output: ${output.take(200)})")
            captureLogcat()
        }
    }

    /** Extract NEEDED library names from ELF dynamic section. */
    private fun readNeededLibs(file: File): List<String> {
        val result = mutableListOf<String>()
        try {
            // Use readelf-equivalent: parse ELF dynamic section
            // Run `readelf -d` via ProcessBuilder if available, else do manual parse
            val pb = ProcessBuilder("readelf", "-d", file.absolutePath)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val reader = java.io.BufferedReader(java.io.InputStreamReader(proc.inputStream))
            var ln: String?
            val neededRegex = Regex("""NEEDED\s+Shared library: \[(.+)]""")
            while (reader.readLine().also { ln = it } != null) {
                val m = neededRegex.find(ln ?: "") ?: continue
                result.add(m.groupValues[1])
            }
            proc.waitFor()
        } catch (_: Exception) {}
        return result
    }

    private fun captureLogcat() {
        logAndNotify("Capturing system logcat for crash details...")
        try {
            val proc = ProcessBuilder("logcat", "-d", "-t", "30", "--pid=${android.os.Process.myPid()}")
                .redirectErrorStream(true).start()
            val reader = java.io.BufferedReader(java.io.InputStreamReader(proc.inputStream))
            var ln: String?
            var count = 0
            while (reader.readLine().also { ln = it } != null && count < 60) {
                logAndNotify("  [logcat] $ln")
                count++
            }
            proc.waitFor()
        } catch (e: Exception) {
            logAndNotify("Failed to capture logcat: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Configuration helpers
    // -------------------------------------------------------------------------

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
                logAndNotify("Created default network.xml: enabled remote access.")
            } else {
                var content = networkXml.readText()
                if (content.contains("<EnableRemoteAccess>false</EnableRemoteAccess>")) {
                    content = content.replace("<EnableRemoteAccess>false</EnableRemoteAccess>",
                        "<EnableRemoteAccess>true</EnableRemoteAccess>")
                    networkXml.writeText(content)
                    logAndNotify("Auto-configured network.xml: enabled remote access.")
                }
            }
        } catch (e: Exception) {
            logAndNotify("WARNING: Failed to auto-configure network.xml: ${e.message}")
        }
    }

    /**
     * Always force-overwrite encoding.xml with the verified FFmpeg path.
     * This prevents stale paths from previous installs from interfering.
     */
    private fun configureEncodingSettings(configDir: File, ffmpegPath: File) {
        val encodingXml = File(configDir, "encoding.xml")
        val xmlContent = """<?xml version="1.0" encoding="utf-8"?>
<EncodingOptions xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
  <EncoderAppPath>${ffmpegPath.absolutePath}</EncoderAppPath>
  <EncoderAppPathDisplay>${ffmpegPath.absolutePath}</EncoderAppPathDisplay>
</EncodingOptions>"""
        try {
            encodingXml.writeText(xmlContent)
            logAndNotify("encoding.xml written: EncoderAppPath = ${ffmpegPath.absolutePath}")
        } catch (e: Exception) {
            logAndNotify("WARNING: Failed to write encoding.xml: ${e.message}")
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
