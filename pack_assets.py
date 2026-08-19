import os
import zipfile

JELLYFIN_DIR = "/data/data/com.termux/files/home/jellyfin"
ASSETS_ZIP = "/data/data/com.termux/files/home/JellyfinServerApp/app/src/main/assets/jellyfin_assets.zip"

print("Zipping non-native Jellyfin assets (DLLs, Web, etc.)...")

# Ensure parent directory exists
os.makedirs(os.path.dirname(ASSETS_ZIP), exist_ok=True)

with zipfile.ZipFile(ASSETS_ZIP, 'w', zipfile.ZIP_DEFLATED) as zipf:
    for root, _, files in os.walk(JELLYFIN_DIR):
        for f in files:
            # We skip native library files (.so) and the primary executable files since they are in jniLibs!
            # (To save APK space and avoid duplicate files)
            if f.endswith(".so") or f == "jellyfin" or f == "createdump" or f == "ld-linux-aarch64.so.1" or ".so." in f:
                continue
                
            full_path = os.path.join(root, f)
            # Create relative path to zip under JELLYFIN_DIR root
            rel_path = os.path.relpath(full_path, JELLYFIN_DIR)
            zipf.write(full_path, rel_path)

print(f"Zip created successfully at {ASSETS_ZIP}!")
