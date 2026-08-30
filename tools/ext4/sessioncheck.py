#!/usr/bin/env python3
# Arcanum - VeraCrypt-compatible encrypted vault manager for Android
#
# Copyright (C) 2026 Esdex
# Licensed under Apache License 2.0
# SPDX-License-Identifier: Apache-2.0
#
# Host test harness for the clean-room ext4 library - PC-only, not shipped in the
# app. e2fsprogs and fuse2fs are used as external oracles (separate processes),
# never linked or copied. See issue #7.

r"""
The harness for holding the filesystem open across operations (#155, second half).

    ./sessioncheck.py

Every operation used to open the reader and the writable handle, do its work, and
close them again. That is what made the same superblock come back 9980 times in one
session - and, less obviously, it is what made a failed operation self-correcting:
the next one re-read everything from the disk, so nothing in memory could survive
being wrong. ext4_session.c keeps the handles for the life of a mount and has to
keep that second property by rule instead of by accident.

**The oracle is the old shape.** `session reopen` opens and closes per operation,
exactly as every other driver here does and as the app did until now, and it has
been measured against e2fsck and fuse2fs by the rest of this harness for months.
The claim under test is equivalence, so the test is equality: the same script run
both ways must leave two images that are equal byte for byte. Nothing is judged by
our own reader.

Three things the scripts are built to make visible:

  a failed write   `failwrite` refuses one write inside an operation, and the
                   driver then tells the session nothing at all. If a poisoned
                   handle were reused, its descriptor table and free counts - ahead
                   of the disk by whatever the dead operation had allocated - would
                   be flushed by the NEXT operation and the images would part.
  a drop           the caller-driven forget, for an operation abandoned with no
                   write having failed.
  a format         the volume replaced underneath with a different checksum seed. A
                   held reader would describe a filesystem that no longer exists.

And the open counters, because equality alone cannot tell holding from a session
that quietly reopens every time - which is correct, produces identical images, and
gives up the entire point of the change.

Both block sizes are run. At 4096 the bootstrap 1 KiB view of the superblock and
the filesystem's own size differ, which is the alternation the block cache was
first written blind to (#155, first half); at 1024 they are the same.
"""

import argparse
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
import time

IMG_BYTES = 48 * 1024 * 1024


def sh(*a, **k):
    return subprocess.run(a, capture_output=True, text=True, **k)


def sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def pattern(i):
    return (i ^ (i >> 8) ^ (i >> 16)) & 0xFF


# name -> (script, expected hold-mode opens, torn?)
#
# "torn" says the script leaves the volume marked as needing a check, so e2fsck is
# asked to repair rather than to agree it is clean.
SCENARIOS = {
    "plain": ("""
        mkdir /d
        create /d/a.bin
        append /d/a.bin 65536
        append /d/a.bin 65536
        list /d
        read /d/a.bin 0 4096
        create /d/b.bin
        append /d/b.bin 4096
        rename /d/b.bin /d/c.bin
        mtime /d/c.bin 1700000000
        unlink /d/c.bin
        mkdir /d/sub
        rmdir /d/sub
        list /
    """, {"reader": 1, "writer": 1}, False),

    "read-only": ("""
        list /
        list /
        list /
    """, {"reader": 1, "writer": 0}, False),

    "torn-write": ("""
        mkdir /t
        create /t/a.bin
        append /t/a.bin 131072
        failwrite 3
        append /t/a.bin 131072
        create /t/b.bin
        append /t/b.bin 65536
        list /t
    """, {"reader": 1, "writer": 2}, True),

    "torn-twice": ("""
        mkdir /t
        create /t/a.bin
        failwrite 2
        append /t/a.bin 65536
        create /t/b.bin
        failwrite 4
        append /t/b.bin 131072
        create /t/c.bin
        append /t/c.bin 8192
        list /t
    """, {"reader": 1, "writer": 3}, True),

    "torn-flush": ("""
        mkdir /f
        create /f/a.bin
        failflush 1
        append /f/a.bin 65536
        create /f/b.bin
        append /f/b.bin 8192
        list /f
    """, {"reader": 1, "writer": 2}, True),

    "drop": ("""
        mkdir /x
        create /x/a.bin
        drop
        append /x/a.bin 8192
        list /x
        drop
        list /x
    """, {"reader": 3, "writer": 2}, False),

    "format": ("""
        mkdir /p
        create /p/a.bin
        append /p/a.bin 8192
        format %d
        mkdir /q
        create /q/b.bin
        append /q/b.bin 8192
        list /q
    """ % IMG_BYTES, {"reader": 2, "writer": 2}, False),
}


def run_mode(session, img, mode, script_path):
    r = sh(session, img, mode, script_path)
    if r.returncode != 0:
        return None, f"session {mode} failed: {r.stderr.strip() or r.stdout.strip()}"
    fields = {}
    for pair in r.stdout.split():
        if "=" in pair:
            k, v = pair.split("=", 1)
            fields[k] = v
    return fields, None


def fsck_ok(img, torn, problems, label):
    """A volume nothing is wrong with, or - after a torn write - one that repairs."""
    if not torn:
        r = sh("e2fsck", "-fn", img)
        if r.returncode != 0:
            problems.append(f"{label}: e2fsck rejects the image (rc={r.returncode})\n"
                            f"           {r.stdout.strip()[:400]}")
        return
    r = sh("e2fsck", "-fy", img)
    if r.returncode not in (0, 1):
        problems.append(f"{label}: e2fsck could not repair the torn volume "
                        f"(rc={r.returncode})\n           {r.stdout.strip()[:400]}")
        return
    r = sh("e2fsck", "-fn", img)
    if r.returncode != 0:
        problems.append(f"{label}: still not clean after a repair (rc={r.returncode})")


