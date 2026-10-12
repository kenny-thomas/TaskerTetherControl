#!/usr/bin/env python3
"""Reconstruct the archived, v1-signed APK byte-for-byte from CI artifact.

This one-time archival helper stores only PUBLIC signature files associated
with an already-signed APK. It neither reads nor contains any signing key.
The original APK hash is checked before this script succeeds.
"""
import base64
import hashlib
from pathlib import Path
import struct
import sys
from types import SimpleNamespace
import zipfile
import zlib

SIGNED_SHA256 = "1007896134fdb558e552fd35d75870ad6db5d8ebf1b8603b333a32d19fc96cd3"
UNSIGNED_SHA256 = "5bbe024cffe7a9bd53df8d6b5908b3a73bcdb909b509fda1452a172f0eac2fda"
SIGNATURE_FILES = (
    "META-INF/MANIFEST.MF",
    "META-INF/TASKERTE.SF",
    "META-INF/TASKERTE.RSA",
)
SIGNATURE_TIME = (2026, 10, 12, 2, 2, 16)


def signature_entries(data):
    raw = zlib.decompress(base64.b64decode(data))
    position = 0
    entries = []
    for filename in SIGNATURE_FILES:
        length = struct.unpack_from(">H", raw, position)[0]
        position += 2
        payload = raw[position:position + length]
        position += length
        if len(payload) != length:
            raise ValueError("Truncated signature bundle")
        info = SimpleNamespace(
            date_time=SIGNATURE_TIME,
            compress_type=zipfile.ZIP_DEFLATED,
            create_system=0,
            internal_attr=0,
            external_attr=0,
        )
        entries.append((filename, payload, info))
    if position != len(raw):
        raise ValueError("Signature bundle has trailing bytes")
    return entries


def reconstruct(unsigned, signature, output):
    original = Path(unsigned).read_bytes()
    if hashlib.sha256(original).hexdigest() != UNSIGNED_SHA256:
        raise ValueError("Unsigned APK does not match successful v1.4.12 build #12")

    archive = bytearray()
    central = bytearray()
    with zipfile.ZipFile(unsigned) as base:
        entries = signature_entries(Path(signature).read_text().strip())
        entries.extend((e.filename, base.read(e.filename), e)
                       for e in base.infolist())

        for name, raw, info in entries:
            offset = len(archive)
            y, month, day, hour, minute, second = info.date_time
            dos_time = (hour << 11) | (minute << 5) | (second // 2)
            dos_date = ((y - 1980) << 9) | (month << 5) | day
            method = info.compress_type
            if method == zipfile.ZIP_DEFLATED:
                compressor = zlib.compressobj(level=6, wbits=-15)
                compressed = compressor.compress(raw) + compressor.flush()
            elif method == zipfile.ZIP_STORED:
                compressed = raw
            else:
                raise ValueError("Unsupported compression method for " + name)
            crc = zlib.crc32(raw) & 0xFFFFFFFF
            flag = 0x808 if method == zipfile.ZIP_DEFLATED else 0x800
            version = 20 if method == zipfile.ZIP_DEFLATED else 10
            name_bytes = name.encode("utf-8")
            archive.extend(struct.pack(
                "<IHHHHHIIIHH", 0x04034B50, version, flag, method,
                dos_time, dos_date, 0 if flag & 8 else crc,
                0 if flag & 8 else len(compressed),
                0 if flag & 8 else len(raw), len(name_bytes), 0))
            archive.extend(name_bytes)
            archive.extend(compressed)
            if flag & 8:
                archive.extend(struct.pack("<IIII", 0x08074B50,
                                           crc, len(compressed), len(raw)))
            central.extend(struct.pack(
                "<IHHHHHHIIIHHHHHII", 0x02014B50,
                (info.create_system << 8) | version,
                version, flag, method, dos_time, dos_date, crc,
                len(compressed), len(raw), len(name_bytes),
                0, 0, 0, info.internal_attr, info.external_attr, offset))
            central.extend(name_bytes)

        central_start = len(archive)
        archive.extend(central)
        archive.extend(struct.pack(
            "<IHHHHIIH", 0x06054B50, 0, 0,
            len(entries), len(entries), len(central), central_start, 0))

    result = bytes(archive)
    actual = hashlib.sha256(result).hexdigest()
    if actual != SIGNED_SHA256:
        raise ValueError("Reconstructed APK SHA-256 mismatch: " + actual)
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(result)
    print("Reconstructed and verified:", output, len(result), actual)


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit("Usage: reconstruct.py UNSIGNED_APK SIGNATURE_BUNDLE OUTPUT_APK")
    reconstruct(*sys.argv[1:])
