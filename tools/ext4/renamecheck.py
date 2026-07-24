#!/usr/bin/env python3
r"""
The harness for rename/move (ext4_rename): renaming an entry in place, moving one
to another directory, and moving a directory - which also has to repoint its ".."
and shift a link between the two parents.

    ./renamecheck.py

The tree the moves act on is built by another driver, not by us: our formatter lays
the container down, then fuse2fs (libext2fs's own implementation) creates the files
and directories through a real mount. So the input is not something our own create
code produced, and this test isolates the one operation under it - the move.

The oracles are all independent of our reader:

  e2fsck -fn   after every move, and it must be clean. This is where a wrong link
               count or a ".." that still names the old parent is caught - fsck
               derives both from the tree and rejects the mismatch (rc=4), so a
               move that forgot either is not merely suboptimal, it is unclean.
  debugfs      the moved thing is gone from its old path and present at the new one
               with the *same inode* it had (moved, not copied), and a moved
               directory's ".." names its new parent - read straight out of the
               directory block, not inferred.
  fuse2fs      the file's bytes are read back through the other driver and matched
               to a per-file pattern, proving the inode carried its data across.

Refusals are checked too, and each must leave the image byte-for-byte unchanged: a
destination that already exists, a directory moved into its own subtree, the root,
a source that is not there, and a destination whose parent is a file. A refusal
that wrote anything before bailing would fail the byte-identity check even if it
returned the right code.

Two block sizes, because ".." and the checksum tail sit differently at each.
"""

import os
import shutil
import subprocess
import sys
import tempfile

# Shared with interopcheck: foreground-mount fuse2fs and wait for writeback on
# unmount, so a read never lands in the window where the image is still settling.
from interopcheck import mount_fuse, unmount_fuse

# Exit codes the rename driver returns on refusal: the primitive's own error negated
# (see rename.c and ext4_dirwrite.h / ext4_create.h).
EXIT_EXISTS = 3    # EXT4_DIRW_ERR_EXISTS
EXIT_ABSENT = 4    # EXT4_DIRW_ERR_ABSENT
EXIT_LOOP = 11     # EXT4_CREATE_ERR_LOOP
EXIT_PATH = 2      # a bad path stopped the driver before ext4_rename


def sh(*a, **k):
    return subprocess.run(a, capture_output=True, text=True, **k)


def content(name, n):
    """A per-file byte pattern - a function of the name and the offset, so a file
    read back at a new path can be checked to be the same bytes, in order."""
    salt = sum(name.encode()) % 251
    return bytes(((i ^ (i >> 8) ^ salt) & 0xFF) for i in range(n))


# The tree fuse2fs builds. (path, size) for files; directories are the "d" rows.
FILES = [
    ("/alpha.bin", 5000),
    ("/beta.bin", 2000),
    ("/docs/note.bin", 9000),
    ("/docs/inner/leaf.bin", 1500),
]
DIRS = ["/docs", "/docs/inner", "/media"]


def build_tree(img, mnt, problems):
    proc = mount_fuse(img, mnt, rw=True)
    if not proc:
        problems.append("fuse2fs would not mount to build the tree")
        return False
    try:
        for d in DIRS:
            os.makedirs(mnt + d, exist_ok=True)
        for path, n in FILES:
            with open(mnt + path, "wb") as f:
                f.write(content(path, n))
        sh("sync")
    finally:
        ok = unmount_fuse(mnt, proc)
    if not ok:
        problems.append("fuse2fs would not unmount after building the tree")
    return ok


def dbg_stat(img, path):
    """The inode number `debugfs stat` reports for a path, or None if it is absent."""
    out = sh("debugfs", "-R", f'stat "{path}"', img).stdout
    if "Inode:" not in out:
        return None
    after = out.split("Inode:", 1)[1].strip()
    tok = after.split()[0]
    return int(tok) if tok.isdigit() else None


def dbg_dotdot(img, path):
    """The inode `..` points at inside `path`, read from the directory listing."""
    out = sh("debugfs", "-R", f'ls -l "{path}"', img).stdout
    for line in out.splitlines():
        toks = line.split()
        if toks and toks[-1] == ".." and toks[0].isdigit():
            return int(toks[0])
    return None


