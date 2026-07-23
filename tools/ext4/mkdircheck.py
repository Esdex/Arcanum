#!/usr/bin/env python3
r"""
The harness for making and removing directories.

    ./mkdircheck.py --cases /tmp/cases
    ./mkdircheck.py --cases /tmp/cases --depth 4

A directory is not a file with a different mode bit, and the difference is three
things that creating a file has no equivalent of. Each is invisible to a listing
and each is something e2fsck works out for itself and compares:

  the first block   a directory holds "." and ".." from the moment it exists. One
                    with a blank first block is not empty, it is corrupt - the
                    chain of rec_len has nowhere to start.
  the parent's link the new ".." is a second name for the parent, so the parent's
                    link count goes up. Nothing about adding the entry implies it.
  bg_used_dirs_count  the group descriptor counts directories separately, and
                    nothing else in this library moves that counter.

So the check that matters here is not "does the name appear" - it does, whichever
of those three is missing - but that e2fsck stays completely clean, and that the
counts come back exactly when the directory is removed again.

Nesting is checked to a configurable depth because the parent link is the failure
that only shows at depth: a mkdir that forgets it leaves the *parent* wrong, not
the new directory, so one level looks fine from the inside.

Removing is checked for what it refuses as much as what it does. A populated
directory must not go: everything inside it is reachable only through it, so
removing one strands every inode below with no name left to find it by, and e2fsck
reports them as unattached long after the operation that did it.
"""

import argparse
import glob
import os
import shutil
import struct
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from appendcheck import fsck, line_delta, BENIGN_REMARK           # noqa: E402
from dircheck import our_listing, dir_csum_ok, find_directories   # noqa: E402
from interopcheck import mount_fuse, unmount_fuse                 # noqa: E402

WHEN = 1784639915
EXT4_FT_DIR = 2
S_IFMT = 0xF000
S_IFDIR = 0x4000


def run(tool, *args):
    r = subprocess.run([tool, *[str(a) for a in args]], capture_output=True, text=True)
    return r.returncode, r.stdout.strip(), r.stderr.strip()


def read_inode(img, ino):
    """The raw on-disk inode, found the long way round so the harness does not
    depend on the code it is checking to locate it."""
    with open(img, "rb") as f:
        f.seek(1024)
        sb = f.read(1024)
        u16 = lambda b, o: struct.unpack_from("<H", b, o)[0]
        u32 = lambda b, o: struct.unpack_from("<I", b, o)[0]
        bs = 1024 << u32(sb, 0x18)
        ipg, isize, first = u32(sb, 0x28), u16(sb, 0x58), u32(sb, 0x14)
        dsz = u16(sb, 0xFE) if (u32(sb, 0x60) & 0x80) else 32
        g, idx = (ino - 1) // ipg, (ino - 1) % ipg
        f.seek((first + 1) * bs + g * dsz)
        d = f.read(dsz)
        itable = u32(d, 8) | (u32(d, 40) << 32 if dsz >= 64 else 0)
        f.seek(itable * bs + idx * isize)
        return f.read(isize)


def inode_links(img, ino):
    return struct.unpack_from("<H", read_inode(img, ino), 0x1A)[0]


def inode_is_dir(img, ino):
    return (struct.unpack_from("<H", read_inode(img, ino), 0x00)[0] & S_IFMT) == S_IFDIR


def fsck_same(base_rc, base_lines, img, problems, when):
    rc, lines, _ = fsck(img)
    if rc != base_rc:
        problems.append(f"fsck rc changed {when}: {base_rc} -> {rc}")
    new, gone = line_delta(base_lines, lines)
    for line in [x for x in new if not BENIGN_REMARK.match(x)]:
        problems.append(f"fsck complains {when}: {line}")
    for line in [x for x in gone if not BENIGN_REMARK.match(x)]:
        problems.append(f"fsck stopped saying, {when}: {line}")


