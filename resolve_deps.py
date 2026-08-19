import os
import subprocess
import shutil
import re

GLIBC_LIB_DIR = "/data/data/com.termux/files/usr/glibc/lib"
JELLYFIN_DIR = "/data/data/com.termux/files/home/jellyfin"
DEST_DIR = "/data/data/com.termux/files/home/JellyfinServerApp/app/src/main/jniLibs/arm64-v8a"

if os.path.exists(DEST_DIR):
    shutil.rmtree(DEST_DIR)
os.makedirs(DEST_DIR, exist_ok=True)

# Map original lib names to safe Android names (must match ^lib.*\.so$)
name_mapping = {}

def get_android_safe_name(name):
    if name in name_mapping:
        return name_mapping[name]
    
    safe_name = name
    # Remove version suffix like .so.1.2 or .so.6
    safe_name = re.sub(r'\.so(\.\d+)*', '.so', safe_name)
    # Replace ++ with xx
    safe_name = safe_name.replace('++', 'xx')
    # Ensure it starts with lib
    if not safe_name.startswith('lib'):
        safe_name = 'lib' + safe_name
    # Ensure it ends with .so
    if not safe_name.endswith('.so'):
        safe_name = safe_name + '.so'
        
    name_mapping[name] = safe_name
    return safe_name

# Add explicit mapping for the loader
name_mapping["ld-linux-aarch64.so.1"] = "libld.so"

# Map core glibc runtime libraries to libg_*.so to avoid Android installer filtering and Bionic clashes
name_mapping["libc.so.6"] = "libg_libc.so"
name_mapping["libc.so"] = "libg_libc.so"

name_mapping["libdl.so.2"] = "libg_dl.so"
name_mapping["libdl.so"] = "libg_dl.so"

name_mapping["libm.so.6"] = "libg_m.so"
name_mapping["libm.so"] = "libg_m.so"

name_mapping["libpthread.so.0"] = "libg_pthread.so"
name_mapping["libpthread.so"] = "libg_pthread.so"

name_mapping["librt.so.1"] = "libg_rt.so"
name_mapping["librt.so"] = "libg_rt.so"

def get_dependencies(filepath):
    try:
        output = subprocess.check_output(["objdump", "-p", filepath], stderr=subprocess.DEVNULL).decode("utf-8")
        deps = []
        for line in output.splitlines():
            if "NEEDED" in line:
                parts = line.strip().split()
                if len(parts) >= 2:
                    deps.append(parts[1])
        return deps
    except Exception as e:
        print(f"Error reading dependencies of {filepath}: {e}")
        return []

# Scan jellyfin files to get seed libraries and binaries
seed_files = []
for root, _, files in os.walk(JELLYFIN_DIR):
    for f in files:
        # Copy web files separately (they go to assets, not jniLibs)
        if "jellyfin-web" in root or "wwwroot" in root:
            continue
        if f.endswith(".so") or f == "jellyfin" or f == "createdump":
            seed_files.append(os.path.join(root, f))

# We need to process recursively
libs_to_scan = list(seed_files)
scanned_files = set()
files_to_copy = {} # src_path -> dest_name

# Step 1: Discover all dependencies recursively
while libs_to_scan:
    current_file = libs_to_scan.pop(0)
    if current_file in scanned_files:
        continue
    scanned_files.add(current_file)
    
    basename = os.path.basename(current_file)
    safe_name = get_android_safe_name(basename)
    
    # Rename jellyfin executable to libjellyfin.so so Android extracts it
    if basename == "jellyfin":
        safe_name = "libjellyfin.so"
    elif basename == "createdump":
        safe_name = "libcreatedump.so"
        
    files_to_copy[current_file] = safe_name
    
    deps = get_dependencies(current_file)
    for dep in deps:
        if dep == "linux-vdso.so.1":
            continue
        
        # Determine path of the dependency
        dep_path = os.path.join(GLIBC_LIB_DIR, dep)
        if not os.path.exists(dep_path):
            # Check if it is in the jellyfin dir itself
            dep_path = os.path.join(JELLYFIN_DIR, dep)
            
        if os.path.exists(dep_path):
            dep_basename = os.path.basename(dep_path)
            dep_safe_name = get_android_safe_name(dep_basename)
            dep_dest_path = os.path.join(DEST_DIR, dep_safe_name)
            
            if dep_path not in files_to_copy:
                libs_to_scan.append(dep_path)

# Copy loader
loader_src = os.path.join(GLIBC_LIB_DIR, "ld-linux-aarch64.so.1")
if os.path.exists(loader_src):
    files_to_copy[loader_src] = "libld.so"

print(f"Total libraries/binaries discovered: {len(files_to_copy)}")

# Step 2: Copy files to DEST_DIR
copied_paths = {}
for src, dest_name in files_to_copy.items():
    dest_path = os.path.join(DEST_DIR, dest_name)
    shutil.copy2(src, dest_path)
    copied_paths[dest_path] = dest_name
    print(f"Copied {os.path.basename(src)} -> {dest_name}")

# Step 3: Patch NEEDED dependencies in copied files
for filepath, dest_name in copied_paths.items():
    deps = get_dependencies(filepath)
    for dep in deps:
        if dep in name_mapping:
            old_dep = dep
            new_dep = name_mapping[dep]
            if old_dep != new_dep:
                print(f"Patching {dest_name}: replacing dependency {old_dep} -> {new_dep}")
                subprocess.run([
                    "patchelf",
                    "--replace-needed", old_dep, new_dep,
                    filepath
                ])

# Step 4: Patch loader soname to prevent dynamic loader duplication and crashes
loader_path = os.path.join(DEST_DIR, "libld.so")
if os.path.exists(loader_path):
    print(f"Patching loader SONAME in {loader_path}...")
    subprocess.run([
        "patchelf",
        "--set-soname", "libld.so",
        loader_path
    ])

# Step 5: Patch apphost dll path in libjellyfin.so
libjellyfin_path = os.path.join(DEST_DIR, "libjellyfin.so")
if os.path.exists(libjellyfin_path):
    print(f"Patching apphost DLL path in {libjellyfin_path}...")
    with open(libjellyfin_path, "r+b") as f:
        data = f.read()
        idx = data.find(b"jellyfin.dll\x00")
        if idx != -1:
            # We use relative path going 6 levels up from lib/arm64 to / and then to writable data dir
            new_path = b"../../../../../../data/data/com.example.jellyfinserver/files/jellyfin/jellyfin.dll\x00"
            f.seek(idx)
            f.write(new_path)
            print(f"Successfully patched apphost DLL path to relative path at offset {idx}!")
        else:
            print("WARNING: b\"jellyfin.dll\\x00\" not found in libjellyfin.so!")

print("Dependency resolving and patching complete!")
