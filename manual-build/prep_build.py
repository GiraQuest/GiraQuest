#!/usr/bin/env python3
"""Prepara el build manual:
- extrae cada AAR (classes.jar, res/, package del manifest, jni arm64-v8a)
- detecta clases duplicadas entre jars y descarta jars totalmente redundantes
- escribe listas para build_manual.sh
"""
import json, os, re, shutil, subprocess, sys, zipfile

MB = os.path.expanduser("~/workspace/giraquest-port/manual-build")
WORK = os.path.join(MB, "work")
ART = os.path.join(MB, "deps", "artifacts")
APP = os.path.expanduser("~/workspace/giraquest-port/quest-app/app/src/main")
os.makedirs(WORK, exist_ok=True)

resolved = json.load(open(os.path.join(MB, "deps", "resolved.json")))
downloaded = resolved["downloaded"]

# jars que nunca van al dex (solo compilacion) o son redundantes conocidos
COMPILE_ONLY = {
    "org.jetbrains:annotations",
    "com.google.errorprone:error_prone_annotations",
    "com.squareup.okio:okio",  # multiplatform; el codigo real esta en okio-jvm
    "org.jetbrains.kotlin:kotlin-stdlib-common",  # metadatos; el stdlib ya lo lleva
}

aar_dirs = {}   # (g,a) -> dir extraido
jar_files = []  # (coord, path)
lib_packages = []  # packages de AAR con res/
jni_files = []  # (abi, path_in_apk, src)

for coord, info in sorted(downloaded.items()):
    g, a, v = coord.rsplit(":", 2)
    path = info["file"]
    typ = info["type"]
    key = (g, a)
    if typ == "aar":
        dest = os.path.join(WORK, "aar", f"{g}__{a}-{v}")
        if not os.path.exists(os.path.join(dest, "classes.jar")):
            os.makedirs(dest, exist_ok=True)
            with zipfile.ZipFile(path) as z:
                z.extractall(dest)
        aar_dirs[key] = dest
        cj = os.path.join(dest, "classes.jar")
        if os.path.exists(cj) and os.path.getsize(cj) > 0:
            jar_files.append((coord, cj))
        # package del manifest
        man = os.path.join(dest, "AndroidManifest.xml")
        pkg = None
        if os.path.exists(man):
            data = open(man, "rb").read().decode("utf-8", "replace")
            m = re.search(r'package="([^"]+)"', data)
            if m:
                pkg = m.group(1)
        resdir = os.path.join(dest, "res")
        has_res = os.path.isdir(resdir) and any(os.scandir(resdir))
        if pkg and has_res:
            lib_packages.append(pkg)
        # jni arm64-v8a
        jni_dir = os.path.join(dest, "jni", "arm64-v8a")
        if os.path.isdir(jni_dir):
            for f in sorted(os.listdir(jni_dir)):
                if f.endswith(".so"):
                    jni_files.append((f"lib/arm64-v8a/{f}", os.path.join(jni_dir, f)))
    else:
        jar_files.append((coord, path))

# --- deduplicacion por clases ---
def classes_of(jar):
    try:
        with zipfile.ZipFile(jar) as z:
            return {n for n in z.namelist() if n.endswith(".class")}
    except Exception:
        return set()

owner = {}  # clase -> coord que la aporta primero (segun orden de prioridad)
# prioridad: stdlib primero, luego el resto en orden alfabetico (determinista)
def prio(coord):
    g, a, v = coord.rsplit(":", 2)
    if a == "kotlin-stdlib":
        return (0, coord)
    if a == "kotlinx-coroutines-core-jvm":
        return (1, coord)
    if a == "okio-jvm":
        return (1, coord)
    if a == "okio":
        return (3, coord)
    return (2, coord)

jar_files_sorted = sorted(jar_files, key=lambda t: prio(t[0]))
redundant = []
kept_jars = []
for coord, path in jar_files_sorted:
    if coord.rsplit(":", 2)[0] + ":" + coord.rsplit(":", 2)[1] in COMPILE_ONLY:
        continue
    cls = classes_of(path)
    new = [c for c in cls if c not in owner]
    if cls and not new:
        redundant.append((coord, path))
        continue
    for c in new:
        owner[c] = coord
    kept_jars.append((coord, path))

# clases duplicadas que quedan entre jars conservados (aviso, d8 puede fallar)
from collections import Counter
allcls = Counter()
for coord, path in kept_jars:
    for c in classes_of(path):
        allcls[c] += 1
dups = {c: n for c, n in allcls.items() if n > 1}
if dups:
    print(f"AVISO: {len(dups)} clases duplicadas entre jars conservados:")
    for c in sorted(dups)[:20]:
        print(f"  {c} x{dups[c]}")

print(f"AAR extraidos: {len(aar_dirs)}, jars para dex: {len(kept_jars)}, redundantes descartados: {len(redundant)}")
for coord, path in redundant:
    print(f"  redundante: {coord}")
print(f"paquetes lib con res: {lib_packages}")
print(f"jni arm64-v8a: {[j[0] for j in jni_files]}")

# v0.1.12: binarios propios en src/main/jniLibs (AGP los recoge solo en
# Gradle; el build manual los suma aqui). Ej.: 7zzs oficial de 7-zip
# (Linux ARM64, estatico) como lib/arm64-v8a/lib7zzs.so para extraer los
# 7z cifrados del repo (commons-compress no abre los del servidor).
jni_libs_dir = os.path.join(APP, "jniLibs")
if os.path.isdir(jni_libs_dir):
    for abi in sorted(os.listdir(jni_libs_dir)):
        abi_dir = os.path.join(jni_libs_dir, abi)
        if not os.path.isdir(abi_dir):
            continue
        for f in sorted(os.listdir(abi_dir)):
            if f.endswith(".so"):
                apk_path = f"lib/{abi}/{f}"
                if apk_path not in [j[0] for j in jni_files]:
                    jni_files.append((apk_path, os.path.join(abi_dir, f)))
    print(f"jniLibs propios: {[j[0] for j in jni_files]}")

with open(os.path.join(WORK, "dex_jars.txt"), "w") as f:
    for coord, path in kept_jars:
        f.write(path + "\n")
with open(os.path.join(WORK, "compile_jars.txt"), "w") as f:
    # classpath de compilacion: todos los classes.jar + jars (incluidos compile-only),
    # ordenados por prioridad (stdlib y okio-jvm primero)
    for coord, path in sorted(jar_files, key=lambda t: prio(t[0])):
        f.write(path + "\n")
    f.write(os.path.expanduser("~/Android/Sdk/platforms/android-34/android.jar") + "\n")
with open(os.path.join(WORK, "lib_packages.txt"), "w") as f:
    f.write(":".join(sorted(set(lib_packages))) + "\n")
with open(os.path.join(WORK, "jni_files.txt"), "w") as f:
    for apk_path, src in jni_files:
        f.write(f"{apk_path}\t{src}\n")
with open(os.path.join(WORK, "aar_dirs.txt"), "w") as f:
    for (g, a), d in sorted(aar_dirs.items()):
        f.write(f"{g}:{a}\t{d}\n")
print("listas escritas en", WORK)