def read_back(img, tmp, problems, label):
    """The plain scenario's file, through fuse2fs, byte by byte against its position.

    Equality with the old shape says the two agree; this says what they agree on is
    right, without asking our own reader."""
    mnt = os.path.join(tmp, "mnt")
    os.makedirs(mnt, exist_ok=True)
    proc = subprocess.Popen(["fuse2fs", img, mnt, "-o", "ro", "-f"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for _ in range(60):
        if os.path.ismount(mnt):
            break
        if proc.poll() is not None:
            break
        time.sleep(0.1)
    if not os.path.ismount(mnt):
        problems.append(f"{label}: fuse2fs would not mount the result")
        proc.kill()
        return
    try:
        data = open(os.path.join(mnt, "d", "a.bin"), "rb").read()
        if len(data) != 131072:
            problems.append(f"{label}: /d/a.bin is {len(data)} bytes, expected 131072")
        else:
            bad = next((i for i in range(len(data)) if data[i] != pattern(i)), None)
            if bad is not None:
                problems.append(f"{label}: /d/a.bin byte {bad} is {data[bad]}, "
                                f"expected {pattern(bad)}")
        if os.path.exists(os.path.join(mnt, "d", "c.bin")):
            problems.append(f"{label}: /d/c.bin survived its unlink")
    except OSError as e:
        problems.append(f"{label}: reading the result back failed: {e}")
    finally:
        sh("fusermount", "-u", mnt)
        try:
            proc.wait(timeout=30)
        except subprocess.TimeoutExpired:
            proc.kill()


def check_geometry(mkfs, session, block_size, problems, verbose):
    with tempfile.TemporaryDirectory() as tmp:
        base = os.path.join(tmp, "base.img")
        sh("truncate", "-s", str(IMG_BYTES), base)
        if sh(mkfs, base, "--bs", str(block_size)).returncode:
            problems.append(f"mkfs at block size {block_size} failed")
            return
        base_sha = sha(base)

        for name, (script, expect, torn) in SCENARIOS.items():
            label = f"[{block_size}] {name}"
            script_path = os.path.join(tmp, f"{name}.txt")
            with open(script_path, "w") as f:
                f.write("\n".join(l.strip() for l in script.strip().splitlines()) + "\n")

            imgs = {}
            fields = {}
            failed = False
            for mode in ("reopen", "hold"):
                imgs[mode] = os.path.join(tmp, f"{name}.{mode}.img")
                shutil.copyfile(base, imgs[mode])
                fields[mode], err = run_mode(session, imgs[mode], mode, script_path)
                if err:
                    problems.append(f"{label}: {err}")
                    failed = True
            if failed:
                continue

            # The claim. Everything else is here to make this line mean something.
            if sha(imgs["hold"]) != sha(imgs["reopen"]):
                problems.append(f"{label}: holding the handles produced a DIFFERENT "
                                f"image from opening them per operation")
                continue

            if fields["hold"]["failed"] != fields["reopen"]["failed"]:
                problems.append(f"{label}: the two modes disagree on which operations "
                                f"failed ({fields['hold']['failed']} vs "
                                f"{fields['reopen']['failed']})")

            got = {"reader": int(fields["hold"]["reader_opens"]),
                   "writer": int(fields["hold"]["writer_opens"])}
            if got != expect:
                problems.append(f"{label}: held opens {got}, expected {expect} - "
                                f"a session that reopens on every ask is equally "
                                f"correct and entirely pointless")

            # The two shapes have to actually be different, or equality proves nothing.
            if int(fields["reopen"]["reader_opens"]) <= got["reader"]:
                problems.append(f"{label}: the reopen mode did not open more often "
                                f"than the held one - the comparison is empty")

            if name == "read-only" and sha(imgs["hold"]) != base_sha:
                problems.append(f"{label}: a session that only read changed the volume")

            for mode in ("reopen", "hold"):
                fsck_ok(imgs[mode], torn, problems, f"{label} {mode}")
            if name == "plain":
                read_back(imgs["hold"], tmp, problems, label)

            if verbose:
                print(f"  {label}: ok  hold={fields['hold']} reopen={fields['reopen']}")


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument("--mkfs", default=os.path.join(here, "mkfs"))
    ap.add_argument("--session", default=os.path.join(here, "session"))
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    for t in (args.mkfs, args.session):
        if not os.path.exists(t):
            sys.exit(f"{t} not found - build it first")
    if not shutil.which("fuse2fs"):
        sys.exit("fuse2fs not found - it is the independent oracle here")

    problems = []
    for bs in (1024, 4096):
        check_geometry(args.mkfs, args.session, bs, problems, args.verbose)

    if problems:
        print("FAIL")
        for p in problems:
            print(f"     {p}")
        return 1
    print(f"{len(SCENARIOS)} scripts at two block sizes: holding the reader and the "
          f"writable handle across operations gives a byte-identical image to "
          f"opening them per operation, through torn writes, drops and a reformat")
    return 0


if __name__ == "__main__":
    sys.exit(main())
