package com.example.jellyfinserver.core

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

object NativeProcessLauncher {

    fun launchJellyfinProcess(
        nativeLibDir: String,
        jellyfinHome: File,
        dotnetRoot: File,
        dataDir: File,
        configDir: File,
        cacheDir: File,
        logDir: File,
        webDir: File
    ): Process {
        val prootBin = File(nativeLibDir, "libproot.so").absolutePath
        val loaderPath = File(nativeLibDir, "libld.so").absolutePath
        val jellyfinBin = File(nativeLibDir, "libjellyfin.so").absolutePath
        val ldLibraryPath = "${jellyfinHome.absolutePath}:$nativeLibDir"

        val command = mutableListOf<String>()

        val resolvConf = File(jellyfinHome, "resolv.conf")
        if (!resolvConf.exists()) {
            try {
                resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
            } catch (_: Exception) {}
        }

        if (File(prootBin).exists()) {
            LogManager.log("[LAUNCHER] Launching Jellyfin via Linux PRoot userspace backend...")
            command.add(prootBin)
            command.add("-0")
            if (File("/dev").exists()) { command.add("-b"); command.add("/dev") }
            if (File("/proc").exists()) { command.add("-b"); command.add("/proc") }
            if (resolvConf.exists()) {
                command.add("-b")
                command.add("${resolvConf.absolutePath}:/etc/resolv.conf")
            }
            command.add(loaderPath)
            command.add(jellyfinBin)
        } else {
            LogManager.log("[LAUNCHER] WARNING: libproot.so missing from nativeLibDir, falling back to loader directly...")
            command.add(loaderPath)
            command.add(jellyfinBin)
        }

        command.add("--nonetchange")
        command.add("--datadir"); command.add(dataDir.absolutePath)
        command.add("--configdir"); command.add(configDir.absolutePath)
        command.add("--cachedir"); command.add(cacheDir.absolutePath)
        command.add("--logdir"); command.add(logDir.absolutePath)
        command.add("--webdir"); command.add(webDir.absolutePath)

        val processBuilder = ProcessBuilder(command)
        processBuilder.directory(jellyfinHome)

        val env = processBuilder.environment()
        // Sanitize environment from Termux variables
        val badKeys = env.filter { it.value.contains("/data/data/com.termux") || it.key == "LD_PRELOAD" }.keys.toList()
        badKeys.forEach { env.remove(it) }

        // Setup application private directories
        val appFiles = jellyfinHome.parentFile ?: File(jellyfinHome.absolutePath).parentFile
        val appCache = File(appFiles?.parentFile ?: File(jellyfinHome.absolutePath), "cache")
        val tmpDir = File(appCache, "tmp").also { it.mkdirs() }
        val homeDir = File(appFiles ?: File(jellyfinHome.absolutePath), "home").also { it.mkdirs() }

        env["LD_LIBRARY_PATH"] = ldLibraryPath
        env["DOTNET_ROOT"] = dotnetRoot.absolutePath
        env["HOME"] = homeDir.absolutePath
        env["TMPDIR"] = tmpDir.absolutePath
        env["TMP"] = tmpDir.absolutePath
        env["TEMP"] = tmpDir.absolutePath
        env["ASPNETCORE_URLS"] = "http://0.0.0.0:8096"
        env["DOTNET_SYSTEM_GLOBALIZATION_INVARIANT"] = "1"
        env["DOTNET_gcServer"] = "0"
        env["DOTNET_System_GC_Server"] = "false"
        env["DOTNET_GCHeapHardLimit"] = "200000000"

        // Set PRoot environment overrides to prevent Termux default fallbacks and warnings
        env["PROOT_TMP_DIR"] = tmpDir.absolutePath
        env["PROOT_LOADER"] = File(nativeLibDir, "libloader.so").absolutePath
        env["PROOT_LOADER_32"] = File(nativeLibDir, "libloader32.so").absolutePath

        val currentPath = env["PATH"] ?: "/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin"
        env["PATH"] = "$nativeLibDir:${jellyfinHome.absolutePath}:$currentPath"

        processBuilder.redirectErrorStream(true)

        val process = processBuilder.start()

        // Stream process log output
        Thread {
            try {
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    LogManager.log(line ?: "")
                }
            } catch (_: Exception) {}
        }.start()

        return process
    }
}