def fsck_clean(img, problems, label):
    r = sh("e2fsck", "-fn", img)
    if r.returncode != 0:
        detail = (r.stdout + r.stderr).strip().replace("\n", " ")[:240]
        problems.append(f"[{label}] e2fsck is not clean (rc={r.returncode}): {detail}")
        return False
    return True


def read_via_fuse(img, mnt, rel, problems, label):
    """Reads a file back through fuse2fs; returns its bytes or None."""
    proc = mount_fuse(img, mnt, rw=False)
    if not proc:
        problems.append(f"[{label}] fuse2fs would not mount for read-back")
        return None
    try:
        with open(os.path.join(mnt, rel), "rb") as f:
            return f.read()
    except OSError as e:
        problems.append(f"[{label}] cannot read {rel} through fuse2fs: {e}")
        return None
    finally:
        unmount_fuse(mnt, proc)


def expect_move(rename, base, tmp, mnt, label, old, new,
                *, is_dir, orig_inode, new_parent_inode,
                readback_rel, readback_src, problems):
    """One successful move: apply it, then hold it to every oracle."""
    img = os.path.join(tmp, "work.img")
    shutil.copy(base, img)

    r = sh(rename, img, old, new)
    if r.returncode != 0:
        problems.append(f"[{label}] rename {old} -> {new} was refused "
                        f"(rc={r.returncode}): {r.stderr.strip()}")
        return
    if not fsck_clean(img, problems, label):
        return

    if dbg_stat(img, old) is not None:
        problems.append(f"[{label}] {old} still exists after the move")
    moved = dbg_stat(img, new)
    if moved is None:
        problems.append(f"[{label}] {new} does not exist after the move")
        return
    if moved != orig_inode:
        problems.append(f"[{label}] {new} is inode {moved}, not the {orig_inode} "
                        f"that {old} was - moved, not the same inode")

    if is_dir:
        dd = dbg_dotdot(img, new)
        if dd != new_parent_inode:
            problems.append(f"[{label}] '..' of {new} is inode {dd}, should be "
                            f"{new_parent_inode} (its new parent)")

    got = read_via_fuse(img, mnt, readback_rel, problems, label)
    if got is not None and got != readback_src:
        where = next((i for i in range(min(len(got), len(readback_src)))
                      if got[i] != readback_src[i]), None)
        problems.append(f"[{label}] {readback_rel} read back wrong "
                        f"(len {len(got)} vs {len(readback_src)}, first diff at {where})")


def expect_refusal(rename, base, tmp, label, old, new, want_code, problems):
    """A move that must be refused, with the right code, having written nothing."""
    img = os.path.join(tmp, "work.img")
    shutil.copy(base, img)

    r = sh(rename, img, old, new)
    if r.returncode != want_code:
        problems.append(f"[{label}] rename {old} -> {new} gave rc={r.returncode}, "
                        f"expected {want_code}: {r.stderr.strip()}")
    if sh("cmp", "-s", img, base).returncode != 0:
        problems.append(f"[{label}] the image changed even though the move was "
                        f"refused - something was written before it bailed")
    fsck_clean(img, problems, label)


