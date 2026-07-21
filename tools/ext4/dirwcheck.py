#!/usr/bin/env python3
r"""
The harness for writing directory entries.

    ./dirwcheck.py --cases /tmp/cases

Both operations edit a linked list inside a fixed-size block, and the failure they
share is a chain that no longer adds up to exactly the block. Overshoot and it
walks into the checksum tail; undershoot and it leaves a hole no reader will ever
visit, so the space is gone for good. Neither is a checksum failure - the block is
rewritten and restamped either way - so e2fsck is the oracle, and here it can be
required to be completely clean: a name and a link count are two halves of one
link and the driver moves both.

Per directory:

  add        the listing gains exactly the new entry and nothing else moves
  fsck       identical to the pristine run, at every step
  duplicate  adding the same name twice is refused rather than allowed
  absent     removing a name that is not there is refused
  remove     the listing comes back exactly, and fsck is clean again

The listing coming back is stronger than it looks. Adding splits the gap inside
some entry's rec_len and removing gives it back to the entry in front, so a wrong
split that happens to read correctly still leaves the chain different afterwards.
"""

import argparse
import glob
import json
import os
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from appendcheck import fsck, line_delta, BENIGN_REMARK        # noqa: E402
from dircheck import our_listing, dir_csum_ok, find_directories  # noqa: E402


def run(tool, *args):
    r = subprocess.run([tool, *[str(a) for a in args]],
                       capture_output=True, text=True)
    return r.returncode, r.stderr.strip()


def fsck_same(base_rc, base_lines, img, problems, when):
    rc, lines, _ = fsck(img)
    if rc != base_rc:
        problems.append(f"fsck return code changed {when}: {base_rc} -> {rc}")
    new, gone = line_delta(base_lines, lines)
    appeared = [l for l in new if not BENIGN_REMARK.match(l)]
    vanished = [l for l in gone if not BENIGN_REMARK.match(l)]
    if appeared:
        problems.append(f"fsck complains {when}: {appeared[:3]}")
    if vanished:
        problems.append(f"fsck stopped saying, {when}: {vanished[:3]}")


def dir_size(img, dir_ino):
    from dircheck import debugfs
    import re as _re
    text = debugfs(img, f"stat <{dir_ino}>\n")
    m = _re.search(r"Size:\s*(\d+)", text)
    return int(m.group(1)) if m else None


def grow_pass(img, dir_ino, bench, dirwrite, target, base_rc, base_lines, before):
    """Adds names until the directory has to take a new block, then removes them.

    Growth is the path that formats a block from scratch - a dead entry spanning
    it and a tail with a checksum - and nothing else exercises it. Most
    directories in the corpus have gaps left by deleted filler files, so without
    pushing until the size moves, only the one already-full directory would ever
    reach it.
    """
    problems = []
    start_size = dir_size(img, dir_ino)
    added = []

    for i in range(400):
        name = f"grow-{i:04d}"
        rc, err = run(dirwrite, img, dir_ino, "add", name, target, 1)
        if rc != 0:
            problems.append(f"adding {name} failed: {err}")
            break
        added.append(name)
        if dir_size(img, dir_ino) != start_size:
            break
    else:
        problems.append("400 entries added and the directory never grew")

    if added and not problems:
        fsck_same(base_rc, base_lines, img, problems, "after growing")
        ok, _ = dir_csum_ok(bench, img, dir_ino)
        if not ok:
            problems.append("a directory block checksum does not verify after growing")
        got = our_listing(bench, img, dir_ino)
        if got is not None:
            missing = [n for n in added if not any(e[2] == n for e in got)]
            if missing:
                problems.append(f"entries lost after growing: {missing[:3]}")

    for name in added:
        rc, err = run(dirwrite, img, dir_ino, "remove", name)
        if rc != 0:
            problems.append(f"removing {name} failed: {err}")
            break
    else:
        fsck_same(base_rc, base_lines, img, problems, "after removing everything")
        if our_listing(bench, img, dir_ino) != before:
            problems.append("the listing did not come back after growing and "
                            "emptying again")
    return problems


def short_name_pass(img, dir_ino, bench, dirwrite, target,
                    base_rc, base_lines, before):
    """Add and remove a name needing only 12 bytes - the size of the checksum
    tail, and so the only one that fits where the tail sits."""
    problems = []
    short = "ab"
    rc, err = run(dirwrite, img, dir_ino, "add", short, target, 1)
    if rc != 0:
        return ([] if "no gap big enough" in err
                else [f"adding a short name failed: {err}"]), "refused"

    fsck_same(base_rc, base_lines, img, problems, "after adding a short name")
    ok, _ = dir_csum_ok(bench, img, dir_ino)
    if not ok:
        problems.append("a directory block checksum does not verify after "
                        "adding a short name")
    got = our_listing(bench, img, dir_ino)
    if got is not None and (target, 1, short) not in got:
        problems.append("the short name is not in the listing")

    rc, err = run(dirwrite, img, dir_ino, "remove", short)
    if rc != 0:
        problems.append(f"removing the short name failed: {err}")
    elif our_listing(bench, img, dir_ino) != before:
        problems.append("the listing did not come back after the short name")
    return problems, None


