# Jellyfin Server Android App

A standalone native Android application that bundles and runs the Jellyfin Media Server in the background.

## 🍒 Features
- **Native Execution**: Runs the real Jellyfin Media Server binary directly on Android using a Termux-glibc translation environment, ensuring 100% native execution speed with zero virtualization/chroot overhead.
- **Background Service**: Operates as an Android Foreground Service, keeping the server alive in the background.
- **Control Interface**: Simple GUI to start and stop the server, monitor runtime state, and view live logs.
- **Easy Web Launcher**: Tap to open the Jellyfin Web dashboard in your browser.

## ⚙️ How it Works
1. **Glibc Loader Mapping**: Android's Bionic libc doesn't natively run glibc-linked binaries. We bundle the glibc dynamic linker (loader) and redirect Jellyfin dependencies to standard glibc packages packaged inside the app's native directory.
2. **Execute Permission Workaround**: Android 10+ restricts execution of binaries from writable directories. We circumvent this by renaming all ELF binaries and shared libraries with a `.so` prefix and placing them in the `jniLibs` folder so Android extracts them into the read-only executable `/data/app/.../lib/arm64/` directory.
3. **Asset Inflation**: All C# assemblies (`.dll` files) and the Jellyfin Web UI files are packaged in an assets zip, which is extracted into the app's private sandboxed data directory on first run.

## 🛠️ Build and Setup Instructions

### Prerequisites (Termux Dev Environment)
To build the project directly on device, install JDK 21, Android SDK Platform-34, and Gradle:
```bash
pkg install openjdk-21 gradle
```

### Steps
1. **Resolve and Patch Libraries**:
   Run the dependency resolution script to trace dependencies, copy glibc shared libraries, and patch ELF headers:
   ```bash
   python3 resolve_deps.py
   ```
2. **Package Web & Assemblies Assets**:
   Zip non-native Assemblies and the Web UI folder:
   ```bash
   python3 pack_assets.py
   ```
3. **Build the APK**:
   Build the project debug release:
   ```bash
   gradle assembleDebug
   ```
   The output APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.
