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
Checks the block cache (#155) against a model that does not know its eviction policy.

    ./cachecheck.py

A cache is the one component here whose bug is silent in both directions: hold an
entry too long and a reader sees bytes that are no longer on the volume, throw
everything away and the volume is merely slow. So this asks two different kinds of
question.

Safety, and the model is deliberately weaker than the code: for every offset it
remembers only the value last written. A miss is always an acceptable answer - the
model has no idea which entries eviction kept - but a hit must carry exactly that
value, and a dropped or resized-away entry must be gone. Nothing here reimplements
LRU, because an oracle that shares the code's assumption agrees with its bugs.

Usefulness, which safety alone cannot express: a cache that stores nothing passes
every safety check ever written. So the last cases assert that the hot block of the
#155 access pattern survives a long walk of other blocks, and that the hit rate on
that pattern stays high.

The mixed-size case is here because the first version of this cache shipped past a
green stand and did nothing at all on a real volume. It emptied itself whenever the
block size changed, and the device alternates two sizes - a 1 KiB bootstrap read of
the superblock against the filesystem's own 4 KiB - so it was empty every time it
was asked. Safe, and worth exactly nothing. A stand that only ever spoke one size
could not see it; this one alternates them the way the device does.
"""

import argparse
import os
import random
import subprocess
import sys

SLOTS = 64          # what the module holds; used only for the capacity bound


class Cache:
    """The driver as a pipe: one line in, one line out."""

    def __init__(self, binary):
        self.p = subprocess.Popen([binary], stdin=subprocess.PIPE,
                                  stdout=subprocess.PIPE, text=True, bufsize=1)

    def cmd(self, line):
        self.p.stdin.write(line + "\n")
        self.p.stdin.flush()
        out = self.p.stdout.readline().strip()
        if out == "":
            raise SystemExit("driver died on: " + line)
        return out

    def new(self):
        assert self.cmd("new") == "ok"

    def read(self, off, length, tag):
        assert self.cmd("read %d %d %d" % (off, length, tag)) == "ok"

    def wrote(self, off, length, tag):
        assert self.cmd("wrote %d %d %d" % (off, length, tag)) == "ok"

    def drop(self, off, length):
        assert self.cmd("drop %d %d" % (off, length)) == "ok"

    def entry_len(self):
        return int(self.cmd("len").split()[1])

    def get(self, off, length):
        out = self.cmd("get %d %d" % (off, length))
        if out == "miss":
            return None
        if out.startswith("hit "):
            return int(out.split()[1])
        raise AssertionError("get %d/%d answered %r" % (off, length, out))

    def count(self):
        return int(self.cmd("count").split()[1])

    def close(self):
        self.p.stdin.close()
        self.p.wait()


def case_random(c, seed, ops, length, blocks):
    """Random traffic. A hit must always carry the value last written there."""
    rnd = random.Random(seed)
    model = {}
    dropped = set()
    hits = 0
    c.new()
    c.read(0, length, 1)          # give the cache its size
    model[0] = 1
    for _ in range(ops):
        off = rnd.randrange(blocks) * length
        roll = rnd.random()
        if roll < 0.30:
            tag = rnd.randrange(1, 2 ** 31)
            c.read(off, length, tag)
            model[off] = tag
            dropped.discard(off)
        elif roll < 0.50:
            tag = rnd.randrange(1, 2 ** 31)
            c.wrote(off, length, tag)
            model[off] = tag
            dropped.discard(off)
        elif roll < 0.90:
            got = c.get(off, length)
            if got is not None:
                hits += 1
                if off in dropped:
                    return "offset %d was dropped and came back as %d" % (off, got)
                if got != model.get(off):
                    return "offset %d answered %s, last written %s" % (
                        off, got, model.get(off))
        else:
            c.drop(off, length)
            model.pop(off, None)
            dropped.add(off)
        if c.count() > SLOTS:
            return "cache holds %d entries, more than the %d slots" % (c.count(), SLOTS)
    if hits == 0:
        return "not one hit in %d operations - the cache is not storing anything" % ops
    return None


def case_overwrite(c, length):
    """The second write of an offset is what a reader must see."""
    c.new()
    c.read(0, length, 1111)
    c.wrote(0, length, 2222)
    got = c.get(0, length)
    if got != 2222:
        return "overwritten entry answered %s, expected 2222" % got
    return None


def case_drop(c, length):
    """A dropped entry is gone, and the slot is reusable afterwards."""
    c.new()
    c.read(0, length, 4242)
    c.drop(0, length)
    if c.get(0, length) is not None:
        return "a dropped entry was still served"
    c.read(0, length, 4343)
    if c.get(0, length) != 4343:
        return "an offset could not be refilled after being dropped"
    return None


def case_adopts_larger(c):
    """A larger block size replaces the smaller one and takes nothing with it."""
    c.new()
    for i in range(8):
        c.read(i * 1024, 1024, 700 + i)
    if c.entry_len() != 1024:
        return "the cache did not adopt the first size it was shown"
    c.read(0, 4096, 900)
    if c.entry_len() != 4096:
        return "the cache did not adopt the larger size"
    for i in range(1, 8):
        if c.get(i * 1024, 1024) is not None:
            return "a 1024-byte entry survived the move to 4096-byte entries"
    if c.get(0, 4096) != 900:
        return "the entry that carried the new size was not kept"
    return None


def case_mixed_sizes(c):
    """
    The regression that a single-size stand could not see (#155).

    The device alternates a 1 KiB bootstrap read of the superblock with the
    filesystem's own 4 KiB blocks. A cache that empties itself whenever the size
    changes is safe and completely useless: on real traffic it is empty every time
    it is asked. So the small reads must leave the big entries alone.
    """
    c.new()
    hot = [0, 4096, 40960]
    for i, off in enumerate(hot):
        c.read(off, 4096, 100 + i)
    held = c.count()
    for round_ in range(200):
        c.read(1024, 1024, 55)              # the bootstrap read, on every operation
        if c.count() != held:
            return ("a bootstrap read changed what the cache holds (%d entries, was %d) "
                    "- a smaller read must be ignored, not stored under the larger size"
                    % (c.count(), held))
        for i, off in enumerate(hot):
            got = c.get(off, 4096)
            if got is None:
                return ("a 4096-byte entry was lost after %d bootstrap reads - the "
                        "small reads are emptying the cache" % (round_ + 1))
            if got != 100 + i:
                return "offset %d answered %s, expected %s" % (off, got, 100 + i)
    if c.get(1024, 1024) is not None:
        return "a smaller-sized read was served from a cache of larger entries"
    return None


def case_small_write_invalidates(c):
    """
    A write smaller than the entry size changes bytes an entry covers, so the entry
    has to go. Nothing writes at the bootstrap size today; this is here so that
    nothing has to keep being true.
    """
    c.new()
    c.read(0, 4096, 321)
    c.read(4096, 4096, 654)
    c.wrote(1024, 1024, 999)                # lands inside the first entry
    if c.get(0, 4096) is not None:
        return "an entry survived a smaller write into the bytes it covers"
    if c.get(4096, 4096) != 654:
        return "a smaller write threw away an entry it does not overlap"
    return None


def case_hot_block_survives(c, length):
    """
    The #155 pattern: one block touched on every operation, plus a walk of blocks
    that are each touched once. Eviction has to keep the hot one - a cache that
    lets a sequential walk push it out is safe and useless.
    """
    c.new()
    hot = 0
    c.read(hot, length, 5555)
    hot_hits = 0
    walk = 4000
    for i in range(walk):
        if c.get(hot, length) == 5555:
            hot_hits += 1
        cold = (i + 1) * length
        if c.get(cold, length) is None:
            c.read(cold, length, 6000 + i)
    if hot_hits != walk:
        return ("the hot block was evicted: %d of %d touches hit "
                "(a walk of cold blocks pushed it out)" % (hot_hits, walk))
    return None


def case_hit_rate(c, length):
    """
    What the issue actually measured. Every operation walks a handful of metadata
    blocks and then touches one block of its own, which is why 98.8% of reads were
    repeats. The rate is asserted so a change that guts the cache fails here rather
    than in a phone's battery.
    """
    c.new()
    metadata = [0, 1 * length, 10 * length, 519 * length]
    reads = hits = 0
    for op in range(500):
        c.read(1024, 1024, 7)               # the bootstrap read every operation makes
        for m in metadata:
            reads += 1
            if c.get(m, length) is not None:
                hits += 1
            else:
                c.read(m, length, 1000 + m)
        own = (10000 + op) * length
        reads += 1
        if c.get(own, length) is None:
            c.read(own, length, op)
        else:
            hits += 1
    rate = hits / reads
    if rate < 0.75:
        return "hit rate %.1f%% on the #155 pattern, expected at least 75%%" % (rate * 100)
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cachetest", default=os.path.join(os.path.dirname(__file__), "cachetest"))
    args = ap.parse_args()

    if not os.path.exists(args.cachetest):
        print("cachetest not built - run ./build.sh", file=sys.stderr)
        return 1

    c = Cache(args.cachetest)
    failures = []

    for length in (1024, 4096):
        why = case_random(c, seed=17, ops=6000, length=length, blocks=400)
        if why:
            failures.append("random traffic at %d-byte entries: %s" % (length, why))

    for name, fn in (("overwrite", lambda: case_overwrite(c, 4096)),
                     ("drop", lambda: case_drop(c, 4096)),
                     ("adopts a larger size", lambda: case_adopts_larger(c)),
                     ("mixed sizes", lambda: case_mixed_sizes(c)),
                     ("a small write invalidates", lambda: case_small_write_invalidates(c)),
                     ("hot block survives", lambda: case_hot_block_survives(c, 4096)),
                     ("hit rate", lambda: case_hit_rate(c, 4096))):
        why = fn()
        if why:
            failures.append("%s: %s" % (name, why))

    c.close()

    if failures:
        for f in failures:
            print("FAIL " + f)
        return 1
    print("the block cache never served a stale or foreign entry, forgets what it is told "
          "to, survives the two block sizes the device alternates, and keeps the hot block "
          "through a long walk")
    return 0


if __name__ == "__main__":
    sys.exit(main())
