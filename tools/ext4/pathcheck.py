#!/usr/bin/env python3
r"""
The harness for resolving a path to an inode.

    ./pathcheck.py

Path resolution is the addressing layer the whole callable surface sits on: every
operation a caller names by path has to become an inode first, and getting the
wrong one means reading or writing the wrong file. So it is checked against an
oracle that walks the tree by itself - debugfs - rather than against our own
reader, which would agree with us wherever we agree with ourselves.

A tree is built with our own tools (mkfs, mkdir, create) at known nested paths.
Then every path in it is resolved two ways and the inodes compared:

  ours       pathresolve, the code under test
  debugfs    `stat "<path>"`, e2fsprogs walking the same tree

They have to name the same inode for every path, and the file-or-directory bit has
to match what the tree was built with.

The refusals are half the point, because they are what a caller acts on:

  ENOENT     a component simply is not there
  ENOTDIR    a component is there but is a file being walked into as a directory -
             a different fix from ENOENT, which is why it is a different code
  EINVAL     the path resolves to the root, which has no parent to create in

and the odd-but-valid forms - repeated, leading and trailing slashes, "." and
".." - have to resolve exactly as the clean path does, because a real caller's
paths are not always tidy.
"""

import os
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

WHEN = 1784639915


def run(tool, *args):
    r = subprocess.run([tool, *[str(a) for a in args]], capture_output=True, text=True)
    return r.returncode, r.stdout.strip(), r.stderr.strip()


def debugfs_inode(img, path):
    """The inode debugfs resolves `path` to, or None. Independent of our walk."""
    r = subprocess.run(["debugfs", "-R", f'stat "{path}"', img],
                       capture_output=True, text=True)
    for line in r.stdout.splitlines():
        if line.startswith("Inode:"):
            return int(line.split()[1])
    return None


def build_tree(mkfs, dirwrite, img):
    """A tree of known nested paths, built with our own tools. Returns
    {path: (inode, is_dir)} for everything in it."""
    subprocess.run(["truncate", "-s", "16M", img], capture_output=True)
    if subprocess.run([mkfs, img, "--bs", "1024"], capture_output=True).returncode:
        return None

    tree = {"/": (2, True), "/lost+found": (11, True)}

    def mkdir(parent_ino, name, path):
        rc, out, err = run(dirwrite, img, parent_ino, "mkdir", name, WHEN)
        if rc != 0:
            raise RuntimeError(f"mkdir {path}: {err}")
        tree[path] = (int(out), True)
        return int(out)

    def create(parent_ino, name, path):
        rc, out, err = run(dirwrite, img, parent_ino, "create", name, WHEN)
        if rc != 0:
            raise RuntimeError(f"create {path}: {err}")
        tree[path] = (int(out), False)
        return int(out)

    photos = mkdir(2, "photos", "/photos")
    y2026 = mkdir(photos, "2026", "/photos/2026")
    mkdir(photos, "2025", "/photos/2025")
    create(y2026, "img.jpg", "/photos/2026/img.jpg")
    create(y2026, "clip.mp4", "/photos/2026/clip.mp4")
    create(2, "top.txt", "/top.txt")
    docs = mkdir(2, "docs", "/docs")
    deep = docs
    for i in range(1, 6):                       # a deliberately deep chain
        deep = mkdir(deep, f"d{i}", "/docs/" + "/".join(f"d{j}" for j in range(1, i + 1)))
    create(deep, "leaf", "/docs/d1/d2/d3/d4/d5/leaf")
    return tree


