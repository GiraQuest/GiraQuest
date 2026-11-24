#!/usr/bin/env python3
"""Mini-resolutor de dependencias Maven (sin Java, solo curl + XML).
Semantica de Gradle: ante conflicto de versiones gana la MAS NUEVA.
Se itera hasta punto fijo: en cada pasada se expanden solo las versiones
elegidas y se recogen todas las candidatas; la elegida es la maxima.
Salida: deps/artifacts/<group>__<artifact>-<version>.<ext>, deps/resolved.json
"""
import json, os, re, subprocess, sys
import xml.etree.ElementTree as ET

BASE = os.path.expanduser("~/workspace/giraquest-port/manual-build/deps")
POM_DIR = os.path.join(BASE, "poms")
ART_DIR = os.path.join(BASE, "artifacts")
os.makedirs(POM_DIR, exist_ok=True)
os.makedirs(ART_DIR, exist_ok=True)

CENTRAL = "https://repo.maven.apache.org/maven2"
GOOGLE = "https://dl.google.com/dl/android/maven2"
JITPACK = "https://jitpack.io"

ROOTS = [
    ("androidx.core", "core-ktx", "1.12.0"),
    ("androidx.appcompat", "appcompat", "1.6.1"),
    ("com.google.android.material", "material", "1.11.0"),
    ("androidx.recyclerview", "recyclerview", "1.3.2"),
    ("androidx.lifecycle", "lifecycle-runtime-ktx", "2.7.0"),
    ("androidx.activity", "activity-ktx", "1.8.2"),
    ("androidx.fragment", "fragment-ktx", "1.6.2"),
    ("com.squareup.okhttp3", "okhttp", "4.12.0"),
    ("org.jetbrains.kotlinx", "kotlinx-coroutines-android", "1.7.3"),
    ("com.github.MuntashirAkon", "libadb-android", "3.1.1"),
    ("org.conscrypt", "conscrypt-android", "2.5.3"),
    ("org.bouncycastle", "bcprov-jdk15to18", "1.78.1"),
    ("org.bouncycastle", "bcpkix-jdk15to18", "1.78.1"),
    ("org.apache.commons", "commons-compress", "1.26.1"),
    ("org.tukaani", "xz", "1.9"),  # v0.1.8: commons-compress marca xz como optional -> sin el, SevenZFile (LZMA) revienta en runtime
    ("io.coil-kt", "coil", "2.6.0"),
    ("androidx.documentfile", "documentfile", "1.0.1"),
]

def repo_bases(group):
    if group.startswith("com.github."):
        return [JITPACK]
    if group.startswith("androidx.") or group in ("com.google.android.material",):
        return [GOOGLE, CENTRAL]
    return [CENTRAL, GOOGLE]

def path_of(group, artifact, version):
    return f"{group.replace('.', '/')}/{artifact}/{version}"

def curl(url, out):
    if os.path.exists(out) and os.path.getsize(out) > 0:
        return True
    r = subprocess.run(["curl", "-sSfL", "--retry", "2", "-o", out, url],
                       capture_output=True, text=True)
    if r.returncode != 0:
        if os.path.exists(out):
            os.remove(out)
        return False
    return True

def fetch_pom(group, artifact, version):
    dest = os.path.join(POM_DIR, f"{group}__{artifact}-{version}.pom")
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return dest
    for base in repo_bases(group):
        url = f"{base}/{path_of(group, artifact, version)}/{artifact}-{version}.pom"
        if curl(url, dest):
            return dest
    return None

NS = {"m": "http://maven.apache.org/POM/4.0.0"}

def txt(el):
    return el.text.strip() if el is not None and el.text else ""

def subst(s, props):
    if not s:
        return s
    for _ in range(10):
        m = re.search(r"\$\{([^}]+)\}", s)
        if not m:
            break
        val = props.get(m.group(1))
        if val is None:
            break
        s = s.replace(m.group(0), val)
    return s

