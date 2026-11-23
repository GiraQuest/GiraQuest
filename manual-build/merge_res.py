#!/usr/bin/env python3
"""Fusiona directorios res/ como el 'mergeResources' de AGP:
- archivos no-values: mismo path relativo -> se queda el de mayor prioridad
  (app > librerias, entre librerias el primero en orden alfabetico);
  si el contenido es identico, sin aviso; si difiere, aviso.
- values*.xml: se fusionan por (tipo, nombre). styleable con el mismo nombre
  fusiona sus <attr> (union por nombre de attr). Otros recursos con el mismo
  (tipo, nombre) y distinto valor: gana el de mayor prioridad, con aviso.
Uso: merge_res.py <dir_salida> <res_app> <res_lib1> <res_lib2> ...
"""
import os, sys, hashlib
import xml.etree.ElementTree as ET

for prefix, uri in [("android", "http://schemas.android.com/apk/res/android"),
                    ("tools", "http://schemas.android.com/tools"),
                    ("app", "http://schemas.android.com/apk/res-auto"),
                    ("xliff", "urn:oasis:names:tc:xliff:document:1.2")]:
    ET.register_namespace(prefix, uri)

def canon(el):
    return ET.tostring(el, encoding="unicode")

def is_values_dir(d):
    return d == "values" or d.startswith("values-")

def main():
    out_dir, srcs = sys.argv[1], sys.argv[2:]
    os.makedirs(out_dir, exist_ok=True)
    warnings = []
    # estado de fusion por directorio relativo
    values_state = {}   # reldir -> {(tipo,nombre): (elemento, prioridad, origen)}
    values_order = {}   # reldir -> [claves en orden de aparicion]
    for prio, src in enumerate(srcs):
        if not os.path.isdir(src):
            continue
        for root, dirs, files in os.walk(src):
            reldir = os.path.relpath(root, src)
            for fn in files:
                rel = os.path.join(reldir, fn) if reldir != "." else fn
                full = os.path.join(root, fn)
                if is_values_dir(reldir) and fn.endswith(".xml"):
                    try:
                        tree = ET.parse(full)
                    except ET.ParseError as e:
                        warnings.append(f"{rel} ({src}): XML no parseable: {e}")
                        continue
                    r = tree.getroot()
                    if r.tag != "resources":
                        continue
                    state = values_state.setdefault(reldir, {})
                    order = values_order.setdefault(reldir, [])
                    for el in list(r):
                        if el.tag == "eat-comment":
                            continue
                        name = el.get("name")
                        if not name:
                            continue
                        key = (el.tag, name)
                        if key in state:
                            old_el, old_prio, old_src = state[key]
                            if el.tag == "styleable":
                                # fusionar attrs (union por nombre)
                                have = {c.get("name") for c in old_el if c.tag == "attr"}
                                for c in list(el):
                                    if c.tag == "attr" and c.get("name") not in have:
                                        old_el.append(c)
                                        have.add(c.get("name"))
                            elif canon(el) == canon(old_el):
                                pass  # identico
                            else:
                                warnings.append(
                                    f"{reldir}/{el.tag}@{name}: conflicto, gana {old_src} (prio {old_prio}) sobre {src}")
                        else:
                            state[key] = (el, prio, src)
                            order.append(key)
                else:
                    dst = os.path.join(out_dir, rel)
                    if os.path.exists(dst):
                        old = open(dst, "rb").read()
                        new = open(full, "rb").read()
                        if old != new:
                            warnings.append(f"{rel}: contenido distinto, se queda el primero ({src} descartado)")
                        continue
                    os.makedirs(os.path.dirname(dst), exist_ok=True)
                    with open(full, "rb") as f_in, open(dst, "wb") as f_out:
                        f_out.write(f_in.read())
    # escribir values fusionados
    for reldir, state in values_state.items():
        d = os.path.join(out_dir, reldir)
        os.makedirs(d, exist_ok=True)
        root = ET.Element("resources")
        for key in values_order[reldir]:
            el, prio, src = state[key]
            root.append(el)
        ET.ElementTree(root).write(os.path.join(d, "values.xml"),
                                   encoding="utf-8", xml_declaration=True)
    print(f"directorios values fusionados: {len(values_state)}")
    for w in warnings:
        print("AVISO:", w)
    print(f"avisos totales: {len(warnings)}")

if __name__ == "__main__":
    main()