def check_at(block_size, tools, problems):
    mkfs, rename = tools
    with tempfile.TemporaryDirectory() as tmp:
        base = os.path.join(tmp, "base.img")
        mnt = os.path.join(tmp, "mnt")
        os.makedirs(mnt)

        sh("truncate", "-s", "32M", base)
        if sh(mkfs, base, "--bs", str(block_size)).returncode:
            problems.append(f"[{block_size}] mkfs failed")
            return
        if not build_tree(base, mnt, problems):
            return
        if not fsck_clean(base, problems, f"{block_size}/base"):
            return

        # Inodes recorded from the base, so a move can be checked to preserve them.
        ino = {p: dbg_stat(base, p) for p in
               ["/alpha.bin", "/beta.bin", "/docs", "/docs/inner", "/media",
                "/docs/note.bin", "/docs/inner/leaf.bin"]}
        root_ino = dbg_stat(base, "/")

        def L(s):
            return f"{block_size}/{s}"

        # ── successful moves ────────────────────────────────────────────────
        # A file renamed in place.
        expect_move(rename, base, tmp, mnt, L("file-rename"),
                    "/alpha.bin", "/alpha2.bin", is_dir=False,
                    orig_inode=ino["/alpha.bin"], new_parent_inode=root_ino,
                    readback_rel="alpha2.bin", readback_src=content("/alpha.bin", 5000),
                    problems=problems)

        # A directory renamed in place: contents follow, ".." unchanged (still root).
        expect_move(rename, base, tmp, mnt, L("dir-rename"),
                    "/docs", "/documents", is_dir=True,
                    orig_inode=ino["/docs"], new_parent_inode=root_ino,
                    readback_rel="documents/note.bin",
                    readback_src=content("/docs/note.bin", 9000), problems=problems)

        # A file moved to another directory.
        expect_move(rename, base, tmp, mnt, L("file-move"),
                    "/beta.bin", "/media/beta.bin", is_dir=False,
                    orig_inode=ino["/beta.bin"], new_parent_inode=ino["/media"],
                    readback_rel="media/beta.bin",
                    readback_src=content("/beta.bin", 2000), problems=problems)

        # A directory moved to another parent: ".." repoints, links shift.
        expect_move(rename, base, tmp, mnt, L("dir-move"),
                    "/docs/inner", "/media/inner", is_dir=True,
                    orig_inode=ino["/docs/inner"], new_parent_inode=ino["/media"],
                    readback_rel="media/inner/leaf.bin",
                    readback_src=content("/docs/inner/leaf.bin", 1500),
                    problems=problems)

        # A file moved and renamed at once.
        expect_move(rename, base, tmp, mnt, L("file-move-rename"),
                    "/alpha.bin", "/media/alpha_moved.bin", is_dir=False,
                    orig_inode=ino["/alpha.bin"], new_parent_inode=ino["/media"],
                    readback_rel="media/alpha_moved.bin",
                    readback_src=content("/alpha.bin", 5000), problems=problems)

        # A no-op: same name, same directory. Success, and the file is still there.
        noop = os.path.join(tmp, "work.img")
        shutil.copy(base, noop)
        r = sh(rename, noop, "/beta.bin", "/beta.bin")
        if r.returncode != 0:
            problems.append(f"[{L('no-op')}] renaming a file to itself was refused "
                            f"(rc={r.returncode})")
        elif dbg_stat(noop, "/beta.bin") != ino["/beta.bin"]:
            problems.append(f"[{L('no-op')}] the file went missing after a no-op rename")
        else:
            fsck_clean(noop, problems, L("no-op"))

        # ── refusals ────────────────────────────────────────────────────────
        expect_refusal(rename, base, tmp, L("exists"),
                       "/alpha.bin", "/beta.bin", EXIT_EXISTS, problems)
        expect_refusal(rename, base, tmp, L("loop"),
                       "/docs", "/docs/inner/docs", EXIT_LOOP, problems)
        expect_refusal(rename, base, tmp, L("root"),
                       "/", "/root2", EXIT_PATH, problems)
        expect_refusal(rename, base, tmp, L("absent"),
                       "/ghost.bin", "/whatever.bin", EXIT_ABSENT, problems)
        expect_refusal(rename, base, tmp, L("parent-is-file"),
                       "/alpha.bin", "/beta.bin/x.bin", EXIT_PATH, problems)


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--mkfs", default=os.path.join(here, "mkfs"))
    ap.add_argument("--rename", default=os.path.join(here, "rename"))
    args = ap.parse_args()

    mkfs, rename = args.mkfs, args.rename
    for t in (mkfs, rename):
        if not os.path.exists(t):
            sys.exit(f"{t} not found - build it first (tools/ext4/build.sh)")
    for prog in ("fuse2fs", "debugfs", "e2fsck"):
        if not shutil.which(prog):
            sys.exit(f"{prog} not found - it is one of the independent oracles here")

    problems = []
    for bs in (1024, 4096):
        check_at(bs, (mkfs, rename), problems)

    if problems:
        print("FAIL")
        for p in problems:
            print(f"     {p}")
        return 1
    print("renamed and moved files and directories at two block sizes: e2fsck clean, "
          "'..' and inodes correct via debugfs, contents intact through fuse2fs; "
          "every bad move refused with nothing written")
    return 0


if __name__ == "__main__":
    sys.exit(main())
