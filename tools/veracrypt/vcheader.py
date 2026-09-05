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

    vcheader.py <file> <offset> <password> [pim] [--prf NAME] [--cipher NAME]

Offsets worth asking about:

    0                       the volume's primary header
    0x10000                 the hidden volume's primary header
    <size> - 0x20000        the volume's backup header
    <size> - 0x10000        the hidden volume's backup header

The derivation is found by trying each one it knows, unless --prf names it:
SHA-512, SHA-256 and BLAKE2s-256 come from hashlib, Argon2id from
`openssl kdf ARGON2ID` with the cost VeraCrypt computes from the PIM. Whirlpool
and Streebog are not here - neither is in a stock OpenSSL 3 or in hashlib.

The cipher is AES unless --cipher says otherwise, and the only other one is
Camellia, because those two are what `openssl enc` can do. Serpent, Twofish and
Kuznyechik would each have to be written out here, and a cascade needs the
per-cipher key slicing as well - a volume this cannot open says so rather than
guessing. For those, mount the volume with desktop VeraCrypt instead.

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

ITERATIONS_DEFAULT = 500_000   # every PBKDF2 PRF, PIM 0 (Pkcs5.c, non-boot)

#: In the order worth trying. Argon2id is last because one attempt costs 416 MiB.
PRFS = ("sha512", "sha256", "blake2s", "argon2id")


def argon2id_params(pim: int) -> tuple:
    """(iterations, memory cost in KiB) - VeraCrypt's get_argon2_params (Pkcs5.c)."""
    p = pim if pim > 0 else 12          # PIM 0 means 12: 416 MiB and 6 passes
    m_cost_mib = min(64 + (p - 1) * 32, 1024)
    t_cost = 3 + (p - 1) // 3 if p <= 31 else 13 + (p - 31)
    return t_cost, m_cost_mib * 1024


def derive_header_key(password: bytes, salt: bytes, prf: str, pim: int) -> bytes:
    """The 192 bytes a header is unlocked with: K1 || K2 || (unused)."""
    if prf == "argon2id":
        t_cost, m_cost = argon2id_params(pim)
        cmd = ["openssl", "kdf", "-keylen", "192", "-binary",
               "-kdfopt", "hexpass:" + password.hex(),
               "-kdfopt", "hexsalt:" + salt.hex(),
               "-kdfopt", "lanes:1", "-kdfopt", "threads:1",
               "-kdfopt", "memcost:%d" % m_cost, "-kdfopt", "iter:%d" % t_cost,
               "ARGON2ID"]
        out = subprocess.run(cmd, stdout=subprocess.PIPE, check=True).stdout
        if len(out) != 192:
            raise RuntimeError("openssl kdf returned %d bytes" % len(out))
        return out
    iterations = ITERATIONS_DEFAULT if pim == 0 else 15_000 + pim * 1_000
    return hashlib.pbkdf2_hmac(prf, password, salt, iterations, 192)


def open_header(header: bytes, password: bytes, pim: int, prf: str = None,
                cipher: str = "aes"):
    """Decrypts a 512-byte header, returning (plaintext, prf) or (None, None)."""
    salt, body = header[:64], header[64:]
    for name in ((prf,) if prf else PRFS):
        dk = derive_header_key(password, salt, name, pim)
        plain = xts_decrypt(dk[0:32], dk[32:64], body, 0, cipher)
        if plain[:4] == b"VERA":
            return plain, name
    return None, None


#: What `openssl enc` calls the ciphers this can drive, by VeraCrypt's name for them.
CIPHERS = {"aes": "-aes-256-ecb", "camellia": "-camellia-256-ecb"}


def aes_ecb(key: bytes, data: bytes, decrypt: bool, cipher: str = "aes") -> bytes:
    cmd = ["openssl", "enc", CIPHERS[cipher], "-nopad",
           "-K", key.hex(), "-d" if decrypt else "-e"]
    out = subprocess.run(cmd, input=data, stdout=subprocess.PIPE, check=True).stdout
    if len(out) != len(data):
        raise RuntimeError("openssl returned %d bytes for %d" % (len(out), len(data)))
    return out


def xts_decrypt(key1: bytes, key2: bytes, data: bytes, data_unit: int,
                cipher: str = "aes") -> bytes:
    """XTS with a whole number of 16-byte blocks (headers always are)."""
    tweak = aes_ecb(key2, struct.pack("<Q", data_unit) + b"\0" * 8,
                    decrypt=False, cipher=cipher)
    tweaks, t = [], int.from_bytes(tweak, "little")
    for _ in range(len(data) // 16):
        tweaks.append(t.to_bytes(16, "little"))
        t <<= 1
        if t >> 128:
            t = (t ^ (1 << 128)) ^ 0x87        # the GF(2^128) reduction polynomial
    masked = b"".join(bytes(a ^ b for a, b in zip(data[i * 16:i * 16 + 16], tweaks[i]))
                      for i in range(len(tweaks)))
    plain = aes_ecb(key1, masked, decrypt=True, cipher=cipher)
    return b"".join(bytes(a ^ b for a, b in zip(plain[i * 16:i * 16 + 16], tweaks[i]))
                    for i in range(len(tweaks)))


def crc32(data: bytes) -> int:
    import zlib
    return zlib.crc32(data) & 0xFFFFFFFF


def main() -> int:
    argv = sys.argv[1:]
    prf, cipher = None, "aes"
    if "--prf" in argv:
        i = argv.index("--prf")
        prf = argv[i + 1]
        del argv[i:i + 2]
    if "--cipher" in argv:
        i = argv.index("--cipher")
        cipher = argv[i + 1]
        del argv[i:i + 2]
    path, offset, password = argv[0], int(argv[1], 0), argv[2].encode()
    pim = int(argv[3]) if len(argv) > 3 else 0

    with open(path, "rb") as f:
        f.seek(offset)
        header = f.read(512)

    plain, prf_used = open_header(header, password, pim, prf, cipher)
    if plain is None:
        print("no VERA magic - wrong password, PIM, cipher, PRF or offset")
        return 1

    hidden_size, volume_size, area_start, area_len = struct.unpack(">QQQQ", plain[28:60])
    header_crc, key_crc = struct.unpack(">I", plain[188:192])[0], struct.unpack(">I", plain[8:12])[0]

    print("%s @ 0x%x" % (path, offset))
    print("  derivation           %s (cipher %s)" % (prf_used, cipher))
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