def check_dir(img, dir_ino, bench, dirwrite, name="harness-entry", grow=False):
    """`name` is deliberately long enough to need 24 bytes. A second pass with a
    short one follows, because an entry needing only 12 is exactly the size that
    fits inside the checksum tail - a writer that does not stop the chain short of
    the tail has nowhere to put a long name and gives itself away only on a short
    one."""
    problems = []
    base_rc, base_lines, _ = fsck(img)
    if base_rc != 0:
        return [f"pristine image is not fsck-clean (rc={base_rc})"], "error"

    before = our_listing(bench, img, dir_ino)
    if before is None:
        return ["could not list the directory before writing"], "error"

    # Point the new name at a file that already exists, which makes it a hard
    # link - the one case where the entry and the link count must both move.
    target = next((i for i, ft, n in before if ft == 1 and n not in (".", "..")), None)
    if target is None:
        return [], "no-target"   # a directory with no file to link to

    rc, err = run(dirwrite, img, dir_ino, "add", name, target, 1)
    if rc != 0:
        if "no gap big enough" in err:
            # A directory too full for 24 bytes may still have the 12 the
            # checksum tail occupies, and that is the only place a writer which
            # fails to stop the chain short of the tail will put anything. So the
            # full directory is exactly the one worth trying a short name on -
            # skipping it here left that mutant unreachable.
            return short_name_pass(img, dir_ino, bench, dirwrite, target,
                                   base_rc, base_lines, before)
        return [f"add failed: {err}"], None

    fsck_same(base_rc, base_lines, img, problems, "after adding")

    after = our_listing(bench, img, dir_ino)
    if after is None:
        problems.append("could not list the directory after adding")
    else:
        added = [e for e in after if e not in before]
        lost = [e for e in before if e not in after]
        if added != [(target, 1, name)]:
            problems.append(f"listing gained {added}, expected one new entry")
        if lost:
            problems.append(f"adding an entry lost {lost[:3]}")

    ok, _ = dir_csum_ok(bench, img, dir_ino)
    if not ok:
        problems.append("a directory block checksum does not verify after adding")

    rc, _ = run(dirwrite, img, dir_ino, "add", name, target, 1)
    if rc == 0:
        problems.append("adding the same name twice was allowed")

    rc, _ = run(dirwrite, img, dir_ino, "remove", "no-such-entry-here")
    if rc == 0:
        problems.append("removing a name that is not there was allowed")

    rc, err = run(dirwrite, img, dir_ino, "remove", name)
    if rc != 0:
        problems.append(f"remove failed: {err}")
        return problems, False

    fsck_same(base_rc, base_lines, img, problems, "after removing")

    back = our_listing(bench, img, dir_ino)
    if back != before:
        problems.append("the listing did not come back after removing")

    ok, _ = dir_csum_ok(bench, img, dir_ino)
    if not ok:
        problems.append("a directory block checksum does not verify after removing")

    extra, _ = short_name_pass(img, dir_ino, bench, dirwrite, target,
                               base_rc, base_lines, before)
    problems += extra
    if grow:
        problems += grow_pass(img, dir_ino, bench, dirwrite, target,
                              base_rc, base_lines, before)
    return problems, None


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser()
    ap.add_argument("--cases", required=True)
    ap.add_argument("--bench", default=os.path.join(here, "bench"))
    ap.add_argument("--dirwrite", default=os.path.join(here, "dirwrite"))
    ap.add_argument("--limit", type=int)
    ap.add_argument("--grow", action="store_true",
                    help="also push each directory until it takes a new block, "
                         "which is the only way the block-formatting path runs")
    # Zero, and it stays zero. The densest directory in the corpus - case-014's
    # root, 601 entries in seven blocks - has no gap of 24 bytes but does have the
    # 12 a short name needs, so it is exercised rather than skipped. A writer that
    # declined work would otherwise report no failures and look identical to one
    # with nothing to do, which is how an earlier suite was fooled.
    ap.add_argument("--max-refused", type=int, default=0,
                    help="how many directories may be refused for want of room")
    ap.add_argument("-v", "--verbose", action="store_true")
    args = ap.parse_args()

    for tool in (args.bench, args.dirwrite):
        if not os.path.exists(tool):
            sys.exit(f"{tool} not found - build it first")

    cases = sorted(glob.glob(os.path.join(args.cases, "case-*")))
    if args.limit:
        cases = cases[:args.limit]

    checked = failed = 0
    skips = {"no-target": 0, "refused": 0, "error": 0}
    refused_where = []
    for case in cases:
        src = os.path.join(case, "fs.img")
        for dir_ino in find_directories(src):
            with tempfile.TemporaryDirectory() as tmp:
                img = os.path.join(tmp, "fs.img")
                shutil.copy(src, img)
                problems, skip_reason = check_dir(img, dir_ino, args.bench,
                                                  args.dirwrite, grow=args.grow)
            if skip_reason in ("no-target", "refused"):
                skips[skip_reason] += 1
                if skip_reason == "refused":
                    refused_where.append(f"{os.path.basename(case)}/{dir_ino}")
                continue
            checked += 1
            if problems:
                failed += 1
                print(f"FAIL {os.path.basename(case)} inode {dir_ino}")
                for p in problems:
                    print(f"     {p}")
            elif args.verbose:
                print(f"ok   {os.path.basename(case)} inode {dir_ino}")

    print(f"\n{checked} directories written to, {failed} failed, "
          f"{skips['no-target']} had nothing to link to, "
          f"{skips['refused']} refused for want of room")

    # The two skips are not the same thing. Having no file to link to is a
    # property of the directory and cannot be caused by a bug. Being refused for
    # want of room is the writer declining to work, and a writer that declined
    # everything would otherwise report zero failures and look identical to one
    # with nothing to do - which is how an earlier suite was fooled.
    if refused_where:
        print(f"     refused: {', '.join(refused_where)}")
    if skips["refused"] > args.max_refused:
        print(f"     {skips['refused']} refusals, more than the "
              f"{args.max_refused} allowed - an unexpected refusal is a failure")
    return 1 if failed or skips["refused"] > args.max_refused else 0


if __name__ == "__main__":
    sys.exit(main())
