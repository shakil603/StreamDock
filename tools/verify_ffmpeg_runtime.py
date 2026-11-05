#!/usr/bin/env python3
"""Audit FFmpeg DT_NEEDED dependency availability in the APK's runtime archives.

This is a static ELF/ABI audit, not Android execution or dynamic symbol validation.
The app must EXTRACT both archives and use the audited search paths before launch.
--without-shared-runtime models the 1.0.3 initialization bug and should fail.
"""
import argparse
import io
import posixpath
import struct
import zipfile
from collections import deque

# Public Android native libraries. libc++_shared is deliberately NOT a system lib.
SYSTEM = {
    "libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so", "libz.so",
    "libjnigraphics.so", "libEGL.so", "libGLESv1_CM.so", "libGLESv2.so", "libGLESv3.so",
    "libOpenSLES.so", "libOpenMAXAL.so", "libmediandk.so", "libvulkan.so",
    "libcamera2ndk.so", "libaaudio.so", "libnativewindow.so",
}
ABI_ELF = {"arm64-v8a": (2, 183), "armeabi-v7a": (1, 40), "x86": (1, 3), "x86_64": (2, 62)}


def elf(data):
    if data[:4] != b"\x7fELF" or data[5] != 1:
        raise ValueError("Not a supported little-endian ELF")
    bits = data[4]
    machine = struct.unpack_from("<H", data, 18)[0]
    if bits == 2:
        phoff = struct.unpack_from("<Q", data, 32)[0]
        entsize, count = struct.unpack_from("<HH", data, 54)
    elif bits == 1:
        phoff = struct.unpack_from("<I", data, 28)[0]
        entsize, count = struct.unpack_from("<HH", data, 42)
    else:
        raise ValueError("Unsupported ELF class")
    segments, dynamic = [], None
    for i in range(count):
        p = phoff + i * entsize
        kind = struct.unpack_from("<I", data, p)[0]
        if bits == 2:
            offset, vaddr = struct.unpack_from("<QQ", data, p + 8)
            size = struct.unpack_from("<Q", data, p + 32)[0]
        else:
            offset, vaddr = struct.unpack_from("<II", data, p + 4)
            size = struct.unpack_from("<I", data, p + 16)[0]
        if kind == 1:
            segments.append((vaddr, offset, size))
        elif kind == 2:
            dynamic = (offset, size)
    if dynamic is None:
        return (bits, machine), []
    need, strva = [], None
    step, fmt = (16, "<QQ") if bits == 2 else (8, "<II")
    for off in range(dynamic[0], dynamic[0] + dynamic[1], step):
        tag, value = struct.unpack_from(fmt, data, off)
        if tag == 0:
            break
        if tag == 1:
            need.append(value)
        elif tag == 5:
            strva = value
    if not need:
        return (bits, machine), []
    strbase = next(offset + strva - va for va, offset, size in segments if va <= strva < va + size)
    names = [data[strbase + n:data.index(b"\0", strbase + n)].decode("ascii") for n in need]
    return (bits, machine), names


def resolve_member(archive, name):
    seen = set()
    while name not in seen:
        seen.add(name)
        try:
            data = archive.read(name)
        except KeyError:
            return None
        if data[:4] == b"\x7fELF":
            return name, data
        # Upstream ZIPs retain relative symlinks as short target strings.
        if len(data) > 256:
            return None
        target = data.decode("utf-8", errors="strict")
        name = posixpath.normpath(posixpath.join(posixpath.dirname(name), target))
        if not name.startswith("usr/lib/"):
            return None
    raise ValueError("Library alias loop")


def audit(apk_path, without_shared=False):
    with zipfile.ZipFile(apk_path) as apk:
        abis = sorted({n.split('/')[1] for n in apk.namelist() if n.startswith("lib/") and n.endswith("/libffmpeg.so")})
        if not abis:
            raise AssertionError("No FFmpeg executable in APK")
        failures = []
        for abi in abis:
            prefix = "lib/" + abi + "/"
            ffmpeg = zipfile.ZipFile(io.BytesIO(apk.read(prefix + "libffmpeg.zip.so")))
            python = zipfile.ZipFile(io.BytesIO(apk.read(prefix + "libpython.zip.so")))
            roots = ([] if without_shared else [("python", python)]) + [("ffmpeg", ffmpeg)]

            def resolve(name):
                for label, archive in roots:
                    value = resolve_member(archive, "usr/lib/" + name)
                    if value is not None:
                        return label + ":" + value[0], value[1]
                try:
                    return "native:" + name, apk.read(prefix + name)
                except KeyError:
                    return None

            visited, missing = set(), set()
            queue = deque([("native:libffmpeg.so", apk.read(prefix + "libffmpeg.so"))])
            while queue:
                label, data = queue.popleft()
                if label in visited:
                    continue
                visited.add(label)
                arch, needed = elf(data)
                if arch != ABI_ELF[abi]:
                    raise AssertionError(f"Wrong ELF architecture for {abi}: {label}: {arch}")
                for name in needed:
                    found = resolve(name)
                    if found is not None:
                        queue.append(found)
                    elif name not in SYSTEM:
                        missing.add(name)
            cpp = resolve("libc++_shared.so")
            print(f"{abi}: {len(visited)} ELF files inspected")
            print("  libc++_shared.so: " + (cpp[0] if cpp else "MISSING"))
            if missing:
                print("  Missing non-system dependencies: " + ", ".join(sorted(missing)))
                failures.extend(f"{abi}/{name}" for name in sorted(missing))
            elif cpp is None:
                failures.append(f"{abi}/libc++_shared.so")
            else:
                print("  PASS: DT_NEEDED non-system dependency closure available for this ABI")
            ffmpeg.close()
            python.close()
        if failures:
            raise AssertionError("Missing runtime dependencies: " + ", ".join(failures))
        print("PASS: native dependency availability/ABI checks. Android linker/symbol execution still requires a device test.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk")
    parser.add_argument("--without-shared-runtime", action="store_true")
    args = parser.parse_args()
    audit(args.apk, args.without_shared_runtime)
