#!/usr/bin/env python3
"""
Names the filesystem inside a VeraCrypt volume, decrypted from the outside.

The companion to vcheader.py, and there for the same reason: our own driver
reporting what our own formatter wrote is one program agreeing with itself.
This reads the volume's first sectors with a key derived by hashlib and AES
supplied by `openssl enc`, then looks for the signature each filesystem is
known by - a boot sector's own words, or the ext2 magic in the superblock.

    vcfs.py <file> <header offset> <password> [pim] [--prf NAME] [--cipher NAME]
            [--dump out.img]

`--dump` writes the volume's whole decrypted data area out as a plain image, so
the filesystem inside it can be handed to a tool that knows it properly:

    vcfs.py vault.hc 0x10000 hidden1234 --dump hidden.img
    e2fsck -fn hidden.img          # or fsck.vfat -n, or file -s

The header offset says which volume to look inside, the same way vcheader.py
takes it: 0 for the volume's own header, 0x10000 for a hidden volume's. Where
its data begins is then read out of that header rather than assumed, so a
hidden volume needs nothing said about its size.

XTS data units are numbered from the start of the CONTAINER, not from the start
of the volume's data area: sector n of the data area is unit
(encrypted_area_start / 512) + n. That is what VeraCrypt does for a hidden
volume too, which is why one of these files can hold two independently
encrypted volumes at all.

Limits are vcheader.py's: single-cipher AES with SHA-512.
"""
import struct
import sys

from vcheader import aes_ecb, open_header, xts_decrypt

SECTOR = 512
BLOCK = 16
#: How much of a volume to decrypt at a time. Whole megabytes of sectors.
DUMP_CHUNK = 8 * 1024 * 1024


def xts_decrypt_sectors(key1: bytes, key2: bytes, data: bytes, first_unit: int,
                        cipher: str = "aes") -> bytes:
    """The same XTS as vcheader's, for many sectors at once.

    Sector by sector it would be two `openssl` processes per 512 bytes, which is
    minutes for a small volume and hours for a real one. The tweaks for every sector
    are one ECB encryption together, and the whole masked body is one more.
    """
    count = len(data) // SECTOR
    seeds = aes_ecb(key2, b"".join(struct.pack("<Q", first_unit + i) + b"\0" * 8
                                   for i in range(count)), decrypt=False, cipher=cipher)
    tweaks = []
    for i in range(count):
        t = int.from_bytes(seeds[i * BLOCK:(i + 1) * BLOCK], "little")
        for _ in range(SECTOR // BLOCK):
            tweaks.append(t.to_bytes(BLOCK, "little"))
            t <<= 1
            if t >> 128:
                t = (t ^ (1 << 128)) ^ 0x87     # the GF(2^128) reduction polynomial
    mask = b"".join(tweaks)
    masked = bytes(a ^ b for a, b in zip(data, mask))
    return bytes(a ^ b for a, b in zip(
        aes_ecb(key1, masked, decrypt=True, cipher=cipher), mask))


def read_data(path: str, area_start: int, key1: bytes, key2: bytes,
              offset: int, length: int, cipher: str = "aes") -> bytes:
    """Plaintext of [offset, offset+length) counted from the data area's start."""
    first = offset // SECTOR
    count = (offset % SECTOR + length + SECTOR - 1) // SECTOR
    out = bytearray()
    with open(path, "rb") as f:
        for i in range(count):
            unit = area_start // SECTOR + first + i
            f.seek(area_start + (first + i) * SECTOR)
            out += xts_decrypt(key1, key2, f.read(SECTOR), unit, cipher)
    start = offset % SECTOR
    return bytes(out[start:start + length])


def identify(boot: bytes, super_block: bytes) -> str:
    """What the bytes say they are. Deliberately literal - no probing logic."""
    if boot[3:11] == b"EXFAT   ":
        return "exFAT"
    if struct.unpack("<H", super_block[56:58])[0] == 0xEF53:
        rev = struct.unpack("<I", super_block[76:80])[0]
        return "ext2/3/4 (superblock magic 0xEF53, rev %d)" % rev
    if boot[82:87] == b"FAT32":
        return "FAT32"
    if boot[54:59] in (b"FAT12", b"FAT16", b"FAT  "):
        return "FAT16/FAT12 (%s)" % boot[54:59].decode("ascii", "replace").strip()
    return "unrecognised (first bytes %s)" % boot[:16].hex()


def main() -> int:
    argv = sys.argv[1:]
    dump, prf, cipher = None, None, "aes"
    for flag in ("--dump", "--prf", "--cipher"):
        if flag in argv:
            i = argv.index(flag)
            value = argv[i + 1]
            del argv[i:i + 2]
            dump, prf, cipher = (value, prf, cipher) if flag == "--dump" else \
                                (dump, value, cipher) if flag == "--prf" else \
                                (dump, prf, value)
    path, offset, password = argv[0], int(argv[1], 0), argv[2].encode()
    pim = int(argv[3]) if len(argv) > 3 else 0

    with open(path, "rb") as f:
        f.seek(offset)
        header = f.read(512)

    plain, prf_used = open_header(header, password, pim, prf, cipher)
    if plain is None:
        print("no VERA magic - wrong password, PIM, cipher, PRF or offset")
        return 1

    area_start, area_len = struct.unpack(">QQ", plain[44:60])
    key1, key2 = plain[192:224], plain[224:256]

    boot = read_data(path, area_start, key1, key2, 0, 512, cipher)
    # The ext2 family keeps its superblock 1024 bytes in, whatever the block size.
    sb = read_data(path, area_start, key1, key2, 1024, 256, cipher)

    print("%s @ 0x%x" % (path, offset))
    print("  derivation           %s (cipher %s)" % (prf_used, cipher))
    print("  data area            %d bytes at 0x%x" % (area_len, area_start))
    print("  filesystem           %s" % identify(boot, sb))

    if dump:
        # In chunks, because the whole volume at once means several copies of it in
        # memory at the same time - and a real volume is not 10 MB.
        done = 0
        with open(path, "rb") as f, open(dump, "wb") as out:
            f.seek(area_start)
            while done < area_len:
                n = min(DUMP_CHUNK, area_len - done)
                body = f.read(n)
                if len(body) != n:
                    print("  short read: the file ends before the data area does")
                    return 1
                out.write(xts_decrypt_sectors(
                    key1, key2, body, (area_start + done) // SECTOR, cipher))
                done += n
        print("  wrote                %s (%d bytes)" % (dump, area_len))
    return 0


if __name__ == "__main__":
    sys.exit(main())
