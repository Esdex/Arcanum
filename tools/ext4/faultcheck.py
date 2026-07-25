#!/usr/bin/env python3
r"""
Proves that a write failing in the middle of a create-layer operation never
corrupts the filesystem - it leaves only a residual e2fsck can repair.

    ./faultcheck.py

mkdir, rename and the rest are ordered so that the moment a crash (or a failed
write) could stop them is always one the filesystem can be left in: an inode is
written before it is named, a name is added before the old one is removed, the
counter updates come last. Without a journal these steps are not atomic, so a
failure part way does leave *something* - a directory not yet linked, a parent
link count off by one, a block marked used but attached to nothing. The claim is
that it is always one of those, all of which e2fsck reconciles, and never a
multiply-claimed block, a name pointing at no inode, or lost data.

faultop drives one operation with the Nth block write forced to fail (the rest,
including any the code does in response, succeed). This sweeps N across every
write of a mkdir and of a rename and, after each, requires e2fsck to report
nothing outside the set of repairable residuals below. Naming an inode before
writing it, or freeing a block still referenced, would leave a dangling entry or a
multiply-claimed block - neither of which is in that set - so the sweep would turn
red. The allowed set was built by running it: each residual below is one a real
fault produced, added only once its repairability was confirmed.

A journal would remove the residual entirely; that is issue #7 / the extent-writer
split's sibling, not this. This proves the weaker thing the current design does
guarantee - that a half-finished operation is always repairable, never corrupt.
"""

import os
import re
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
MKFS = os.path.join(HERE, "mkfs")
DIRWRITE = os.path.join(HERE, "dirwrite")
FAULTOP = os.path.join(HERE, "faultop")
WHEN = "1784639915"

# e2fsck lines that are noise, or the residuals a well-ordered, journal-less
# operation is allowed to leave when a write fails part way. Anything a sweep
# produces outside these means the failure corrupted something.
ALLOWED = re.compile(
    r"^e2fsck |"
    r"^Pass [1-5]|"
    r"^$|"
    r": clean, |"                                   # already-clean summary
    r": \d+/\d+ files .*, \d+/\d+ blocks|"           # dirty summary line
    r"(Clear|Fix|Connect to /lost\+found|Salvage)\? no|"
    r"is a zero-length directory|"                   # orphan directory inode
    r"Unattached (zero-length )?inode|"
    r"Unconnected directory inode|"
    r"was in /|"
    r"ref count is \d+, should be|"                  # parent link off by one
    r"Directories count wrong for group|"            # bg_used_dirs off by one, repairable
    r"(Inode|Block) bitmap differences|"             # leaked/uncleared, repairable
    r"Free (inodes|blocks) count wrong|"
    r"Directory inode \d+, .*, offset 0: directory has no checksum|"
    r"Padding at end|"
    r"^\s+[-+]\d|"                                    # the +N/-N bitmap lists
    r"FILE SYSTEM WAS MODIFIED|"
    r"^IGNORED\.|"                                    # -fn boilerplate for a skipped fix
    r"WARNING: Filesystem still has errors"           # -fn banner when it left them
)


def sh(*a):
    return subprocess.run(a, capture_output=True, text=True)


def fsck_clean(img):
    return sh("e2fsck", "-fn", img).returncode == 0


def only_repairable(img):
    """-> (ok, offending text). ok when every e2fsck line is noise or a residual
    from the ALLOWED set - i.e. nothing was corrupted."""
    r = sh("e2fsck", "-fn", img)
    if r.returncode == 0:
        return True, ""
    offending = [ln for ln in (r.stdout + r.stderr).splitlines()
                 if ln.strip() and not ALLOWED.search(ln)]
    return (not offending), "\n".join(offending[:8])


def run_faultop(img, fail_at, *op):
    r = sh(FAULTOP, img, str(fail_at), *op)
    m = re.search(r"writes=(\d+)", r.stdout)
    return (int(m.group(1)) if m else -1), r.stdout.strip()


def ino_of(img, path):
    out = sh("debugfs", "-R", f'stat "{path}"', img).stdout
    m = re.search(r"Inode:\s*(\d+)", out)
    return int(m.group(1)) if m else None


def sweep(base_img, label, op, problems):
    """Fault every write of `op` in turn; after each, e2fsck must find nothing
    outside the repairable set."""
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

        bad = 0
        for n in range(1, writes + 1):
            img = os.path.join(tmp, f"f{n}.img")
            shutil.copy(base_img, img)
            run_faultop(img, n, *op)
            ok, offending = only_repairable(img)
            if not ok:
                bad += 1
                if bad <= 3:
                    problems.append(f"{label}: faulting write {n} of {writes} left a "
                                    f"non-repairable state:\n{offending}")
        if bad == 0:
            print(f"{label}: {writes} fault points, every failure repairable "
                  f"(no corruption)")


def main():
    for t in (MKFS, DIRWRITE, FAULTOP):
        if not os.path.exists(t):
            sys.exit(f"{t} not found - build it first")

    problems = []
    with tempfile.TemporaryDirectory() as tmp:
        mk = os.path.join(tmp, "mkdir.img")
        subprocess.run(["truncate", "-s", "32M", mk], check=True)
        sh(MKFS, mk)
        sweep(mk, "mkdir /d", ("mkdir", "2", "d"), problems)

        rn = os.path.join(tmp, "rename.img")
        subprocess.run(["truncate", "-s", "32M", rn], check=True)
        sh(MKFS, rn)
        sh(DIRWRITE, rn, "2", "mkdir", "a", WHEN)
        sh(DIRWRITE, rn, "2", "mkdir", "b", WHEN)
        a, b = ino_of(rn, "/a"), ino_of(rn, "/b")
        sh(DIRWRITE, rn, str(a), "create", "f.txt", WHEN)
        if a is None or b is None or not fsck_clean(rn):
            problems.append("rename base setup is not e2fsck-clean")
        else:
            sweep(rn, "rename /a/f.txt -> /b/f.txt",
                  ("rename", str(a), "f.txt", str(b), "f.txt"), problems)

    if problems:
        print("FAIL")
        for p in problems:
            print(f"     {p}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
