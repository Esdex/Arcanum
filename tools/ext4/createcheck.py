#!/usr/bin/env python3
r"""
The harness for creating and deleting a file.

    ./createcheck.py --cases /tmp/cases

Nothing new is written here - the inode allocator, the extent writer and the
directory writer each have their own suite. What this checks is the join, and the
join's failures are ones no single layer can see: an inode allocated and named but
never filled in, a name removed while its inode is still claimed, blocks freed
while something still points at them. e2fsck is what sees all of those, and it can
be required completely clean because a correct create leaves nothing dangling.

Per directory:

  create     the listing gains exactly the new names, each reads as an empty file
  write      one of them takes data through the extent writer and reads it back,
             which is what proves the inode was made usable and not merely legal
  unlink     everything goes back: the listing returns and fsck is clean again

The deletion time is the check that would be missed by looking only at the
listing. An inode freed with no links and no i_dtime is neither live nor deleted,
and e2fsck says so - which is how it was found here.
"""
import argparse, glob, os, shutil, struct, subprocess, sys, tempfile
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from appendcheck import fsck, line_delta, BENIGN_REMARK           # noqa: E402
from dircheck import our_listing, dir_csum_ok, find_directories   # noqa: E402

WHEN = 1784639915


def inode_extra_isize(img, ino):
    """i_extra_isize of one inode, and the minimum the superblock demands.

    Checked here because nothing else does. e2fsck accepts an inode whose
    i_extra_isize is zero - the inode is then simply a classic 128-byte one with
    a 16-bit checksum, which is self-consistent and which our own reader agrees
    with. But the superblock declares s_min_extra_isize, and writing less than it
    breaks a promise the filesystem makes about every inode in it. Without this
    the mutant that zeroes the field passes every other check.
    """
    with open(img, "rb") as f:
        f.seek(1024)
        sb = f.read(1024)
        u16 = lambda b, o: struct.unpack_from("<H", b, o)[0]
        u32 = lambda b, o: struct.unpack_from("<I", b, o)[0]
        bs = 1024 << u32(sb, 0x18)
        ipg, isize, first = u32(sb, 0x28), u16(sb, 0x58), u32(sb, 0x14)
        min_extra = u16(sb, 0x15C)
        dsz = u16(sb, 0xFE) if (u32(sb, 0x60) & 0x80) else 32
        g, idx = (ino - 1) // ipg, (ino - 1) % ipg
        f.seek((first + 1) * bs + g * dsz)
        d = f.read(dsz)
        itable = u32(d, 8) | (u32(d, 40) << 32 if dsz >= 64 else 0)
        f.seek(itable * bs + idx * isize)
        inode = f.read(isize)
    if isize <= 128:
        return None, min_extra
    return u16(inode, 0x80), min_extra


def run(tool, *args):
    r = subprocess.run([tool, *[str(a) for a in args]], capture_output=True, text=True)
    return r.returncode, r.stdout.strip(), r.stderr.strip()


def fsck_same(base_rc, base_lines, img, problems, when):
    rc, lines, _ = fsck(img)
    if rc != base_rc:
        problems.append(f"fsck rc changed {when}: {base_rc} -> {rc}")
    new, gone = line_delta(base_lines, lines)
    for l in [l for l in new if not BENIGN_REMARK.match(l)]:
        problems.append(f"fsck complains {when}: {l}")
    for l in [l for l in gone if not BENIGN_REMARK.match(l)]:
        problems.append(f"fsck stopped saying, {when}: {l}")


def check_dir(img, dir_ino, tools, count):
    bench, dirwrite, extwrite = tools
    problems = []
    base_rc, base_lines, _ = fsck(img)
    if base_rc != 0:
        return [f"pristine image is not fsck-clean (rc={base_rc})"]
    before = our_listing(bench, img, dir_ino)
    if before is None:
        return ["could not list the directory"]

    made = []
    for i in range(count):
        name = f"created-{i:03d}"
        rc, out, err = run(dirwrite, img, dir_ino, "create", name, WHEN)
        if rc != 0:
            problems.append(f"create {name} failed: {err}")
            break
        made.append((name, int(out)))

    if not made:
        return problems or ["nothing was created"]

    fsck_same(base_rc, base_lines, img, problems, "after creating")

    after = our_listing(bench, img, dir_ino)
    if after is not None:
        want = {(ino, 1, name) for name, ino in made}
        got = set(after) - set(before)
        if got != want:
            problems.append(f"listing gained {sorted(got)[:3]}, expected "
                            f"{sorted(want)[:3]}")
    ok, _ = dir_csum_ok(bench, img, dir_ino)
    if not ok:
        problems.append("a directory block checksum does not verify after creating")

    for name, ino in made:
        extra, min_extra = inode_extra_isize(img, ino)
        if extra is not None and extra < min_extra:
            problems.append(f"{name}: i_extra_isize is {extra}, below the "
                            f"{min_extra} the superblock demands")
            break

    # A new inode has to be usable, not merely well-formed. Appending through the
    # extent writer and reading it back is what tells the two apart.
    name, ino = made[0]
    rc, out, err = run(extwrite, img, ino, "append", 2)
    if rc not in (0, 3):
        problems.append(f"appending to a created file failed: {err}")
    else:
        fsck_same(base_rc, base_lines, img, problems, "after writing to a new file")
        r = subprocess.run([bench, img, str(ino), "--read"], capture_output=True)
        if r.returncode != 0 or len(r.stdout) == 0:
            problems.append("a created file read back empty after being written")

    for name, _ in made:
        rc, _, err = run(dirwrite, img, dir_ino, "unlink", name, WHEN + 1)
        if rc != 0:
            problems.append(f"unlink {name} failed: {err}")
            break
    else:
        fsck_same(base_rc, base_lines, img, problems, "after unlinking")
        if our_listing(bench, img, dir_ino) != before:
            problems.append("the listing did not come back after unlinking")
    return problems


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument("--cases", required=True)
    ap.add_argument("--bench", default=os.path.join(here, "bench"))
    ap.add_argument("--dirwrite", default=os.path.join(here, "dirwrite"))
    ap.add_argument("--extwrite", default=os.path.join(here, "extwrite"))
    ap.add_argument("--count", type=int, default=3)
    ap.add_argument("--limit", type=int)
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    tools = (args.bench, args.dirwrite, args.extwrite)
    for t in tools:
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
                problems = check_dir(img, dir_ino, tools, args.count)
            checked += 1
            if problems:
                failed += 1
                print(f"FAIL {os.path.basename(case)} inode {dir_ino}")
                for p in problems:
                    print(f"     {p}")
            elif args.verbose:
                print(f"ok   {os.path.basename(case)} inode {dir_ino}")

    print(f"\n{checked} directories, {args.count} files created and deleted in "
          f"each, {failed} failed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
