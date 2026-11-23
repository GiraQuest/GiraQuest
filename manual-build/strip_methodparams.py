#!/usr/bin/env python3
"""Quita el atributo MethodParameters de los .class dentro de un jar.

Por que: spake2-android 2.2.1 (JitPack) se compilo con javac -parameters y
sus metodos sinteticos/mandated (p. ej. el <init> de un enum y valueOf)
llevan MethodParameters con name_index = 0 ("<no name>"). d8/R8 (tanto
build-tools 34.0.0 como 32.0.0) convierte ese indice en un String null y
muere con NullPointerException ("Cannot invoke String.length() because
<parameter1> is null") al escribir el dex. El atributo es solo metadato
de depuracion: quitarlo no cambia el comportamiento en runtime.

Uso: strip_methodparams.py <jar> [<jar> ...]  (reescribe in-place)
"""
import struct
import sys
import zipfile
from io import BytesIO

CP_TAGS = {1, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 15, 16, 17, 18, 19, 20}


def parse_cp(data, off):
    """Devuelve (dict idx->str para Utf8, nuevo offset tras el constant pool)."""
    (cp_count,) = struct.unpack_from(">H", data, off)
    off += 2
    utf8 = {}
    i = 1
    while i < cp_count:
        tag = data[off]
        off += 1
        if tag == 1:  # Utf8
            (ln,) = struct.unpack_from(">H", data, off)
            off += 2
            utf8[i] = data[off:off + ln].decode("utf-8", "replace")
            off += ln
        elif tag in (7, 8, 16, 19, 20):  # u2
            off += 2
        elif tag in (15,):  # u1 + u2
            off += 3
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):  # u4
            off += 4
        elif tag in (5, 6):  # u8, ocupa 2 entradas
            off += 8
            i += 1
        else:
            raise ValueError(f"tag de constant pool desconocido: {tag}")
        i += 1
    return utf8, off


def strip_class(data):
    """Quita MethodParameters de los metodos. Devuelve (nuevos_bytes, n_quitados)."""
    if data[:4] != b"\xca\xfe\xba\xbe":
        return data, 0
    # magic(4) + minor(2) + major(2) -> el constant pool empieza en 8
    utf8, off = parse_cp(data, 8)
    out = bytearray(data[:off])  # hasta el final del constant pool
    # access_flags, this_class, super_class
    out += data[off:off + 6]
    off += 6
    (n_if,) = struct.unpack_from(">H", data, off)
    out += data[off:off + 2 + 2 * n_if]
    off += 2 + 2 * n_if
    # fields
    (n_fields,) = struct.unpack_from(">H", data, off)
    out += data[off:off + 2]
    off += 2
    removed = 0
    for _ in range(n_fields):
        out, off, r = copy_member(data, off, utf8, out)
        removed += r
    # methods
    (n_methods,) = struct.unpack_from(">H", data, off)
    out += data[off:off + 2]
    off += 2
    for _ in range(n_methods):
        out, off, r = copy_member(data, off, utf8, out)
        removed += r
    # atributos de clase: copiar tal cual
    out += data[off:]
    return bytes(out), removed


def copy_member(data, off, utf8, out):
    """Copia un field/method, quitando MethodParameters. Devuelve (out, off, n)."""
    start = off
    off += 6  # access_flags, name_index, descriptor_index
    (n_attr,) = struct.unpack_from(">H", data, off)
    off += 2
    kept = bytearray()
    removed = 0
    for _ in range(n_attr):
        (name_idx,) = struct.unpack_from(">H", data, off)
        (ln,) = struct.unpack_from(">I", data, off + 2)
        total = 6 + ln
        if utf8.get(name_idx) == "MethodParameters":
            removed += 1
        else:
            kept += data[off:off + total]
        off += total
    out += data[start:start + 6]
    out += struct.pack(">H", n_attr - removed)
    out += kept
    return out, off, removed


def process_jar(path):
    """Reescribe a un archivo temporal y renombra solo si todo fue bien:
    si el parseo falla a mitad, el jar original queda intacto."""
    import os
    import tempfile
    total_classes = 0
    total_removed = 0
    changed = []
    with zipfile.ZipFile(path, "r") as zin:
        items = [(i, zin.read(i.filename)) for i in zin.infolist() if not i.is_dir()]
    out = []
    for info, blob in items:
        if info.filename.endswith(".class"):
            new, n = strip_class(blob)
            if n:
                total_classes += 1
                total_removed += n
                changed.append((info.filename, n))
                blob = new
        out.append((info, blob))
    fd, tmp = tempfile.mkstemp(dir=os.path.dirname(path), suffix=".tmpjar")
    try:
        with os.fdopen(fd, "wb") as f:
            with zipfile.ZipFile(f, "w", zipfile.ZIP_DEFLATED) as zout:
                for info, blob in out:
                    zout.writestr(info, blob)
        os.replace(tmp, path)
    except Exception:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise
    return total_classes, total_removed, changed


if __name__ == "__main__":
    for jar in sys.argv[1:]:
        try:
            nc, nr, changed = process_jar(jar)
        except Exception as e:
            print(f"{jar}: ERROR {e}")
            continue
        if nr:
            print(f"{jar}: quitados {nr} MethodParameters en {nc} clases: "
                  + ", ".join(f"{c}({n})" for c, n in changed[:5]))
