#!/usr/bin/env python3
r"""
Proves the point of the whole feature: a container Arcanum makes can be opened by
something that is not Arcanum, and the two can pass files back and forth.

    ./interopcheck.py

Every other suite here compares us against e2fsprogs' *tools* - debugfs reads a
structure, e2fsck judges one. This one is different: it hands the filesystem to
another ext4 *driver* and lets it mount and write. fuse2fs is libext2fs's own
implementation, so it agrees with us only where we are both right about the
format, not merely where we made the same assumption.

The exchange runs in both directions on purpose. Reading what another driver wrote
proves we understand what it produces; having it read what we wrote proves it
understands what we produce, which is the direction that matters for a user who
takes a container to a desktop.

## The feature set is the subject of this test

Containers are made without has_journal and without dir_index, and the reason for
each is worth restating where it will be read:

  dir_index    does not mean directories are indexed, it means the kernel may
               index them. A container filled up on a real Linux would come back
               with an htree directory this implementation refuses to write to,
               and no update from us is involved in that happening.
  has_journal  nothing here writes through a journal, and writing around a
               journal that has anything in it silently loses whatever we wrote
               when it is next replayed. Dropping it also drops orphan_file,
               which needs it and which would be the same kind of problem.

Neither is required for a filesystem to be ext4, and this test is what says so
out loud rather than in a comment.
"""

import os
import shutil
import subprocess
import sys
import tempfile
import time

FEATURES = "^has_journal,^dir_index"
WHEN = 1784639915


def sh(*args, **kw):
    return subprocess.run(args, capture_output=True, text=True, **kw)


def mount_fuse(img, mnt, rw=True):
    """Mounts and returns the fuse2fs process, or None if it never came up.

    Run in the foreground so that there is a process to wait for. That matters at
    unmount rather than here: `fusermount -u` detaches the mount point and
    returns, while the daemon is still writing its cache back into the image, so
    os.path.ismount reports "unmounted" some time before the image has stopped
    changing. Reading it in that window shows a filesystem missing whatever was
    written last - an intermittent failure that looks exactly like a bug in the
    thing under test, which is how it was found.
    """
    opts = "rw,fakeroot" if rw else "ro"
    proc = subprocess.Popen(["fuse2fs", img, mnt, "-o", opts, "-f"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for _ in range(60):
        if os.path.ismount(mnt):
            return proc
        if proc.poll() is not None:
            return None
        time.sleep(0.1)
    proc.kill()
    return None


def unmount_fuse(mnt, proc=None):
    """Unmounts and waits for the image to be settled, not merely detached."""
    sh("fusermount", "-u", mnt)
    if proc is not None:
        try:
            proc.wait(timeout=60)
        except subprocess.TimeoutExpired:
            proc.kill()
            return False
        return not os.path.ismount(mnt)
    for _ in range(40):
        if not os.path.ismount(mnt):
            return True
        time.sleep(0.1)
    return False


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    bench = os.path.join(here, "bench")
    dirwrite = os.path.join(here, "dirwrite")
    fsmeta = os.path.join(here, "fsmeta")
    for t in (bench, dirwrite, fsmeta):
        if not os.path.exists(t):
            sys.exit(f"{t} not found - build it first")
    if not shutil.which("fuse2fs"):
        sys.exit("fuse2fs not found - it is what makes this an interop test "
                 "rather than another self-check")

    problems = []
    with tempfile.TemporaryDirectory() as tmp:
        img = os.path.join(tmp, "container.img")
        mnt = os.path.join(tmp, "mnt")
        os.makedirs(mnt)

        sh("truncate", "-s", "32M", img)
        r = sh("mkfs.ext4", "-q", "-F", "-O", FEATURES, "-b", "2048", img)
        if r.returncode != 0:
            sys.exit(f"could not create the container: {r.stderr.strip()[:200]}")

        # It has to be ext4 in the other driver's opinion, not only in ours.
        feats = sh("dumpe2fs", "-h", img).stdout
        for unwanted in ("has_journal", "dir_index", "orphan_file"):
            if unwanted in feats:
                problems.append(f"{unwanted} is enabled, which the feature set "
                                f"is chosen to avoid")
        if "extent" not in feats or "metadata_csum" not in feats:
            problems.append("the container lost a feature it is supposed to have")

        # Direction one: the other driver writes, we read.
        proc = mount_fuse(img, mnt)
        if not proc:
            sys.exit("fuse2fs would not mount a container we created - that is "
                     "the interop claim failing outright")
        with open(os.path.join(mnt, "from-the-other-side.txt"), "w") as f:
            f.write("written by fuse2fs\n")
        os.makedirs(os.path.join(mnt, "subdir"), exist_ok=True)
        sh("sync")
        if not unmount_fuse(mnt, proc):
            problems.append("fuse2fs would not unmount")

        listing = sh(bench, img, "2", "--ls").stdout.split()
        if "from-the-other-side.txt" not in listing:
            problems.append("we cannot see the file the other driver wrote")
        if "subdir" not in listing:
            problems.append("we cannot see the directory the other driver made")

        # Direction two: we write, the other driver reads.
        r = sh(dirwrite, img, "2", "create", "from-arcanum.txt", str(WHEN))
        if r.returncode != 0:
            problems.append(f"we could not create a file in it: {r.stderr.strip()}")
        if sh(fsmeta, img).returncode != 0:
            problems.append("our own checksums do not verify after writing")
        r = sh("e2fsck", "-fn", img)
        if r.returncode != 0:
            problems.append(f"e2fsck rejects the container after we wrote to it "
                            f"(rc={r.returncode})")

        proc = mount_fuse(img, mnt, rw=False)
        if not proc:
            problems.append("fuse2fs would not mount the container after we wrote "
                            "to it - which is the failure that matters most here")
        else:
            names = os.listdir(mnt)
            if "from-arcanum.txt" not in names:
                problems.append("the other driver cannot see the file we created")
            if "from-the-other-side.txt" not in names:
                problems.append("its own file went missing after we wrote")
            unmount_fuse(mnt, proc)

    if problems:
        print("FAIL")
        for p in problems:
            print(f"     {p}")
        return 1
    print("a container made here was mounted, written and read by another ext4 "
          "driver, both ways round")
    return 0


if __name__ == "__main__":
    sys.exit(main())
