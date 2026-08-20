package com.example.jellyfinserver.core

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import android.system.Os

object RuntimeManager {

    /**
     * Robust, Idempotent Symlink Management Algorithm
     * Handles Cases A, B, C, D, E explicitly with full verification and reporting.
     */
    fun safeCreateSymlink(sourceFile: File, destFile: File) {
        val srcPath = sourceFile.absolutePath
        val destPath = destFile.absolutePath
        val expectedTarget = srcPath

        LogManager.log("Symlink:")
        LogManager.log("  Source: $srcPath")
        LogManager.log("  Destination: $destPath")

        val destPathObj = Paths.get(destPath)
        val exists = Files.exists(destPathObj) || Files.isSymbolicLink(destPathObj)

        if (!exists) {
            // CASE A: Destination does not exist -> Create
            LogManager.log("  Destination exists: false")
            LogManager.log("  Action: CREATE")
            try {
                Os.symlink(srcPath, destPath)
                LogManager.log("  Result: SUCCESS")
            } catch (e: Exception) {
                LogManager.log("  Result: FAILED (${e.message})")
                throw e
            }
        } else {
            // Destination exists
            val isSymlink = Files.isSymbolicLink(destPathObj)
            val isDir = destFile.isDirectory && !isSymlink
            val isRegularFile = destFile.isFile && !isSymlink

            LogManager.log("  Destination exists: true")

            if (isSymlink) {
                LogManager.log("  Destination type: SYMLINK")
                val existingTarget = try {
                    Files.readSymbolicLink(destPathObj).toString()
                } catch (e: Exception) {
                    "unknown"
                }

                LogManager.log("  Existing target: $existingTarget")
                LogManager.log("  Expected target: $expectedTarget")

                if (existingTarget == expectedTarget) {
                    // CASE B: Already points to correct target -> Reuse
                    LogManager.log("  Action: REUSE EXISTING SYMLINK")
                    LogManager.log("  Result: SUCCESS")
                } else {
                    // CASE C: Points to wrong target -> Remove symlink ONLY and recreate
                    LogManager.log("  Action: STALE SYMLINK — Recreating")
                    try {
                        Files.delete(destPathObj) // Deletes ONLY the symlink pointer
                        Os.symlink(srcPath, destPath)
                        LogManager.log("  Result: SUCCESS")
                    } catch (e: Exception) {
                        LogManager.log("  Result: FAILED (${e.message})")
                        throw e
                    }
                }
            } else if (isDir) {
                // CASE E: Real Directory -> Refuse deletion
                LogManager.log("  Destination type: DIRECTORY")
                LogManager.log("  Action: REFUSED (Real directory exists at destination)")
                LogManager.log("  Result: REFUSED")
                return
            } else if (isRegularFile) {
                // CASE D: Real File -> Replace with symlink
                LogManager.log("  Destination type: FILE — Overwriting with symlink")
                try {
                    destFile.delete()
                    Os.symlink(srcPath, destPath)
                    LogManager.log("  Result: SUCCESS")
                } catch (e: Exception) {
                    LogManager.log("  Result: FAILED (${e.message})")
                    throw e
                }
            }
        }

        // Verification step
        val isLinkNow = Files.isSymbolicLink(destPathObj)
        val resolvedTarget = try { Files.readSymbolicLink(destPathObj).toString() } catch (_: Exception) { "unknown" }
        val targetExists = File(resolvedTarget).exists()
        val verified = isLinkNow && resolvedTarget == expectedTarget && targetExists

        LogManager.log("  Symlink verification:")
        LogManager.log("    Destination exists: true")
        LogManager.log("    Is symbolic link: $isLinkNow")
        LogManager.log("    Resolved target: $resolvedTarget")
        LogManager.log("    Expected target: $expectedTarget")
        LogManager.log("    Target exists: $targetExists")
        LogManager.log("    Verification: ${if (verified) "SUCCESS" else "FAILED"}")

        if (!verified) {
            throw Exception("Symlink verification failed for $destPath")
        }
    }

