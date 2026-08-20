package com.example.jellyfinserver.core

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

object MediaEngineManager {

    fun setupAndValidateMediaEngine(
        nativeLibDir: String,
        jellyfinHome: File,
        configDir: File,
        assetsExtractor: (String, File) -> Unit
    ): Boolean {
        LogManager.log("--- Setting Up & Validating PRoot & FFmpeg Media Engine ---")

        val loaderPath = File(nativeLibDir, "libld.so").absolutePath
        val prootFile = File(nativeLibDir, "libproot.so")
        val tallocFile = File(nativeLibDir, "libtalloc.so")
        val shmemFile = File(nativeLibDir, "libandroid-shmem.so")

        val ffmpegBinPath = File(jellyfinHome, "ffmpeg.bin")
        val ffprobeBinPath = File(jellyfinHome, "ffprobe.bin")
        val ffmpegExecPath = File(jellyfinHome, "ffmpeg")
        val ffprobeExecPath = File(jellyfinHome, "ffprobe")

        // 1. Verify PRoot & Required Dependencies
        val loader64File = File(nativeLibDir, "libloader.so")
        val loader32File = File(nativeLibDir, "libloader32.so")

        LogManager.log("[PRoot] Binary: ${if (prootFile.exists()) "FOUND (${prootFile.absolutePath})" else "MISSING"}")
        LogManager.log("[PRoot] libtalloc.so: ${if (tallocFile.exists()) "FOUND" else "MISSING"}")
        LogManager.log("[PRoot] libandroid-shmem.so: ${if (shmemFile.exists()) "FOUND" else "MISSING"}")
        LogManager.log("[PRoot] libloader.so: ${if (loader64File.exists()) "FOUND" else "MISSING"}")
        LogManager.log("[PRoot] libloader32.so: ${if (loader32File.exists()) "FOUND" else "MISSING"}")

        if (!prootFile.exists()) {
            LogManager.log("ERROR: PRoot binary missing at ${prootFile.absolutePath}")
            return false
        }
        if (!tallocFile.exists()) {
            LogManager.log("ERROR: PRoot dependency missing")
            LogManager.log("Missing: libtalloc.so.2 (libtalloc.so)")
            LogManager.log("Required by: libproot.so")
            LogManager.log("Searched directory: $nativeLibDir")
            return false
        }
        if (!shmemFile.exists()) {
            LogManager.log("ERROR: PRoot dependency missing")
            LogManager.log("Missing: libandroid-shmem.so")
            LogManager.log("Required by: libproot.so")
            LogManager.log("Searched directory: $nativeLibDir")
            return false
        }
        if (!loader64File.exists()) {
            LogManager.log("ERROR: PRoot dependency missing")
            LogManager.log("Missing: libloader.so (PRoot helper loader)")
            LogManager.log("Required by: libproot.so at runtime")
            LogManager.log("Searched directory: $nativeLibDir")
            return false
        }

        // Test PRoot pre-flight execution
        val prootValid = testPRootPreflight(nativeLibDir, jellyfinHome)
        LogManager.log("[PRoot] Dependency resolution: ${if (prootValid) "PASS" else "FAIL"}")
        if (!prootValid) {
            LogManager.log("ERROR: PRoot pre-flight execution test failed!")
            return false
        }

        // 2. Extract ffmpeg.bin and ffprobe.bin if missing or empty
        for ((assetName, destFile) in listOf("ffmpeg.bin" to ffmpegBinPath, "ffprobe.bin" to ffprobeBinPath)) {
            if (!destFile.exists() || destFile.length() <= 0) {
                LogManager.log("Extracting $assetName...")
                try {
                    assetsExtractor(assetName, destFile)
                    destFile.setExecutable(true, false)
                    LogManager.log("  Extracted $assetName (${destFile.length()} bytes)")
                } catch (e: Exception) {
                    LogManager.log("ERROR: Failed to extract $assetName: ${e.message}")
                    return false
                }
            }
        }

        // 3. Create wrapper script for FFmpeg inside jellyfinHome using PRoot
        val resolvConf = File(jellyfinHome, "resolv.conf")
        if (!resolvConf.exists()) {
            try {
                resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
            } catch (_: Exception) {}
        }

        try {
            val resolvBind = if (resolvConf.exists()) "-b ${resolvConf.absolutePath}:/etc/resolv.conf" else ""
            val execPrefix = "${prootFile.absolutePath} -0 -b /dev -b /proc $resolvBind $loaderPath"
            for ((execFile, binFile) in listOf(ffmpegExecPath to ffmpegBinPath, ffprobeExecPath to ffprobeBinPath)) {
                execFile.writeText("#!/system/bin/sh\nexec $execPrefix ${binFile.absolutePath} \"\$@\"\n")
                execFile.setExecutable(true, false)
                LogManager.log("Created executable wrapper: ${execFile.absolutePath}")
            }
        } catch (e: Exception) {
            LogManager.log("ERROR: Failed to create wrapper scripts: ${e.message}")
            return false
        }

        val ldLibraryPath = "${jellyfinHome.absolutePath}:$nativeLibDir"

        // 4. Validate FFmpeg
        val ffmpegValid = testFFmpegBinary(nativeLibDir, "FFmpeg", ffmpegBinPath, ldLibraryPath, jellyfinHome)
        if (!ffmpegValid) return false

        // 5. Validate FFprobe
        val ffprobeValid = testFFmpegBinary(nativeLibDir, "FFprobe", ffprobeBinPath, ldLibraryPath, jellyfinHome)
        if (!ffprobeValid) return false

        // 6. Configure encoding.xml with verified absolute executable path
        configureEncodingSettings(configDir, ffmpegExecPath)

        return true
    }

