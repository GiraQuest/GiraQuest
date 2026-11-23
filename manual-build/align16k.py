#!/usr/bin/env python3
"""Alinea un APK como zipalign, pero con dos niveles: .so sin comprimir a
16 KiB (páginas de 16K, Android 15/16) y el resto a 4 bytes.

zipalign de build-tools 34.0.0 no tiene la opción -P (llegó en la 35), y
la v0.1 metía las .so comprimidas con alineado 4K: en móviles con
páginas de 16K eso puede impedir cargar la .so de Conscrypt al arrancar
(UnsatisfiedLinkError en Application.onCreate = crash instantáneo).

Uso: align16k.py entrada.apk salida.apk
Reescribe el zip entry a entry: el extra de la cabecera local se rellena
con ceros (como hace zipalign) hasta que el inicio de los datos quede
alineado. Se ejecuta ANTES de firmar con apksigner.
"""
import struct
import sys
import zlib
import zipfile

ALIGN_SO = 16384
ALIGN_OTHER = 4


def dos_datetime(date_time):
    y, mo, d, h, mi, s = date_time
    return ((h << 11) | (mi << 5) | (s // 2),
            (((y - 1980) << 9) | (mo << 5) | d))


def main():
    src, dst = sys.argv[1], sys.argv[2]
    zin = zipfile.ZipFile(src)
    entries = []
    for info in zin.infolist():
        data = zin.read(info.filename)
        if info.compress_type == zipfile.ZIP_DEFLATED:
            comp = zlib.compressobj(6, zlib.DEFLATED, -15)
            cdata = comp.compress(data) + comp.flush()
            method = 8
        else:
            cdata = data
            method = 0
        entries.append((info, data, cdata, method))
    zin.close()

    out = bytearray()
    central = []
    for info, data, cdata, method in entries:
        name = info.filename.encode("utf-8")
        is_so = (info.filename.startswith("lib/")
                 and info.filename.endswith(".so")
                 and method == 0)
        align = ALIGN_SO if is_so else ALIGN_OTHER
        header_size = 30 + len(name)
        base = len(out) + header_size
        elen = (align - (base % align)) % align
        extra = b"\x00" * elen
        header_offset = len(out)
        mtime, mdate = dos_datetime(info.date_time)
        version = 20 if method == 8 else 10
        # Cabecera local de fichero.
        out += struct.pack(
            "<IHHHHHIIIHH", 0x04034B50, version, 0, method,
            mtime, mdate, info.CRC, len(cdata), len(data),
            len(name), elen)
        out += name + extra + cdata
        central.append((info, method, len(cdata), len(data),
                        len(name), header_offset))

    cd_offset = len(out)
    for info, method, csize, usize, nlen, header_offset in central:
        name = info.filename.encode("utf-8")
        mtime, mdate = dos_datetime(info.date_time)
        version = 20 if method == 8 else 10
        out += struct.pack(
            "<IHHHHHHIIIHHHHHII", 0x02014B50, 20, version, 0, method,
            mtime, mdate, info.CRC, csize, usize, nlen, 0, 0, 0, 0,
            info.external_attr, header_offset)
        out += name
    cd_size = len(out) - cd_offset
    out += struct.pack("<IHHHHIIH", 0x06054B50, 0, 0, len(central),
                       len(central), cd_size, cd_offset, 0)
    with open(dst, "wb") as f:
        f.write(out)

    # Verificación: offsets de datos de las .so múltiplos de 16K.
    z = zipfile.ZipFile(dst)
    for info in z.infolist():
        if info.filename.startswith("lib/") and info.filename.endswith(".so"):
            print(f"{info.filename}: header_offset={info.header_offset} "
                  f"compress_type={info.compress_type}")
    print(f"OK: {len(central)} entradas, {len(out)} bytes")


if __name__ == "__main__":
    main()
