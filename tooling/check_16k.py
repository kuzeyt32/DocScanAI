#!/usr/bin/env python3
"""
check_16k.py - report the PT_LOAD p_align of every ELF64 shared object inside an
APK / AAR (zip) or a directory of .so files. Used to verify Android 15+ 16 KB
page-size compatibility.

Usage:
    py -3 tooling/check_16k.py <file.apk | file.aar | dir | file.so> [...]

Interpretation (Android 16 KB page-size requirement):
    p_align >= 0x4000 (16384)  -> 16KB-OK
    p_align == 0x1000 (4096)   -> NOT-16KB (will fail to load on 16 KB-page kernels)

This inspects the ACTUAL bytes; it never trusts release notes.
"""
import io
import os
import struct
import sys
import zipfile

ELFMAG = b"\x7fELF"


def parse_elf_palign(data: bytes):
    """Return (is64, [(ptype, palign), ...]) or None if not ELF."""
    if len(data) < 64 or data[:4] != ELFMAG:
        return None
    ei_class = data[4]          # 2 == ELFCLASS64
    ei_data = data[5]           # 1 == ELFDATA2LSB
    endian = "<" if ei_data == 1 else ">"
    if ei_class != 2:
        return (False, [])
    (e_phoff,) = struct.unpack_from(endian + "Q", data, 0x20)
    (e_phentsize,) = struct.unpack_from(endian + "H", data, 0x36)
    (e_phnum,) = struct.unpack_from(endian + "H", data, 0x38)
    segs = []
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        if off + 56 > len(data):
            break
        (p_type,) = struct.unpack_from(endian + "I", data, off)
        (p_align,) = struct.unpack_from(endian + "Q", data, off + 48)
        segs.append((p_type, p_align))
    return (True, segs)


def verdict(min_align):
    if min_align is None:
        return "NO-PT_LOAD"
    if min_align >= 0x4000:
        return "16KB-OK"
    return "NOT-16KB (min 0x%x)" % min_align


def report_one(label: str, data: bytes):
    parsed = parse_elf_palign(data)
    if parsed is None:
        print("%-58s :: not ELF (skipped)" % label)
        return
    is64, segs = parsed
    if not is64:
        print("%-58s :: ELF32 (skipped)" % label)
        return
    loads = [a for (t, a) in segs if t == 1]      # PT_LOAD == 1
    if not loads:
        print("%-58s :: PT_LOAD=0 -> %s" % (label, verdict(None)))
        return
    mn = min(loads)
    print("%-58s :: PT_LOAD=%d min_p_align=0x%x (%d) -> %s"
          % (label, len(loads), mn, mn, verdict(mn)))


def iter_from_zip(path: str):
    with zipfile.ZipFile(path) as z:
        for name in z.namelist():
            if name.endswith(".so") and (name.startswith("lib/") or "jniLibs" in name or "jni/" in name):
                yield name, z.read(name)


def main(argv):
    if len(argv) < 2:
        print(__doc__)
        return 2
    any_out = False
    for target in argv[1:]:
        if os.path.isdir(target):
            for root, _dirs, files in os.walk(target):
                for fn in sorted(files):
                    if fn.endswith(".so"):
                        p = os.path.join(root, fn)
                        with open(p, "rb") as fh:
                            report_one(p.replace("\\", "/"), fh.read())
                        any_out = True
        elif zipfile.is_zipfile(target):
            print("# %s" % target)
            entries = sorted(iter_from_zip(target))
            for name, data in entries:
                report_one(name, data)
                any_out = True
        elif os.path.isfile(target):
            with open(target, "rb") as fh:
                report_one(target.replace("\\", "/"), fh.read())
            any_out = True
        else:
            print("%s :: NOT FOUND" % target)
    return 0 if any_out else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