def check_dir(img, dir_ino, tools, count, depth):
    bench, dirwrite = tools
    problems = []

    base_rc, base_lines, _ = fsck(img)
    if base_rc != 0:
        return [f"pristine image is not fsck-clean (rc={base_rc})"]
    before = our_listing(bench, img, dir_ino)
    if before is None:
        return ["could not list the directory"]
    links_before = inode_links(img, dir_ino)

    made = []
    for i in range(count):
        name = f"made-dir-{i:03d}"
        rc, out, err = run(dirwrite, img, dir_ino, "mkdir", name, WHEN)
        if rc != 0:
            problems.append(f"mkdir {name} failed: {err}")
            break
        made.append((name, int(out)))

    if not made:
        return problems or ["nothing was created"]

    fsck_same(base_rc, base_lines, img, problems, "after mkdir")

    after = our_listing(bench, img, dir_ino)
    if after is not None:
        want = {(ino, EXT4_FT_DIR, name) for name, ino in made}
        got = set(after) - set(before)
        if got != want:
            problems.append(f"listing gained {sorted(got)[:3]}, expected "
                            f"{sorted(want)[:3]}")
    if not dir_csum_ok(bench, img, dir_ino)[0]:
        problems.append("a directory block checksum does not verify after mkdir")

    # Each new directory must be a walkable, genuinely empty one - not merely a
    # name. "." and ".." and nothing else.
    for name, ino in made:
        if not inode_is_dir(img, ino):
            problems.append(f"{name}: inode {ino} is not marked as a directory")
            continue
        listing = our_listing(bench, img, ino)
        if listing is None:
            problems.append(f"{name}: its own block does not parse as a directory")
            continue
        if sorted(listing) != sorted([(ino, EXT4_FT_DIR, "."),
                                      (dir_ino, EXT4_FT_DIR, "..")]):
            problems.append(f"{name}: holds {sorted(listing)} rather than just "
                            f". and ..")
        if inode_links(img, ino) != 2:
            problems.append(f"{name}: has {inode_links(img, ino)} links, a new "
                            f"directory has two")

    # The parent gained one link per new "..".
    want_links = links_before + len(made)
    if inode_links(img, dir_ino) != want_links:
        problems.append(f"parent has {inode_links(img, dir_ino)} links after "
                        f"{len(made)} mkdirs, expected {want_links}")

    # Depth. The forgotten parent link shows up here and not at one level, since
    # the directory that ends up wrong is the one above.
    chain = [made[0][1]]
    for d in range(1, depth):
        rc, out, err = run(dirwrite, img, chain[-1], "mkdir", f"deep-{d}", WHEN)
        if rc != 0:
            problems.append(f"mkdir at depth {d} failed: {err}")
            break
        chain.append(int(out))
    else:
        fsck_same(base_rc, base_lines, img, problems, f"after nesting {depth} deep")

    # A file inside a made directory, so the new block is proven usable rather
    # than merely well-formed.
    rc, out, err = run(dirwrite, img, chain[-1], "create", "inside.txt", WHEN)
    if rc != 0:
        problems.append(f"could not create a file in a directory we made: {err}")
    else:
        fsck_same(base_rc, base_lines, img, problems, "after writing inside it")

        # And it must refuse to remove that directory while the file is in it.
        parent = chain[-2] if len(chain) > 1 else dir_ino
        name = f"deep-{len(chain) - 1}" if len(chain) > 1 else made[0][0]
        rc, _, _ = run(dirwrite, img, parent, "rmdir", name, WHEN + 1)
        if rc == 0:
            problems.append("rmdir removed a directory that still held a file")

        # rmdir on something that is not a directory has to be refused too. It
        # would otherwise take the file's blocks away and decrement a link count
        # on a parent that never gained one, and the name it removes is a real
        # one - so the damage is done before anything notices.
        rc, _, _ = run(dirwrite, img, chain[-1], "rmdir", "inside.txt", WHEN + 1)
        if rc == 0:
            problems.append("rmdir removed a regular file")

        run(dirwrite, img, chain[-1], "unlink", "inside.txt", WHEN + 1)

    # Unwind the chain from the bottom, then the rest, and everything must come
    # back to exactly what it was.
    for d in range(len(chain) - 1, 0, -1):
        rc, _, err = run(dirwrite, img, chain[d - 1], "rmdir", f"deep-{d}", WHEN + 1)
        if rc != 0:
            problems.append(f"rmdir at depth {d} failed: {err}")
            break

    for name, _ in made:
        rc, _, err = run(dirwrite, img, dir_ino, "rmdir", name, WHEN + 1)
        if rc != 0:
            problems.append(f"rmdir {name} failed: {err}")
            break
    else:
        fsck_same(base_rc, base_lines, img, problems, "after rmdir")
        if our_listing(bench, img, dir_ino) != before:
            problems.append("the listing did not come back after rmdir")
        if inode_links(img, dir_ino) != links_before:
            problems.append(f"parent kept {inode_links(img, dir_ino)} links after "
                            f"everything was removed, started at {links_before}")
    return problems