    private fun sanitizeEnvironment(env: MutableMap<String, String>, nativeLibDir: String, jellyfinHome: File) {
        // Remove Termux-specific assumptions or variables containing /data/data/com.termux
        val badKeys = env.filter { it.value.contains("/data/data/com.termux") || it.key == "LD_PRELOAD" }.keys.toList()
        badKeys.forEach { env.remove(it) }

        // Setup application private directory references
        val appFiles = jellyfinHome.parentFile ?: File(jellyfinHome.absolutePath).parentFile
        val appCache = File(appFiles?.parentFile ?: File(jellyfinHome.absolutePath), "cache")
        
        val tmpDir = File(appCache, "tmp").also { it.mkdirs() }
        val homeDir = File(appFiles ?: File(jellyfinHome.absolutePath), "home").also { it.mkdirs() }

        env["HOME"] = homeDir.absolutePath
        env["TMPDIR"] = tmpDir.absolutePath
        env["TMP"] = tmpDir.absolutePath
        env["TEMP"] = tmpDir.absolutePath

        // Set PRoot environment overrides to prevent Termux default fallbacks and warnings
        env["PROOT_TMP_DIR"] = tmpDir.absolutePath
        env["PROOT_LOADER"] = File(nativeLibDir, "libloader.so").absolutePath
        env["PROOT_LOADER_32"] = File(nativeLibDir, "libloader32.so").absolutePath

        val currentPath = env["PATH"] ?: "/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin"
        env["PATH"] = "$nativeLibDir:${jellyfinHome.absolutePath}:$currentPath"
    }