def parse_pom(path):
    tree = ET.parse(path)
    root = tree.getroot()
    props = {}
    for p in root.findall("m:properties/m:*", NS):
        props[p.tag.split("}")[-1]] = (p.text or "").strip()
    parent = root.find("m:parent", NS)
    parent_coord = None
    if parent is not None:
        parent_coord = (txt(parent.find("m:groupId", NS)),
                        txt(parent.find("m:artifactId", NS)),
                        txt(parent.find("m:version", NS)))
    managed = {}
    dm = root.find("m:dependencyManagement/m:dependencies", NS)
    if dm is not None:
        for d in dm.findall("m:dependency", NS):
            g, a, v = txt(d.find("m:groupId", NS)), txt(d.find("m:artifactId", NS)), txt(d.find("m:version", NS))
            scope = txt(d.find("m:scope", NS))
            typ = txt(d.find("m:type", NS))
            if scope == "import":
                managed[(g, a)] = ("__import__", v)
            elif v:
                managed[(g, a)] = (v, typ or "jar")
    deps = []
    ds = root.find("m:dependencies", NS)
    if ds is not None:
        for d in ds.findall("m:dependency", NS):
            g = txt(d.find("m:groupId", NS))
            a = txt(d.find("m:artifactId", NS))
            v = txt(d.find("m:version", NS))
            scope = txt(d.find("m:scope", NS)) or "compile"
            typ = txt(d.find("m:type", NS)) or "jar"
            optional = txt(d.find("m:optional", NS)) == "true"
            excl = set()
            ex = d.find("m:exclusions", NS)
            if ex is not None:
                for e in ex.findall("m:exclusion", NS):
                    excl.add((txt(e.find("m:groupId", NS)), txt(e.find("m:artifactId", NS))))
            deps.append({"group": g, "artifact": a, "version": v, "scope": scope,
                         "type": typ, "optional": optional, "exclusions": excl})
    packaging = txt(root.find("m:packaging", NS)) or "jar"
    return {"props": props, "parent": parent_coord, "managed": managed,
            "deps": deps, "packaging": packaging}

def fetch_module(group, artifact, version):
    """Gradle Module Metadata (.module): AndroidX publica ahi las versiones
    que faltan en el POM. Devuelve dict (group, artifact) -> version."""
    dest = os.path.join(POM_DIR, f"{group}__{artifact}-{version}.module")
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        pass
    else:
        ok = False
        for base in repo_bases(group):
            url = f"{base}/{path_of(group, artifact, version)}/{artifact}-{version}.module"
            if curl(url, dest):
                ok = True
                break
        if not ok:
            return {}
    try:
        data = json.load(open(dest))
    except Exception:
        return {}
    out = {}
    for variant in data.get("variants", []):
        name = variant.get("name", "")
        if not any(k in name for k in ("ApiElements", "RuntimeElements",
                                        "ApiPublication", "RuntimePublication")):
            continue
        if "debug" in name or "sources" in name.lower():
            continue
        for d in variant.get("dependencies", []):
            g = d.get("group"); a = d.get("module")
            ver = d.get("version") or {}
            v = ver.get("requires") or ver.get("prefers") or ver.get("strictly")
            if g and a and v:
                out[(g, a)] = v
    return out

_pom_cache = {}
def load_pom_full(group, artifact, version, depth=0):
    key = (group, artifact, version)
    if key in _pom_cache:
        return _pom_cache[key]
    path = fetch_pom(group, artifact, version)
    if path is None:
        _pom_cache[key] = None
        return None
    pom = parse_pom(path)
    props = dict(pom["props"])
    managed = dict(pom["managed"])
    if pom["parent"] and depth < 8:
        pg, pa, pv = pom["parent"]
        pv = subst(pv, props)
        parent = load_pom_full(pg, pa, pv, depth + 1)
        if parent:
            merged_props = dict(parent["props"]); merged_props.update(props)
            props = merged_props
            merged_managed = dict(parent["managed"]); merged_managed.update(managed)
            managed = merged_managed
    for (g, a), (v, typ) in list(managed.items()):
        if v == "__import__" and depth < 8:
            bom = load_pom_full(g, a, subst(typ, props), depth + 1)
            if bom:
                del managed[(g, a)]
                for k, val in bom["managed"].items():
                    managed.setdefault(k, val)
                for k, val in bom["props"].items():
                    props.setdefault(k, val)
    # Fusionar .module (Gradle metadata): rellena versiones que el POM omite
    # y anade dependencias que solo existen ahi (caso AndroidX).
    deps = list(pom["deps"])
    try:
        mod = fetch_module(group, artifact, version)
    except Exception:
        mod = {}
    have = {(d["group"], d["artifact"]) for d in deps}
    for d in deps:
        if not d["version"] and (d["group"], d["artifact"]) in mod:
            d["version"] = mod[(d["group"], d["artifact"])]
    for (g, a), v in mod.items():
        if (g, a) not in have:
            deps.append({"group": g, "artifact": a, "version": v, "scope": "compile",
                         "type": "jar", "optional": False, "exclusions": set()})
    full = {"props": props, "managed": managed, "deps": deps,
            "packaging": pom["packaging"]}
    _pom_cache[key] = full
    return full

def resolve_version(dep, props, managed):
    v = dep["version"]
    if not v:
        m = managed.get((dep["group"], dep["artifact"]))
        if m:
            v = m[0]
    if v:
        v = subst(v, props)
    return v

def vkey(v):
    """Clave comparable para versiones semver-ish."""
    parts = []
    for tok in re.split(r"[.\-]", v):
        if tok.isdigit():
            parts.append((0, int(tok), ""))
        else:
            m = re.match(r"(\d+)(.*)", tok)
            if m:
                parts.append((0, int(m.group(1)), m.group(2)))
            else:
                order = {"alpha": -3, "beta": -2, "rc": -1, "snapshot": -4}
                parts.append((1, order.get(tok.lower(), 0), tok))
    return parts