def check_interop(tools, mkfs):
    """A tree we built, read by a driver that is not ours.

    The counters this layer moves are exactly the ones another driver relies on to
    walk a tree, so having libext2fs traverse one we made is worth more here than
    on any other layer.
    """
    bench, dirwrite = tools
    problems = []
    with tempfile.TemporaryDirectory() as tmp:
        img = os.path.join(tmp, "tree.img")
        mnt = os.path.join(tmp, "mnt")
        os.makedirs(mnt)
        subprocess.run(["truncate", "-s", "32M", img], capture_output=True)
        if subprocess.run([mkfs, img, "--bs", "1024"], capture_output=True).returncode:
            return ["could not format an image to build a tree in"]

        want = set()
        parent = 2
        for name in ("alpha", "beta", "gamma"):
            rc, out, err = run(dirwrite, img, parent, "mkdir", name, WHEN)
            if rc != 0:
                return [f"mkdir {name} failed while building the tree: {err}"]
            parent = int(out)
            want.add(name)
        rc, _, err = run(dirwrite, img, parent, "create", "deep.txt", WHEN)
        if rc != 0:
            return [f"could not create the file at the bottom: {err}"]

        proc = mount_fuse(img, mnt, rw=False)
        if not proc:
            return ["fuse2fs would not mount a filesystem holding our directories"]
        got = set()
        for root, dirs, files in os.walk(os.path.join(mnt, "alpha")):
            got.update(dirs)
            got.update(files)
        unmount_fuse(mnt, proc)

        missing = ({"beta", "gamma", "deep.txt"}) - got
        if missing:
            problems.append(f"the other driver cannot see {sorted(missing)} in the "
                            f"tree we built")
    return problems


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument("--cases", required=True)
    ap.add_argument("--bench", default=os.path.join(here, "bench"))
    ap.add_argument("--dirwrite", default=os.path.join(here, "dirwrite"))
    ap.add_argument("--mkfs", default=os.path.join(here, "mkfs"))
    ap.add_argument("--count", type=int, default=3)
    ap.add_argument("--depth", type=int, default=3)
    ap.add_argument("--limit", type=int)
    ap.add_argument("--no-interop", action="store_true")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    tools = (args.bench, args.dirwrite)
    for t in (*tools, args.mkfs):
        if not os.path.exists(t):
            sys.exit(f"{t} not found - build it first")

    cases = sorted(glob.glob(os.path.join(args.cases, "case-*")))
    if args.limit:
        cases = cases[:args.limit]

    checked = failed = 0
    for case in cases:
        src = os.path.join(case, "fs.img")
        for dir_ino in find_directories(src):
            with tempfile.TemporaryDirectory() as tmp:
                img = os.path.join(tmp, "fs.img")
                shutil.copy(src, img)
                problems = check_dir(img, dir_ino, tools, args.count, args.depth)
            checked += 1
            if problems:
                failed += 1
                print(f"FAIL {os.path.basename(case)} inode {dir_ino}")
                for p in problems:
                    print(f"     {p}")
            elif args.verbose:
                print(f"ok   {os.path.basename(case)} inode {dir_ino}")

    if not args.no_interop:
        problems = check_interop(tools, args.mkfs)
        if problems:
            failed += 1
            print("FAIL a tree of our directories read by fuse2fs")
            for p in problems:
                print(f"     {p}")
        elif args.verbose:
            print("ok   a tree of our directories read by fuse2fs")

    print(f"\n{checked} directories, {args.count} made and removed in each "
          f"plus {args.depth} deep, {failed} failed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
