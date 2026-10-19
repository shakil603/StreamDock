#!/usr/bin/env python3
"""Reject the exact missing Android-9 URL codec methods in the final APK.

Uses the DEX method-reference tables, not just source inspection. This targeted
check does not claim general Android API compatibility or device execution.
No third-party Python packages needed.
"""
import struct
import sys
import zipfile
from pathlib import Path


def method_refs(data):
    if not data.startswith(b"dex\n"):
        raise ValueError("Expected a standard DEX file")
    if struct.unpack_from("<I", data, 40)[0] != 0x12345678:
        raise ValueError("Unsupported DEX endianness")

    def u32(offset):
        return struct.unpack_from("<I", data, offset)[0]

    def string(index):
        offset = u32(u32(60) + 4 * index)
        # Skip the ULEB128 UTF-16 length. Names/descriptors used here are ASCII.
        while data[offset] & 0x80:
            offset += 1
        offset += 1
        end = data.index(b"\0", offset)
        return data[offset:end].decode("utf-8", errors="replace")

    def typename(index):
        return string(u32(u32(68) + 4 * index))

    def prototype(index):
        offset = u32(76) + 12 * index
        returns = typename(u32(offset + 4))
        args_offset = u32(offset + 8)
        args = ""
        if args_offset:
            args = "".join(typename(struct.unpack_from("<H", data, args_offset + 4 + 2 * i)[0])
                           for i in range(u32(args_offset)))
        return "(" + args + ")" + returns

    for index in range(u32(88)):
        offset = u32(92) + 8 * index
        owner_idx, proto_idx, name_idx = struct.unpack_from("<HHI", data, offset)
        owner = typename(owner_idx)
        if owner in {"Ljava/net/URLDecoder;", "Ljava/net/URLEncoder;"}:
            yield owner, string(name_idx), prototype(proto_idx)


def inspect(apk):
    refs = set()
    with zipfile.ZipFile(apk) as archive:
        dex_files = [n for n in archive.namelist() if n.startswith("classes") and n.endswith(".dex")]
        if not dex_files:
            raise AssertionError("No DEX files in APK")
        for name in dex_files:
            refs.update(method_refs(archive.read(name)))
    bad = [r for r in refs if r[1] in {"decode", "encode"} and "Ljava/nio/charset/Charset;" in r[2]]
    for ref in sorted(refs):
        print(" ".join(ref))
    if bad:
        raise AssertionError("Unsupported Android-9 Charset overload remains in APK: " + repr(bad))
    for owner, method in [("Ljava/net/URLDecoder;", "decode"), ("Ljava/net/URLEncoder;", "encode")]:
        expected = (owner, method, "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;")
        if expected not in refs:
            raise AssertionError("Expected compatible method reference not found: " + repr(expected))
    print("PASS: both URL codec String overloads present; no Charset overload references in APK.")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: python3 tools/verify_url_codec_dex.py path/to/app.apk")
    inspect(Path(sys.argv[1]))