# --- punto fijo: gana la version mas nueva ---
chosen = {(g, a): v for (g, a, v) in ROOTS}
failed_pom = set()
for it in range(6):
    candidates = {}
    for (g, a, v) in ROOTS:
        candidates.setdefault((g, a), set()).add(v)
    queue = [(g, a, chosen[(g, a)], 0) for (g, a, v) in ROOTS]
    expanded = set()
    while queue:
        g, a, v, depth = queue.pop(0)
        if (g, a, v) in expanded or depth > 10:
            continue
        expanded.add((g, a, v))
        full = load_pom_full(g, a, v)
        if full is None:
            failed_pom.add((g, a, v))
            continue
        if full["packaging"] == "pom":
            # BOM: solo aporta dependencyManagement, sin dependencias reales
            continue
        props = dict(full["props"])
        props.setdefault("project.groupId", g)
        props.setdefault("project.artifactId", a)
        props.setdefault("project.version", v)
        for dep in full["deps"]:
            if dep["optional"] or dep["scope"] in ("test", "provided", "system"):
                continue
            dv = resolve_version(dep, props, full["managed"])
            if not dv:
                continue
            if dv.startswith("[") or dv.startswith("("):
                # rango exacto "[x]" -> x directamente
                m = re.fullmatch(r"\[([^\],]+)\]", dv)
                if m:
                    dv = m.group(1)
                else:
                    # maven-metadata.xml NO tiene namespace (a diferencia del POM)
                    meta = os.path.join(POM_DIR, f"meta-{dep['group']}__{dep['artifact']}.xml")
                    got = False
                    for base in repo_bases(dep["group"]):
                        if curl(f"{base}/{dep['group'].replace('.', '/')}/{dep['artifact']}/maven-metadata.xml", meta):
                            got = True
                            break
                    if got:
                        t = ET.parse(meta).getroot()
                        dv = txt(t.find("versioning/latest")) or txt(t.find("versioning/release")) or dv
            candidates.setdefault((dep["group"], dep["artifact"]), set()).add(dv)
            key = (dep["group"], dep["artifact"])
            if chosen.get(key) == dv:
                queue.append((dep["group"], dep["artifact"], dv, depth + 1))
    new_chosen = {}
    for key, vers in candidates.items():
        new_chosen[key] = max(vers, key=vkey)
    # las raices no se mueven
    for (g, a, v) in ROOTS:
        new_chosen[(g, a)] = v
    print(f"pasada {it}: {len(new_chosen)} artefactos")
    if new_chosen == chosen:
        print("punto fijo alcanzado")
        break
    changed = {k: (chosen.get(k), v) for k, v in new_chosen.items() if chosen.get(k) != v}
    for k, (old, new) in sorted(changed.items()):
        print(f"  {k[0]}:{k[1]} {old} -> {new}")
    chosen = new_chosen

# packaging de cada elegido + descarga
downloaded = {}
missing = []
for (g, a), v in sorted(chosen.items()):
    full = load_pom_full(g, a, v)
    if full and full["packaging"] == "pom":
        print(f"SKIP BOM {g}:{a}:{v}")
        continue
    ext = "aar" if (full and full["packaging"] == "aar") else "jar"
    dest = os.path.join(ART_DIR, f"{g}__{a}-{v}.{ext}")
    ok = False
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        ok = True
    else:
        for base in repo_bases(g):
            url = f"{base}/{path_of(g, a, v)}/{a}-{v}.{ext}"
            if curl(url, dest):
                ok = True
                break
    if not ok and ext == "aar":
        dest2 = os.path.join(ART_DIR, f"{g}__{a}-{v}.jar")
        for base in repo_bases(g):
            url2 = f"{base}/{path_of(g, a, v)}/{a}-{v}.jar"
            if curl(url2, dest2):
                ext = "jar"; dest = dest2; ok = True
                break
    if ok:
        downloaded[f"{g}:{a}:{v}"] = {"file": dest, "type": ext, "size": os.path.getsize(dest)}
        print(f"OK  {g}:{a}:{v} ({ext}, {os.path.getsize(dest)//1024} KB)")
    else:
        missing.append((g, a, v, ext))
        print(f"NO  {g}:{a}:{v} (.{ext})")

with open(os.path.join(BASE, "resolved.json"), "w") as f:
    json.dump({"chosen": {f"{g}:{a}": v for (g, a), v in sorted(chosen.items())},
               "downloaded": downloaded, "missing": missing,
               "failed_pom": sorted(failed_pom)}, f, indent=1)
print(f"\nDescargados: {len(downloaded)}, faltantes: {len(missing)}, POM fallidos: {len(failed_pom)}")
for m in missing:
    print("  FALTA:", m)
for f in sorted(failed_pom):
    print("  POM FALLO:", f)
