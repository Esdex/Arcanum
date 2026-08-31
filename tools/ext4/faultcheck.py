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
Proves what a write failing in the middle of an operation leaves behind.

    ./faultcheck.py
    ./faultcheck.py --report      # also list the residuals each operation leaves

ext4 vaults carry no journal, on purpose (see #7): there is nothing to replay, so
an operation stopped part way leaves whatever the last completed write put there.
Every operation here is ordered so that those moments are ones the filesystem can
be left in - an inode is written before anything names it, a name is removed
before the inode it named is freed, the counters go last. faultop runs one
operation with the Nth block write forced to fail and every other write, including
any the code makes in response, allowed through. This sweeps N across every write
of every operation.

The bar each fault point has to clear, and why it is this one:

  repairable  `e2fsck -fy` on the residual, then `e2fsck -fn`, which must come
              back completely clean. Not "e2fsck prints only lines from a list we
              wrote down" - that was the old bar, and it was the wrong shape. It
              had to be widened every time a new operation produced a new wording,
              and widening it is indistinguishable from excusing a real fault.
  lossless    everything on the volume before the operation is still there
              afterwards, byte for byte, except what the operation was allowed to
              touch. Taken with `debugfs rdump`, so the census is e2fsprogs's view
              and not ours. This is the half a string list cannot express: e2fsck
              is good enough to make almost anything "clean" - it will clone a
              multiply-claimed block or delete a dangling entry and report success
              - so what has to be measured is what the repair cost, not whether it
              finished.

Between them these say the thing a user cares about: if the phone dies mid-write,
a check puts the vault back and takes nothing else with it. What is not promised
is the operation itself surviving - a file being created when the power goes may
or may not be there.

--report prints, per operation, the distinct e2fsck findings the sweep produced,
with the numbers taken out. It is evidence rather than a check: it is how the
guarantee written for users was worked out, and how a change in what an operation
leaves behind gets noticed.
"""

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from genimages import parse_extents, debugfs           # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
MKFS = os.path.join(HERE, "mkfs")
DIRWRITE = os.path.join(HERE, "dirwrite")
EXTWRITE = os.path.join(HERE, "extwrite")
FAULTOP = os.path.join(HERE, "faultop")
WHEN = "1784639915"

# Lines that carry no finding: banners, pass headers, summaries. Only --report
# uses this, and only to keep its output readable - nothing passes or fails on it.
NOISE = re.compile(
    r"^e2fsck |"
    r"^Pass [1-5]|"
    r": clean, |"
    r": \d+/\d+ files .*, \d+/\d+ blocks|"
    r"FILE SYSTEM WAS MODIFIED|"
    r"^IGNORED\.|"
    r"WARNING: Filesystem still has errors"
)


def sh(*a):
    return subprocess.run(a, capture_output=True, text=True)


def fsck_clean(img):
    return sh("e2fsck", "-fn", img).returncode == 0


def run_faultop(img, fail_at, *op):
    r = sh(FAULTOP, img, str(fail_at), *op)
    m = re.search(r"writes=(\d+)", r.stdout)
    return (int(m.group(1)) if m else -1), r.stdout.strip()


def ino_of(img, path):
    out = sh("debugfs", "-R", f'stat "{path}"', img).stdout
    m = re.search(r"Inode:\s*(\d+)", out)
    return int(m.group(1)) if m else None


def file_sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def is_clean_flag(img):
    """Whether s_state says the volume was put down tidily, read with dumpe2fs.

    Read from e2fsprogs rather than from the two bytes, so that what is checked is
    what another driver concludes and not what we think we wrote.
    """
    for ln in sh("dumpe2fs", "-h", img).stdout.splitlines():
        if ln.startswith("Filesystem state:"):
            # Compared whole, not searched: "not clean" contains "clean", and a
            # substring test here passed every image for an afternoon.
            return ln.split(":", 1)[1].strip() == "clean"
    return None


def census(img, tmp):
    """-> {path: sha256 or "dir"} for everything on the volume, via debugfs.

    e2fsprogs walks the filesystem and writes it out; we only hash what lands.
    Our own reader is deliberately not involved - a census taken with the code
    under test would agree with that code by construction.

    /lost+found's contents are left out: reconnecting an orphan into it is a
    repair doing its job, not something the volume lost.
    """
    out = os.path.join(tmp, "census")
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    sh("debugfs", "-R", f"rdump / {out}", img)

    seen = {}
    for dirpath, dirnames, filenames in os.walk(out):
        rel = os.path.relpath(dirpath, out)
        if rel == "lost+found" or rel.startswith("lost+found" + os.sep):
            dirnames[:] = []
            continue
        for d in dirnames:
            seen[os.path.normpath(os.path.join(rel, d))] = "dir"
        for f in filenames:
            p = os.path.join(dirpath, f)
            try:
                with open(p, "rb") as fh:
                    seen[os.path.normpath(os.path.join(rel, f))] = \
                        hashlib.sha256(fh.read()).hexdigest()
            except OSError as e:
                seen[os.path.normpath(os.path.join(rel, f))] = f"unreadable: {e}"
    return seen


def residual_lines(img):
    """Every e2fsck finding, with the numbers taken out so a sweep of two hundred
    fault points groups into the handful of distinct residuals it really is."""
    r = sh("e2fsck", "-fn", img)
    return {re.sub(r"\d+", "#", ln.strip())
            for ln in (r.stdout + r.stderr).splitlines()
            if ln.strip() and not NOISE.search(ln)}


def repairs_losslessly(img, before, tmp, may_change):
    """-> (ok, why not). Repairs the image, then asks what the repair cost."""
    sh("e2fsck", "-fy", img)
    if not fsck_clean(img):
        return False, "e2fsck could not repair it - a second pass still finds faults"

    after = census(img, tmp)
    gone, changed = [], []
    for path, mark in before.items():
        if path in may_change:
            continue
        if path not in after:
            gone.append(path)
        elif after[path] != mark:
            changed.append(path)
    if gone or changed:
        return False, (f"the repair cost something it was not allowed to: "
                       f"lost {gone[:4]}, changed {changed[:4]}")
    return True, ""


def fault_points(writes, max_points):
    """Which values of N to fault. Everything, unless there are too many.

    An operation that writes two hundred blocks costs an e2fsck per point, so past
    a limit this takes the first eight, the last eight and an even spread between.
    The ends are where the states differ: at the start the operation has barely
    begun, at the end it is all but committed, and the middle is the same shape
    repeated. Which points were taken is printed, so a sampled sweep is never
    mistaken for a complete one.
    """
    if max_points is None or writes <= max_points:
        return list(range(1, writes + 1)), False
    edge = 8
    pts = set(range(1, edge + 1)) | set(range(writes - edge + 1, writes + 1))
    middle = max_points - len(pts)
    if middle > 0:
        step = (writes - 2 * edge) / (middle + 1)
        pts |= {int(edge + step * (i + 1)) for i in range(middle)}
    return sorted(p for p in pts if 1 <= p <= writes), True


def sweep(base_img, label, op, problems, may_change=(), report=None,
          max_points=None):
    """Fault every write of `op` in turn and judge what is left.

    `may_change` names the paths the operation itself is entitled to add, remove
    or rewrite. Everything else on the volume is required back untouched.
    """
    may_change = set(may_change)
    with tempfile.TemporaryDirectory() as tmp:
        img0 = os.path.join(tmp, "n.img")
        shutil.copy(base_img, img0)
        writes, out = run_faultop(img0, 0, *op)
        if writes <= 0 or "rc=0" not in out:
            problems.append(f"{label}: the unfaulted operation did not succeed ({out})")
            return
        if not fsck_clean(img0):
            problems.append(f"{label}: the unfaulted operation is not e2fsck-clean")
            return
        if is_clean_flag(img0) is not True:
            problems.append(f"{label}: the volume is not marked clean after an "
                            f"operation that finished")
            return

        before = census(base_img, tmp)
        base_sha = file_sha(base_img)
        points, sampled = fault_points(writes, max_points)
        bad = clean = 0
        for n in points:
            img = os.path.join(tmp, f"f{n}.img")
            shutil.copy(base_img, img)
            run_faultop(img, n, *op)
            if report is not None:
                report.setdefault(label, set()).update(residual_lines(img))
            if fsck_clean(img):
                clean += 1
            # An operation that did not finish must either say so or have changed
            # nothing at all. The second half is not a let-off: the first write of
            # every operation is the mark itself, and a fault on that one leaves
            # the volume exactly as it was found, which is the one case where
            # still claiming to be clean is the truth.
            #
            # This is the half of #142 that nothing else here would notice: a
            # residual e2fsck repairs is only harmless if something says a repair
            # is due.
            if is_clean_flag(img) is not False and file_sha(img) != base_sha:
                bad += 1
                if bad <= 3:
                    problems.append(f"{label}: faulting write {n} of {writes} left "
                                    f"the volume marked clean after changing it")
                continue
            ok, why = repairs_losslessly(img, before, tmp, may_change)
            if not ok:
                bad += 1
                if bad <= 3:
                    problems.append(f"{label}: faulting write {n} of {writes}: {why}")
        if bad == 0:
            how = (f"{len(points)} of {writes} writes sampled" if sampled
                   else f"{writes} fault points")
            print(f"{label}: {how}, {clean} left nothing to repair, "
                  f"{len(points) - clean} repaired losslessly")


def fresh(tmp, name, megs=16):
    """An empty volume our own formatter made."""
    img = os.path.join(tmp, name)
    subprocess.run(["truncate", "-s", f"{megs}M", img], check=True)
    sh(MKFS, img)
    return img


def make_file(img, parent, name, blocks):
    """-> the inode of a new file carrying `blocks` blocks of content."""
    out = sh(DIRWRITE, img, str(parent), "create", name, WHEN).stdout.strip()
    ino = int(out) if out.isdigit() else ino_of(img, f"/{name}")
    if blocks:
        sh(EXTWRITE, img, str(ino), "append", str(blocks))
    return ino


def block_size_of(img):
    """The filesystem's block size, read straight out of the superblock."""
    with open(img, "rb") as fh:
        fh.seek(1024 + 24)
        return 1024 << int.from_bytes(fh.read(4), "little")


def filled_leaf_file(img, name, block_size, pads=1, want_level=2, limit=600):
    """-> the inode of a file whose rightmost extent node is full, under a node of
    its own.

    One block to each padding file and then one to the target, round after round, so
    no two of the target's blocks are adjacent and their extents cannot merge. Four
    separate extents fill the four slots inside the inode and the fifth pushes the
    root down; four full nodes below that fill the root again and push it down once
    more. The target is the moment a node two levels below the root is exactly full,
    because the very next block appended has to hang a new node off its parent - and
    the parent is then a node with a block of its own, not the root.

    That distinction is the whole point of the setup. When the parent is the root it
    rides in the inode and goes to disk last whatever happens, so only one node is
    ever written and there is no order to get wrong. Two levels down, the new node
    and the parent naming it are both written by the same operation, and the sweep
    can ask which of them reaches the disk first. Stopping a level short gives a case
    that runs and asks nothing.
    """
    capacity = (block_size - 12 - 4) // 12
    pad_inos = [make_file(img, 2, f"pad{i}.txt", 0) for i in range(pads)]
    ino = make_file(img, 2, name, 0)
    for _ in range(limit):
        rows = parse_extents(debugfs(img, f"dump_extents <{ino}>\n"))
        if rows and rows[-1]["level"] >= want_level and rows[-1]["entries"] == capacity:
            return ino if fsck_clean(img) else None
        for pi in pad_inos:
            sh(EXTWRITE, img, str(pi), "append", "1")
        sh(EXTWRITE, img, str(ino), "append", "1")
    return None


def indexed_image(tmp, name, files, megs, inodes):
    """A volume mke2fs made, with one directory e2fsck has since indexed.

    The rebuild an indexed directory gets on its first write (#141) rewrites the
    whole directory in place, which is the largest thing this layer does between
    two consistent states, and the only one whose interrupted state was argued for
    in the design rather than fallen out of it. Kept small on purpose - the sweep
    costs one e2fsck per block the rebuild writes.
    """
    img = os.path.join(tmp, name + ".img")
    seed = os.path.join(tmp, name + "-seed")
    os.makedirs(os.path.join(seed, "many"), exist_ok=True)
    for i in range(files):
        with open(os.path.join(seed, "many", f"file-{i:04d}"), "w") as f:
            f.write("x")
    subprocess.run(["truncate", "-s", f"{megs}M", img], check=True)
    if sh("mke2fs", "-q", "-t", "ext4", "-b", "1024", "-I", "256",
          "-N", str(inodes), "-d", seed, img).returncode != 0:
        return None, None, None
    sh("e2fsck", "-fyD", img)
    ino = ino_of(img, "/many")
    if ino is None or "0x81000" not in sh("debugfs", "-R", f"stat <{ino}>", img).stdout:
        return None, None, None
    for ln in sh("debugfs", "-R", f"ls -l <{ino}>", img).stdout.splitlines():
        m = re.match(r"\s*(\d+)\s+(\d+)", ln)
        if m and m.group(2).startswith("100"):
            return img, ino, int(m.group(1))
    return None, None, None


def main():
    global FAULTOP, MKFS, DIRWRITE, EXTWRITE
    ap = argparse.ArgumentParser()
    ap.add_argument("--report", action="store_true",
                    help="also list, per operation, every distinct residual a "
                         "fault left - evidence, not a check")
    ap.add_argument("--only", help="run one sweep by label prefix")
    # So mutants-fault.sh can point this at binaries built from staged sources.
    ap.add_argument("--faultop", default=FAULTOP)
    ap.add_argument("--mkfs", default=MKFS)
    ap.add_argument("--dirwrite", default=DIRWRITE)
    ap.add_argument("--extwrite", default=EXTWRITE)
    args = ap.parse_args()
    FAULTOP, MKFS = args.faultop, args.mkfs
    DIRWRITE, EXTWRITE = args.dirwrite, args.extwrite

    for t in (MKFS, DIRWRITE, EXTWRITE, FAULTOP):
        if not os.path.exists(t):
            sys.exit(f"{t} not found - build it first")

    problems = []
    report = {} if args.report else None

    def go(base, label, op, may_change=(), max_points=None):
        if args.only and not label.startswith(args.only):
            return
        sweep(base, label, op, problems, may_change=may_change, report=report,
              max_points=max_points)

    with tempfile.TemporaryDirectory() as tmp:
        # Making and unmaking a name. The orderings that decide whether a fault
        # leaves an orphan (repairable) or a dangling entry (not).
        cr = fresh(tmp, "create.img")
        go(cr, "create /new.txt", ("create", "2", "new.txt"), {"new.txt"})

        mk = fresh(tmp, "mkdir.img")
        go(mk, "mkdir /d", ("mkdir", "2", "d"), {"d"})

        un = fresh(tmp, "unlink.img")
        make_file(un, 2, "gone.txt", 12)
        go(un, "unlink /gone.txt", ("unlink", "2", "gone.txt"), {"gone.txt"})

        rd = fresh(tmp, "rmdir.img")
        sh(DIRWRITE, rd, "2", "mkdir", "empty", WHEN)
        go(rd, "rmdir /empty", ("rmdir", "2", "empty"), {"empty"})

        rn = fresh(tmp, "rename.img")
        sh(DIRWRITE, rn, "2", "mkdir", "a", WHEN)
        sh(DIRWRITE, rn, "2", "mkdir", "b", WHEN)
        a, b = ino_of(rn, "/a"), ino_of(rn, "/b")
        sh(DIRWRITE, rn, str(a), "create", "f.txt", WHEN)
        if a is None or b is None or not fsck_clean(rn):
            problems.append("rename base setup is not e2fsck-clean")
        else:
            go(rn, "rename /a/f.txt -> /b/f.txt",
               ("rename", str(a), "f.txt", str(b), "f.txt"),
               {os.path.join("a", "f.txt"), os.path.join("b", "f.txt")})

        # The extent writer. Blocks are taken from the allocator before anything
        # references them and given back only after nothing does, so a fault leaks
        # rather than hands the same block to two files.
        apimg = fresh(tmp, "append.img")
        ai = make_file(apimg, 2, "grow.txt", 4)
        go(apimg, "append 8 blocks", ("append", str(ai), "8"), {"grow.txt"})

        # The same writer over a tree that has a leaf of its own. The case above
        # never leaves the inode - four contiguous blocks are one extent in the
        # root - so no fault in it can fall between a node and the parent that
        # names it. Since the node is now written once at the end of a run rather
        # than after every block (#162), where that single write falls in the
        # order is the whole question, and this is the only case that asks it.
        dpimg = fresh(tmp, "deeptree.img")
        di = filled_leaf_file(dpimg, "deep.txt", block_size_of(dpimg))
        if di is None:
            problems.append("could not set up a file whose extent node is exactly "
                            "full, so the growing append was never swept")
        else:
            go(dpimg, "append 4 blocks onto a full extent node",
               ("append", str(di), "4"), {"deep.txt"})

        tr = fresh(tmp, "truncate.img")
        ti = make_file(tr, 2, "cut.txt", 24)
        go(tr, "truncate to 8 blocks", ("truncate", str(ti), "8"), {"cut.txt"})

        # Inside the file's last block, which is the only range set_size accepts.
        ss = fresh(tmp, "setsize.img")
        si = make_file(ss, 2, "size.txt", 4)
        go(ss, "set_size to 3500 bytes", ("setsize", str(si), "3500"), {"size.txt"})

        # Straddles the end on purpose: some bytes land in blocks the file has,
        # the rest have to be appended, so one call exercises both halves.
        wa = fresh(tmp, "writeat.img")
        wi = make_file(wa, 2, "spliced.txt", 6)
        go(wa, "write_at across the end", ("writeat", str(wi), "5000", "4000"),
           {"spliced.txt"})

        # The directory rebuild (#141). Every name in the directory may move to a
        # different block, but not one of them may be lost.
        # Two of them. The shallow one is swept whole; the deep one has interior
        # index nodes, which are the blocks a half-finished rebuild leaves behind
        # that no linear reader can parse, so it is the case worth having even at
        # 185 writes - sampled rather than swept.
        for name, files, megs, inodes, cap in (("htree", 120, 16, 600, None),
                                               ("htree-deep", 9000, 64, 12000, 20)):
            hi, hino, htarget = indexed_image(tmp, name, files, megs, inodes)
            if hi is None:
                problems.append(f"could not build the {name} indexed directory")
                continue
            go(hi, f"add into an indexed directory ({name})",
               ("add", str(hino), str(htarget), "faulted-entry"),
               {os.path.join("many", "faulted-entry")}, max_points=cap)

    if report is not None:
        print("\nresiduals seen, by operation:")
        for label in sorted(report):
            print(f"\n  {label}")
            for ln in sorted(report[label]) or ["(nothing - every fault left a clean image)"]:
                print(f"    {ln}")

    if problems:
        print("\nFAIL")
        for p in problems:
            print(f"     {p}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