    fun verifyDotNetRuntime(nativeLibDir: String, jellyfinHome: File, dotnetRoot: File): Boolean {
        val loaderPath = File(nativeLibDir, "libld.so")
        val jellyfinBin = File(nativeLibDir, "libjellyfin.so")
        val jellyfinDll = File(jellyfinHome, "jellyfin.dll")
        val fxrDir = File(dotnetRoot, "host/fxr/9.0.16")
        val hostfxrPath = File(fxrDir, "libhostfxr.so")
        val hostpolicyPath = File(jellyfinHome, "libhostpolicy.so")
        val coreclrPath = File(jellyfinHome, "libcoreclr.so")
        val sslPath = File(jellyfinHome, "libssl.so")
        val cryptoPath = File(jellyfinHome, "libcrypto.so")
        val runtimeconfigPath = File(jellyfinHome, "jellyfin.runtimeconfig.json")
        val depsPath = File(jellyfinHome, "jellyfin.deps.json")

        // Diagnostics for libg_* packaged libraries
        val libgLibc = File(nativeLibDir, "libg_libc.so")
        val libgM = File(nativeLibDir, "libg_m.so")
        val libgDl = File(nativeLibDir, "libg_dl.so")
        val libgPthread = File(nativeLibDir, "libg_pthread.so")
        val libgRt = File(nativeLibDir, "libg_rt.so")

        // Diagnostics for glibc runtime symlinks
        val cSym = File(jellyfinHome, "libc.so.6")
        val mSym = File(jellyfinHome, "libm.so.6")
        val dlSym = File(jellyfinHome, "libdl.so.2")
        val pthreadSym = File(jellyfinHome, "libpthread.so.0")
        val rtSym = File(jellyfinHome, "librt.so.1")

        fun getResolvedTarget(f: File): String {
            if (!f.exists()) return "MISSING"
            return try {
                val p = java.nio.file.Paths.get(f.absolutePath)
                if (java.nio.file.Files.isSymbolicLink(p)) {
                    java.nio.file.Files.readSymbolicLink(p).toString()
                } else {
                    f.absolutePath
                }
            } catch (_: Exception) {
                "EXISTS"
            }
        }

        LogManager.log(".NET runtime:")
        LogManager.log("  Version: 9.0.16")
        LogManager.log("  libhostfxr.so: ${if (hostfxrPath.exists()) "OK" else "MISSING"}")
        LogManager.log("  libhostpolicy.so: ${if (hostpolicyPath.exists()) "OK" else "MISSING"}")
        LogManager.log("  libcoreclr.so: ${if (coreclrPath.exists()) "OK" else "MISSING"}")

        LogManager.log("[CORECLR] Checking libcoreclr.so: ${if (coreclrPath.exists()) "OK (${coreclrPath.absolutePath})" else "MISSING"}")
        LogManager.log("[CORECLR] Checking libc.so.6: -> ${getResolvedTarget(cSym)}")
        LogManager.log("[CORECLR] Checking libm.so.6: -> ${getResolvedTarget(mSym)}")
        LogManager.log("[CORECLR] Checking libdl.so.2: -> ${getResolvedTarget(dlSym)}")
        LogManager.log("[CORECLR] Checking libpthread.so.0: -> ${getResolvedTarget(pthreadSym)}")
        LogManager.log("[CORECLR] Checking librt.so.1: -> ${getResolvedTarget(rtSym)}")

        LogManager.log("[CORECLR] Native packaged glibc libraries:")
        LogManager.log("  libld.so: ${if (loaderPath.exists()) "OK" else "MISSING"}")
        LogManager.log("  libg_libc.so: ${if (libgLibc.exists()) "OK" else "MISSING"}")
        LogManager.log("  libg_m.so: ${if (libgM.exists()) "OK" else "MISSING"}")
        LogManager.log("  libg_dl.so: ${if (libgDl.exists()) "OK" else "MISSING"}")
        LogManager.log("  libg_pthread.so: ${if (libgPthread.exists()) "OK" else "MISSING"}")
        LogManager.log("  libg_rt.so: ${if (libgRt.exists()) "OK" else "MISSING"}")

        return loaderPath.exists() && jellyfinBin.exists() && jellyfinDll.exists() &&
                hostfxrPath.exists() && hostpolicyPath.exists() && coreclrPath.exists() &&
                sslPath.exists() && cryptoPath.exists() && runtimeconfigPath.exists() && depsPath.exists() &&
                libgRt.exists() && rtSym.exists()
    }
}