def check(tools):
    mkfs, dirwrite, pathresolve = tools
    problems = []
    with tempfile.TemporaryDirectory() as tmp:
        img = os.path.join(tmp, "tree.img")
        try:
            tree = build_tree(mkfs, dirwrite, img)
        except RuntimeError as e:
            return [str(e)]
        if tree is None:
            return ["could not format an image to build a tree in"]

        # Every built path: ours must equal debugfs, and the kind must match.
        for path, (want_ino, want_dir) in tree.items():
            rc, out, err = run(pathresolve, img, "resolve", path)
            if rc != 0:
                problems.append(f"resolve {path}: we failed ({err}), "
                                f"debugfs says inode {debugfs_inode(img, path)}")
                continue
            got_ino, kind = out.split()
            got_ino = int(got_ino)
            oracle = debugfs_inode(img, path)
            if oracle is not None and got_ino != oracle:
                problems.append(f"resolve {path}: we say {got_ino}, debugfs {oracle}")
            if got_ino != want_ino:
                problems.append(f"resolve {path}: inode {got_ino}, built as {want_ino}")
            if (kind == "dir") != want_dir:
                problems.append(f"resolve {path}: kind {kind}, built as "
                                f"{'dir' if want_dir else 'file'}")

        # Untidy but valid forms resolve to the same place as the clean path.
        same = {
            "/photos//2026": "/photos/2026",
            "/photos/2026/": "/photos/2026",
            "//photos/2026": "/photos/2026",
            "/./photos/2026": "/photos/2026",
            "/photos/2025/../2026": "/photos/2026",
            "/docs/d1/../../top.txt": "/top.txt",
        }
        for messy, clean in same.items():
            rc, out, _ = run(pathresolve, img, "resolve", messy)
            want = tree[clean][0]
            if rc != 0 or int(out.split()[0]) != want:
                problems.append(f"resolve {messy}: did not land on {clean} "
                                f"(inode {want}), got '{out}'")

        # The three refusals, each with its own code.
        refusals = {
            "/nope": "ENOENT",
            "/photos/2027": "ENOENT",
            "/top.txt/anything": "ENOTDIR",          # a file used as a directory
            "/photos/2026/img.jpg/x": "ENOTDIR",
            "/photos/nope/deeper": "ENOENT",         # missing dir before a real-looking tail
        }
        for path, want in refusals.items():
            rc, _, err = run(pathresolve, img, "resolve", path)
            if rc == 0:
                problems.append(f"resolve {path}: accepted, expected {want}")
            elif err != want:
                problems.append(f"resolve {path}: got {err}, expected {want}")

        # resolve parent, which is what create/mkdir/unlink stand on.
        parents = {
            "/photos/2026/img.jpg": (tree["/photos/2026"][0], "img.jpg"),
            "/top.txt": (2, "top.txt"),
            "/photos/newfile": (tree["/photos"][0], "newfile"),   # need not exist yet
        }
        for path, (want_ino, want_name) in parents.items():
            rc, out, err = run(pathresolve, img, "parent", path)
            if rc != 0:
                problems.append(f"parent {path}: failed ({err})")
                continue
            got_ino, got_name = out.split(maxsplit=1)
            if int(got_ino) != want_ino or got_name != want_name:
                problems.append(f"parent {path}: got {got_ino} '{got_name}', "
                                f"expected {want_ino} '{want_name}'")

        # The root has no parent.
        rc, _, err = run(pathresolve, img, "parent", "/")
        if rc == 0 or err != "EINVAL":
            problems.append(f"parent /: expected EINVAL, got rc={rc} {err}")

        # A parent path that runs through a file is ENOTDIR, not ENOENT.
        rc, _, err = run(pathresolve, img, "parent", "/top.txt/child")
        if err != "ENOTDIR":
            problems.append(f"parent /top.txt/child: got {err}, expected ENOTDIR")

    return problems


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--mkfs", default=os.path.join(here, "mkfs"))
    ap.add_argument("--dirwrite", default=os.path.join(here, "dirwrite"))
    ap.add_argument("--pathresolve", default=os.path.join(here, "pathresolve"))
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    tools = (args.mkfs, args.dirwrite, args.pathresolve)
    for t in tools:
        if not os.path.exists(t):
            sys.exit(f"{t} not found - build it first")
    import shutil
    if not shutil.which("debugfs"):
        sys.exit("debugfs not found - it is the independent oracle here")

    problems = check(tools)
    if problems:
        print("FAIL")
        for p in problems:
            print(f"     {p}")
        return 1
    print("every path resolved to the inode debugfs resolves it to, refusals "
          "included")
    return 0


if __name__ == "__main__":
    sys.exit(main())
