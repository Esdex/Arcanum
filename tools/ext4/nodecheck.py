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
The harness for how an extent tree node is written: once per run of appends rather
than once per block (#162), and in what order against the inode (#164).

    ./nodecheck.py

Appending a block used to write the whole leaf every time round: find the rightmost
path, allocate, write the data, extend the last extent, write the leaf back.
Measured on a device, one block took 38647 writes in a single four-second burst -
23% of everything that session wrote, and it grew with the size of the file rather
than with the size of the tree. The path is now carried across the appends of one
call and put down once at the end.

**The measurement only works on a fragmented volume, and that is not a detail.**
A file whose blocks are contiguous has exactly one extent, that extent lives in the
four slots inside the inode, and the inode is written once at the end whatever this
code does - so the bug is invisible on an empty volume however much is appended to
it. Every case here first breaks the free space into pieces so the target's tree is
deeper than its root, and the depth is then asserted with debugfs rather than
assumed. Without that assertion a case that quietly stopped fragmenting would pass
while measuring nothing at all, which is the trap runcheck.py names, in a new place.

**What e2fsck cannot see, and what it can.**

A writer that goes back to putting the node down per block is entirely correct and
leaves a perfect filesystem, so the marginal measurement is the only thing standing
between us and all of #162 coming back. It is marginal rather than absolute so the
fixed cost of fragmenting the volume stays out of the number.

The other half - that deferring the write did not lose the last change to a node -
e2fsck cannot answer either: a tree missing its final extent is structurally sound,
it simply describes a shorter file, and a check calls that clean. So every byte is
read back through debugfs and compared with the pattern session.c wrote.

What e2fsck is exactly right about is the ordering, and it is asked on every case.
A node that reached the disk while the bitmap behind it did not is a block
referenced and free at once, which is the one damage a check cannot undo losslessly
- it hands that block to the next file that asks. Deferring only ever moves a write
later, so the direction it can break is the harmless one, a block marked and
referenced by nothing. e2fsck reports both, as bitmap differences.

  marginal   a second megabyte onto a tree deeper than its root must cost about
             what its own data costs, not twice it
  deeper     1 KiB blocks and enough fragments to push the root down, so the
             writes are spread over levels instead of landing in one leaf
  holes      free space left in small pieces, so a single append fills a node
             partway through itself and has to grow the tree while still
             holding changes it has not written
  starve     the same fragmented ground run dry, so an append fails partway
             through hanging a new chain of nodes off the tree
  regrow     grow, free the file, grow again, over free space now in awkward
             pieces - the tree is rebuilt rather than extended
  torn       the Nth write of a growing append is failed, for every N, over a
             tree deep enough that a node and the parent naming it are written
             by the same operation - the only case that can see their order.
             Two bars: everything the file held before the append is byte for
             byte intact, and `e2fsck -p` can put the volume right on its own
  readback   every byte of the fragmented file, dumped by debugfs, against the
             pattern that was written into it
"""

import argparse
import os
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from genimages import parse_extents, debugfs           # noqa: E402

IMG = "256M"
PADS = 6          # files that take blocks away from the target as it grows
ROUNDS = 6        # one block each per round, so the target ends up in fragments


def sh(*a):
    return subprocess.run(a, capture_output=True, text=True)


def pat(i):
    """The byte session.c writes at absolute file offset i."""
    return (i ^ (i >> 8) ^ (i >> 16)) & 0xFF


def fragment(block_size, rounds=ROUNDS, pads=PADS):
    """A script that leaves /f.bin holding `rounds` single-block extents.

    Each round hands one block to every padding file and one to the target, so no
    two of the target's blocks are next to each other and its extents cannot merge.
    """
    names = [f"/p{i}.bin" for i in range(pads)] + ["/f.bin"]
    lines = [f"create {n}" for n in names]
    for _ in range(rounds):
        lines += [f"append {n} {block_size}" for n in names]
    return "\n".join(lines) + "\n"


def run(mkfs, session, tmp, script, problems, label, block_size=4096, size=IMG):
    """One image, one script. Returns (image path, writes, failed_ops) or None."""
    img = os.path.join(tmp, f"n-{label.replace(' ', '_')}.img")
    scr = os.path.join(tmp, "n.txt")
    if os.path.exists(img):
        os.remove(img)
    sh("truncate", "-s", str(size), img)
    if sh(mkfs, img, "--bs", str(block_size)).returncode:
        problems.append(f"[{label}] mkfs failed")
        return None
    with open(scr, "w") as f:
        f.write(script)

    r = sh(session, img, "hold", scr)
    if r.returncode != 0:
        problems.append(f"[{label}] {r.stderr.strip() or r.stdout.strip()}")
        return None
    fields = dict(p.split("=", 1) for p in r.stdout.split() if "=" in p)

    fsck = sh("e2fsck", "-fn", img)
    if fsck.returncode != 0:
        problems.append(f"[{label}] e2fsck rejects the result (rc={fsck.returncode})\n"
                        f"           {fsck.stdout.strip()[:400]}")
    return img, int(fields["writes"]), int(fields["failed"])


def tree_depth(img, path="/f.bin"):
    rows = parse_extents(debugfs(img, f"dump_extents {path}\n"))
    return max((e["level"] for e in rows), default=0), len(
        [e for e in rows if not e["is_index"]])


def check_depth(img, label, want, problems, verbose=False):
    depth, leaves = tree_depth(img)
    if depth < want:
        problems.append(
            f"[{label}] the tree is {depth} deep with {leaves} extents, and this case "
            f"needs at least {want}: the whole tree still fits in the inode, so the "
            f"node write being measured never happens and the case proves nothing")
        return False
    if verbose:
        print(f"  {label:18s} depth {depth}, {leaves} extents")
    return True


def readback(img, problems, label="readback"):
    """Every byte of /f.bin, through debugfs, against the pattern written."""
    out = img + ".dump"
    debugfs(img, f"dump /f.bin {out}\n")
    if not os.path.exists(out):
        problems.append(f"[{label}] debugfs could not dump the file")
        return
    with open(out, "rb") as fh:
        data = fh.read()
    if not data:
        problems.append(f"[{label}] the file dumped empty")
        return
    want = bytes(pat(i) for i in range(len(data)))
    if data != want:
        first = next(i for i in range(len(data)) if data[i] != want[i])
        problems.append(
            f"[{label}] the file reads back wrong from offset {first} of {len(data)} "
            f"({data[first]:#04x}, expected {want[first]:#04x}) - a change to a node "
            f"never reached the disk, and e2fsck cannot see that")


def full_node_base(mkfs, session, tmp, problems, want_level=2,
                   block_size=1024, size="64M"):
    """An image whose /f.bin ends in a full node two levels below the root.

    Two levels matters. When the node that fills is the root's own child, the
    parent is the root, the root rides inside the inode and goes to disk last
    whatever happens - one node is written and there is no order to get wrong.
    Deeper down, the new node and the parent naming it are both written by the same
    append, and which of them lands first is the whole question.

    Built by hand rather than by a script, because the moment wanted is exactly the
    round the node fills: a round early and the append does not grow the tree, a
    round late and it grew during the setup instead.
    """
    capacity = (block_size - 12 - 4) // 12
    img = os.path.join(tmp, f"torn-base-{want_level}.img")
    scr = os.path.join(tmp, "torn.txt")
    if os.path.exists(img):
        os.remove(img)
    sh("truncate", "-s", size, img)
    if sh(mkfs, img, "--bs", str(block_size)).returncode:
        problems.append("[torn] mkfs failed")
        return None

    def script(text):
        with open(scr, "w") as f:
            f.write(text)
        return sh(session, img, "hold", scr)

    round_ = "append /p0.bin 1024\nappend /f.bin 1024\n"
    # A bulk to get near the wanted depth, then one round at a time to stop exactly
    # on it. The bulk has to undershoot: the tree only ever gets deeper, so
    # overshooting cannot be walked back. A depth-1 tree with a full node is about
    # 84 rounds away, a depth-2 one about 420.
    bulk = 40 if want_level == 1 else 400
    script("create /p0.bin\ncreate /f.bin\n" + round_ * bulk)
    for _ in range(300):
        rows = parse_extents(debugfs(img, "dump_extents /f.bin\n"))
        # The tree's depth, not the last row's level. `>= want_level` on one row is
        # true of a deeper tree as well, so it stopped both shapes at the same place
        # and gave two names to one case.
        depth = max((e["level"] for e in rows), default=0) if rows else 0
        if rows and depth == want_level and rows[-1]["entries"] == capacity:
            # One more block to the padding file, so the block after the target's
            # last one is taken. Without this the next append is CONTIGUOUS, it
            # lengthens the last extent instead of needing a slot, and a full node
            # is not full enough to make the tree grow - the case then runs and
            # asks nothing, which is how it was written the first time.
            script("append /p0.bin 1024\n")
            if sh("e2fsck", "-fn", img).returncode != 0:
                problems.append("[torn] the base image is not e2fsck-clean")
                return None
            return img
        script(round_)
    problems.append(f"[torn L{want_level}] could not build a file whose node is "
                    f"exactly full at level {want_level}, so the order of the node "
                    f"writes was never asked about")
    return None


def torn(mkfs, session, tmp, problems, want_level=2, verbose=False):
    """Fails the Nth write of an append that has to grow the tree, for every N.

    `want_level` picks which node fills, and the two are different operations. At 1
    the full node hangs directly off the root, so growing gives the root a new entry
    and the root - which lives inside the inode - is committed by a write of its own
    after the node it names. At 2 the growth happens below a node with a block of its
    own and the root never moves. Only the first shape can show a root committed ahead
    of its tree; only the second has two block-owning levels to put in an order.

    The bar is stronger than the one every other case here uses, and deliberately.
    e2fsck repairs almost anything and calls the volume clean afterwards, so
    "repairable" alone cannot tell a safe order from an unsafe one. What separates
    them is the price of the repair, and the price is paid in bytes that were on the
    volume before the operation started.

    So what is required is exactly that: **everything the file held before the append
    is byte for byte intact afterwards**. The tail is not required to be anything.

    An interrupted append can leave the file longer than what actually reached the
    disk, ending in a hole that reads as zeros, and that is accepted here on purpose.
    It is the price of the ordering #164 settled on, and the two directions are not
    symmetrical: an i_size ahead of the extents is a legal sparse file and e2fsck
    leaves it alone, while extents ahead of i_size are an error whose repair clears
    the whole extent - and an append lengthens an extent that covers blocks the file
    already had. One of those directions has to be chosen, and only one of them is
    survivable.
    """
    base = full_node_base(mkfs, session, tmp, problems, want_level=want_level)
    if base is None:
        return
    tag = f"torn L{want_level}"
    before = os.path.join(tmp, "torn-before.bin")
    debugfs(base, f"dump /f.bin {before}\n")
    base_len = os.path.getsize(before) if os.path.exists(before) else 0

    img = os.path.join(tmp, f"torn-{want_level}.img")
    scr = os.path.join(tmp, "torn-run.txt")

    def attempt(fail_at):
        shutil.copyfile(base, img)
        with open(scr, "w") as f:
            if fail_at:
                f.write(f"failwrite {fail_at}\n")
            f.write("append /f.bin 4096\n")
        r = sh(session, img, "hold", scr)
        fields = dict(p.split("=", 1) for p in r.stdout.split() if "=" in p)
        return int(fields.get("writes", 0))

    def node_blocks(image):
        rows = parse_extents(debugfs(image, "dump_extents /f.bin\n"))
        return sum(1 for e in rows if e["is_index"])

    def root_entries(image):
        """How many entries the root holds. debugfs numbers levels from the root,
        so its own entries are the level 0 rows - and the root lives inside the
        inode, which is what makes it the one part of the tree written separately
        from the rest."""
        rows = parse_extents(debugfs(image, "dump_extents /f.bin\n"))
        return sum(1 for e in rows if e["level"] == 0)

    writes = attempt(0)
    if writes < 4:
        problems.append(f"[{tag}] the growing append made only {writes} writes, so "
                        f"there is nothing to interrupt")
        return
    # The append must actually GROW the tree, not merely lengthen an extent. If it
    # does not, path_flush has one level to write, there is no parent and child to
    # put in an order, and every fault point below asks nothing at all.
    if node_blocks(img) <= node_blocks(base):
        problems.append(f"[{tag}] the append did not hang a new node off the tree, so "
                        "no parent and child are written by the same operation and "
                        "the order this case exists to check was never exercised")
        return
    # At level 1 the new node hangs off the root, so the root must have gained an
    # entry. Without that this case is the level 2 one over again: the root never
    # moves, the write that commits it never happens, and a root committed ahead of
    # the tree it names cannot be shown. The level 2 case was written that way by
    # mistake and passed while asking nothing, which is why this is checked.
    moved = root_entries(img) - root_entries(base)
    if want_level == 1 and moved <= 0:
        problems.append(f"[{tag}] the root still holds what it held before, so the "
                        f"grow went somewhere below it and this case cannot see a "
                        f"root written ahead of its tree")
        return

    lost = 0
    unattended = 0
    for n in range(1, writes + 1):
        attempt(n)
        # Before the repair that answers yes to everything, the one an unattended
        # check would make. `e2fsck -p` fixes what it considers safe and exits
        # above 1 the moment it meets something it will not decide alone. That is
        # e2fsprogs drawing the line rather than this stand grading its wording,
        # and it is the line between the two orders a node can be written in: a
        # leak is reclaimed without being asked, an index naming a block that was
        # never written is not.
        preen = sh("e2fsck", "-fp", img)
        if preen.returncode > 1:
            unattended += 1
            problems.append(f"[{tag} {n}] an unattended check will not repair what "
                            f"this leaves - e2fsck -p exits {preen.returncode}: "
                            f"{(preen.stdout + preen.stderr).strip()[:160]}")
        if sh("e2fsck", "-fy", img).returncode not in (0, 1, 2):
            problems.append(f"[{tag} {n}] e2fsck could not repair the residual")
            continue
        rc = sh("e2fsck", "-fn", img)
        if rc.returncode != 0:
            problems.append(f"[{tag} {n}] the repair did not settle: "
                            f"{rc.stdout.strip()[:300]}")
            continue
        out = os.path.join(tmp, "torn-after.bin")
        if os.path.exists(out):
            os.remove(out)
        debugfs(img, f"dump /f.bin {out}\n")
        if not os.path.exists(out) or os.path.getsize(out) == 0:
            problems.append(f"[{tag} {n}] the file did not survive the repair - "
                            f"whatever this residual is, e2fsck settled it by taking "
                            f"the file, which is the one price no interrupted append "
                            f"is allowed to cost")
            lost += 1
            continue
        with open(out, "rb") as fh:
            data = fh.read()
        if len(data) < base_len:
            problems.append(f"[{tag} {n}] the file came back {len(data)} bytes, shorter "
                            f"than the {base_len} it had before the append - the repair "
                            f"took more than the interrupted tail")
            continue
        want = bytes(pat(i) for i in range(base_len))
        if data[:base_len] != want:
            first = next(i for i in range(base_len) if data[i] != want[i])
            problems.append(f"[{tag} {n}] byte {first} of what the file already held "
                            f"reads back as {data[first]:#04x} instead of "
                            f"{want[first]:#04x} - the repair took something that was "
                            f"on the volume before the append started")
    if verbose:
        print(f"  {tag:18s} {writes} fault points, {lost} lost the file, "
              f"{unattended} beyond an unattended check")


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
    for t in ("e2fsck", "debugfs"):
        if not shutil.which(t):
            sys.exit(f"{t} not found - it is an oracle here")

    problems = []
    with tempfile.TemporaryDirectory() as tmp:
        # ── the marginal cost of a megabyte onto a tree with a leaf of its own ──
        def deep_append(megs, bs=4096):
            return fragment(bs) + f"append /f.bin 1048576\n" * megs

        one = run(args.mkfs, args.session, tmp, deep_append(1), problems, "1 MiB")
        two = run(args.mkfs, args.session, tmp, deep_append(2), problems, "2 MiB")
        if one and two:
            measured = check_depth(one[0], "marginal", 1, problems, args.verbose)
            blocks = 1048576 // 4096
            marginal = two[1] - one[1]
            budget = int(blocks * 1.15)
            if measured and marginal > budget:
                problems.append(
                    f"[marginal] the second megabyte cost {marginal} block writes for "
                    f"{blocks} blocks of data - over the {budget} allowed, so the "
                    f"extent node is being written per block again, which is all "
                    f"of #162")
            elif args.verbose:
                print(f"  second megabyte    {marginal} writes for {blocks} data blocks")
            readback(two[0], problems)

        # ── a tree the root has been pushed out of ─────────────────────────────
        # At 1 KiB a leaf holds 84 extents, so four full leaves fill the root's four
        # slots and the next one pushes the root down. That is a different shape to
        # measure: the writes are spread over three levels rather than landing in one
        # leaf, and the levels above the leaf are only touched when the tree grows.
        deep = fragment(1024, rounds=360) + "append /f.bin 1048576\n"
        got = run(args.mkfs, args.session, tmp, deep, problems, "deeper",
                  block_size=1024)
        if got:
            check_depth(got[0], "deeper", 2, problems, args.verbose)
            readback(got[0], problems, "deeper readback")

        # ── free space in holes, so one call fills a node partway through ──────
        # Every case above appends onto a node that was already there. None of them
        # fills one DURING a call, and that is the only moment when the buffer a
        # node is held in gets reused while it still holds changes - which is what
        # makes it the only moment that can lose them.
        #
        # Reaching it needs free space in small pieces rather than one run, so this
        # lays down a row of small files and takes every other one away. At 1 KiB a
        # node holds 84 extents, and an append that has to pick up a hundred holes
        # runs out of room in the middle of itself.
        holes = ("".join(f"create /h{i}.bin\nappend /h{i}.bin 2048\n"
                         for i in range(200))
                 + "".join(f"unlink /h{i}.bin\n" for i in range(1, 200, 2))
                 + "create /f.bin\nappend /f.bin 204800\n")
        got = run(args.mkfs, args.session, tmp, holes, problems, "holes",
                  block_size=1024)
        if got:
            depth, leaves = tree_depth(got[0])
            if leaves < 85:
                problems.append(
                    f"[holes] the file came out in only {leaves} extents, so the node "
                    f"never filled inside a single append and the case did not reach "
                    f"the moment it exists for")
            elif args.verbose:
                print(f"  holes              depth {depth}, {leaves} extents in "
                      f"one append")
            readback(got[0], problems, "holes readback")

        # ── to the very end of a fragmented volume ─────────────────────────────
        # Growing the tree is where an append can run out of space in the middle of
        # something: a chain of new nodes is taken a level at a time, and the second
        # can fail after the first has been linked into its parent in memory. What
        # must not happen then is that link reaching the disk, because it names a
        # block that was handed straight back - a block referenced and free at once,
        # which is the one state a check cannot undo without taking something away.
        # e2fsck is the oracle for exactly that, and running the volume dry on
        # ground that is already in pieces is what gets there.
        starve = fragment(4096, rounds=40) + "append /f.bin 1048576\n" * 80
        got = run(args.mkfs, args.session, tmp, starve, problems, "fill to refusal",
                  size="32M")
        if got:
            if got[2] == 0:
                problems.append("[fill to refusal] nothing was refused - the volume "
                                "did not fill, so this case checked nothing")
            elif args.verbose:
                print(f"  fill to refusal    {got[1]} writes, {got[2]} refused")

        # ── grown, freed and grown again ───────────────────────────────────────
        regrow = (fragment(4096) + "append /f.bin 1048576\n" * 2
                  + "unlink /f.bin\ncreate /f.bin\n"
                  + "append /f.bin 1048576\n" * 2)
        got = run(args.mkfs, args.session, tmp, regrow, problems, "regrow")
        if got and args.verbose:
            print(f"  regrow             {got[1]} writes, {got[2]} operations refused")

        # ── interrupted partway through growing the tree ───────────────────────
        # Twice, because the two shapes commit different things. At level 2 the new
        # node hangs off a node with a block of its own, so the root does not move
        # and the tree is the only thing written after the size. At level 1 it hangs
        # off the root itself, which lives in the inode and therefore goes down in a
        # write of its own, after the node it names. Only the second shape can show
        # a root committed ahead of its tree, and the first was written without it.
        torn(args.mkfs, args.session, tmp, problems, want_level=1,
             verbose=args.verbose)
        torn(args.mkfs, args.session, tmp, problems, want_level=2,
             verbose=args.verbose)


    if problems:
        print("FAIL")
        for p in problems:
            print(f"     {p}")
        return 1
    print("an extent tree node is put down once per run of appends rather than once "
          "per block: a megabyte onto a fragmented file costs about its own data in "
          "writes, the tree still reads back byte for byte, and e2fsck is clean on "
          "every shape including one deep enough to have pushed the root down. "
          "Interrupted at any single write, in either shape the tree can grow in, "
          "the append leaves everything the file already held byte for byte intact "
          "and a volume an unattended e2fsck -p puts right on its own.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