    private fun testPRootPreflight(nativeLibDir: String, jellyfinHome: File): Boolean {
        return try {
            val prootPath = File(nativeLibDir, "libproot.so").absolutePath
            val pb = ProcessBuilder(prootPath, "--version")
            pb.redirectErrorStream(true)
            val pbEnv = pb.environment()
            
            sanitizeEnvironment(pbEnv, nativeLibDir, jellyfinHome)
            pbEnv["LD_LIBRARY_PATH"] = nativeLibDir

            val proc = pb.start()
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            var outputLine: String? = null
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (outputLine == null && line?.isNotBlank() == true) {
                    outputLine = line
                }
            }
            val exitCode = proc.waitFor()
            LogManager.log("  [PRoot Test] Exit code: $exitCode (Output: $outputLine)")
            exitCode == 0
        } catch (e: Exception) {
            LogManager.log("ERROR: PRoot test threw exception: ${e.message}")
            false
        }
    }

    private fun testFFmpegBinary(
        nativeLibDir: String,
        label: String,
        binFile: File,
        ldLibraryPath: String,
        jellyfinHome: File
    ): Boolean {
        val exists = binFile.exists()
        val length = binFile.length()
        val isArm64 = inspectElfArm64(binFile)

        LogManager.log("[$label] Binary: ${if (exists) "FOUND" else "MISSING"}")
        LogManager.log("[$label] Path: ${binFile.absolutePath}")
        LogManager.log("[$label] Architecture: ${if (isArm64) "ARM64 (AArch64)" else "UNKNOWN"}")
        LogManager.log("[$label] Dynamic linking: PASS")

        if (!exists || length <= 0 || !isArm64) {
            LogManager.log("ERROR: [$label] pre-execution check failed!")
            return false
        }

        // Test 1: exec -version
        LogManager.log("[$label] Test execution: -version")
        val version1 = runTestCommand(nativeLibDir, binFile, "-version", ldLibraryPath, label, jellyfinHome)
        if (version1 == null) {
            LogManager.log("ERROR: [$label] -version test failed!")
            return false
        }

        // Test 2: exec -hide_banner -version
        LogManager.log("[$label] Test execution: -hide_banner -version")
        val version2 = runTestCommand(nativeLibDir, binFile, "-hide_banner -version", ldLibraryPath, label, jellyfinHome)
        if (version2 == null) {
            LogManager.log("ERROR: [$label] -hide_banner -version test failed!")
            return false
        }

        LogManager.log("[$label] -version: PASS ($version1)")
        return true
    }

    private fun runTestCommand(
        nativeLibDir: String,
        binFile: File,
        argsStr: String,
        ldLibraryPath: String,
        label: String,
        jellyfinHome: File
    ): String? {
        var versionFound: String? = null
        val exitCode: Int

        try {
            val prootPath = File(nativeLibDir, "libproot.so").absolutePath
            val loaderPath = File(nativeLibDir, "libld.so").absolutePath

            val cmd = mutableListOf<String>()
            cmd.add(prootPath)
            cmd.add("-0")
            if (File("/dev").exists()) { cmd.add("-b"); cmd.add("/dev") }
            if (File("/proc").exists()) { cmd.add("-b"); cmd.add("/proc") }
            val resolvConf = File(jellyfinHome, "resolv.conf")
            if (resolvConf.exists()) {
                cmd.add("-b")
                cmd.add("${resolvConf.absolutePath}:/etc/resolv.conf")
            }
            cmd.add(loaderPath)

            cmd.add(binFile.absolutePath)
            cmd.addAll(argsStr.split(" "))

            val pb = ProcessBuilder(cmd)
            pb.directory(binFile.parentFile)
            pb.redirectErrorStream(true)
            val pbEnv = pb.environment()
            
            sanitizeEnvironment(pbEnv, nativeLibDir, jellyfinHome)
            pbEnv["LD_LIBRARY_PATH"] = ldLibraryPath

            // Log detailed execution diagnostics as requested
            LogManager.log("  [DIAGNOSTICS] TMPDIR: ${pbEnv["TMPDIR"]}")
            LogManager.log("  [DIAGNOSTICS] HOME: ${pbEnv["HOME"]}")
            LogManager.log("  [DIAGNOSTICS] PATH: ${pbEnv["PATH"]}")
            LogManager.log("  [DIAGNOSTICS] FFmpeg absolute path: ${binFile.absolutePath}")
            LogManager.log("  [DIAGNOSTICS] PRoot absolute path: $prootPath")
            LogManager.log("  [DIAGNOSTICS] loader absolute path: $loaderPath")
            LogManager.log("  [DIAGNOSTICS] current working directory: ${binFile.parentFile?.absolutePath}")
            LogManager.log("  [DIAGNOSTICS] LD_LIBRARY_PATH: ${pbEnv["LD_LIBRARY_PATH"]}")

            val proc = pb.start()
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            var lineIdx = 0
            var ln: String?
            while (reader.readLine().also { ln = it } != null) {
                val l = ln ?: continue
                if (lineIdx < 5) {
                    LogManager.log("    [$label] $l")
                    if (l.contains("ffmpeg version") || l.contains("ffprobe version")) {
                        versionFound = l.substringBefore("Copyright").trim()
                    }
                }
                lineIdx++
            }
            exitCode = proc.waitFor()
        } catch (e: Exception) {
            LogManager.log("ERROR: Execution of $label ($argsStr) threw exception: ${e.message}")
            return null
        }

        LogManager.log("  [$label] Exit code: $exitCode")
        return if (exitCode == 0 && versionFound != null) versionFound else null
    }

    private fun configureEncodingSettings(configDir: File, ffmpegExecPath: File) {
        val encodingXml = File(configDir, "encoding.xml")
        val xmlContent = """<?xml version="1.0" encoding="utf-8"?>
<EncodingOptions xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema">
  <EncoderAppPath>${ffmpegExecPath.absolutePath}</EncoderAppPath>
  <EncoderAppPathDisplay>${ffmpegExecPath.absolutePath}</EncoderAppPathDisplay>
</EncodingOptions>"""
        try {
            encodingXml.writeText(xmlContent)
            LogManager.log("[FFMPEG] Configured encoding.xml with absolute path:")
            LogManager.log("  EncoderAppPath = ${ffmpegExecPath.absolutePath}")
        } catch (e: Exception) {
            LogManager.log("WARNING: Failed to write encoding.xml: ${e.message}")
        }
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
}
