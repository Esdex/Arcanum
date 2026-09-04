#!/usr/bin/env python3
"""
Prints the fields of a VeraCrypt volume header, decrypted from the outside.

Nothing here shares code with Arcanum: PBKDF2 comes from hashlib and the AES
primitive from `openssl enc -aes-256-ecb`, with the XTS layer written out below
(openssl's enc has no XTS mode). That is the point of it - a header written by
our own code, read back by our own code, would agree with itself about a wrong
value. This found two: a hidden volume recording its own size as zero, which is
the volume's length to VeraCrypt's Windows driver, and a protection boundary
computed 128 KB past where the hidden volume actually starts.

    vcheader.py <file> <offset> <password> [pim]

Offsets worth asking about:

    0                       the volume's primary header
    0x10000                 the hidden volume's primary header
    <size> - 0x20000        the volume's backup header
    <size> - 0x10000        the hidden volume's backup header

Limits: single-cipher AES volumes with a PBKDF2 hash of SHA-512, which is what
the test fixtures use. A cascade needs the per-cipher key slicing, and Argon2id
needs its own derivation - neither is here, because neither was needed yet.

A volume to point it at comes from desktop VeraCrypt (see the recipe in
HiddenProtectionTest) or from Arcanum itself:

    adb shell am instrument -w -e class zip.arcanum.crypto.HiddenVolumeCreateTest \
        zip.arcanum.test/androidx.test.runner.AndroidJUnitRunner
    adb pull /sdcard/Android/data/zip.arcanum/files/arcanum-hidden.hc

Header layout (VeraCrypt src/Common/Volumes.c):
    0..63    salt
    64..     encrypted body, XTS data unit 0
      +0     "VERA"
      +4     version, +6 min program version
      +8     CRC32 of the master keydata
      +28    hidden volume size      <- the field this exists to read
      +36    volume size
      +44    encrypted area start
      +52    encrypted area length
      +60    flags, +64 sector size
      +188   CRC32 of bytes 0..187
"""
import hashlib
import struct
import subprocess
import sys

ITERATIONS_DEFAULT = 500_000   # SHA-512, PIM 0


def aes_ecb(key: bytes, data: bytes, decrypt: bool) -> bytes:
    cmd = ["openssl", "enc", "-aes-256-ecb", "-nopad",
           "-K", key.hex(), "-d" if decrypt else "-e"]
    out = subprocess.run(cmd, input=data, stdout=subprocess.PIPE, check=True).stdout
    if len(out) != len(data):
        raise RuntimeError("openssl returned %d bytes for %d" % (len(out), len(data)))
    return out


def xts_decrypt(key1: bytes, key2: bytes, data: bytes, data_unit: int) -> bytes:
    """AES-XTS with a whole number of 16-byte blocks (headers always are)."""
    tweak = aes_ecb(key2, struct.pack("<Q", data_unit) + b"\0" * 8, decrypt=False)
    tweaks, t = [], int.from_bytes(tweak, "little")
    for _ in range(len(data) // 16):
        tweaks.append(t.to_bytes(16, "little"))
        t <<= 1
        if t >> 128:
            t = (t ^ (1 << 128)) ^ 0x87        # the GF(2^128) reduction polynomial
    masked = b"".join(bytes(a ^ b for a, b in zip(data[i * 16:i * 16 + 16], tweaks[i]))
                      for i in range(len(tweaks)))
    plain = aes_ecb(key1, masked, decrypt=True)
    return b"".join(bytes(a ^ b for a, b in zip(plain[i * 16:i * 16 + 16], tweaks[i]))
                    for i in range(len(tweaks)))


def crc32(data: bytes) -> int:
    import zlib
    return zlib.crc32(data) & 0xFFFFFFFF


def main() -> int:
    path, offset, password = sys.argv[1], int(sys.argv[2], 0), sys.argv[3].encode()
    pim = int(sys.argv[4]) if len(sys.argv) > 4 else 0
    iterations = 15_000 + pim * 1_000 if pim > 0 else ITERATIONS_DEFAULT

    with open(path, "rb") as f:
        f.seek(offset)
        header = f.read(512)

    salt, body = header[:64], header[64:]
    dk = hashlib.pbkdf2_hmac("sha512", password, salt, iterations, 192)
    plain = xts_decrypt(dk[0:32], dk[32:64], body, 0)

    if plain[:4] != b"VERA":
        print("no VERA magic - wrong password, PIM, cipher or offset")
        return 1

    hidden_size, volume_size, area_start, area_len = struct.unpack(">QQQQ", plain[28:60])
    header_crc, key_crc = struct.unpack(">I", plain[188:192])[0], struct.unpack(">I", plain[8:12])[0]

    print("%s @ 0x%x" % (path, offset))
    print("  version              %d (min program %#06x)" % struct.unpack(">HH", plain[4:8]))
    print("  hidden volume size   %d %s" % (hidden_size, "(field 28)" if hidden_size else "(field 28 - ZERO)"))
    print("  volume size          %d" % volume_size)
    print("  encrypted area start %d (0x%x)" % (area_start, area_start))
    print("  encrypted area len   %d" % area_len)
    print("  sector size          %d" % struct.unpack(">I", plain[64:68])[0])
    print("  header CRC           %s" % ("ok" if crc32(plain[0:188]) == header_crc else "MISMATCH"))
    print("  key area CRC         %s" % ("ok" if crc32(plain[192:448]) == key_crc else "MISMATCH"))
    print("  VeraCrypt would call this volume: %s" % ("hidden" if hidden_size else "normal"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
