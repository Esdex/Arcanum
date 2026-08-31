#!/usr/bin/env bash
# Arcanum - VeraCrypt-compatible encrypted vault manager for Android
#
# Copyright (C) 2026 Esdex
# Licensed under Apache License 2.0
# SPDX-License-Identifier: Apache-2.0
#
# Host test harness for the clean-room ext4 library - PC-only, not shipped in the
# app. e2fsprogs and fuse2fs are used as external oracles (separate processes),
# never linked or copied. See issue #7.

# Measures nodecheck.py, and faultcheck.py, against a broken deferral of the
# extent node write (#162).
#
#   ./mutants-node.sh
#
# Holding a node in memory across a run of appends fails in three directions, and
# they need three different oracles, which is why this suite drives two stands.
#
#   Undo the deferral      Putting the node down every time round is entirely
#                          correct and leaves a perfect filesystem. Nothing but
#                          nodecheck's marginal count can see it.
#   Lose a change          A node whose last change never reached the disk leaves
#                          a tree that is structurally sound and describes a
#                          shorter file, which e2fsck calls clean. The read-back
#                          through debugfs is what sees it.
#   Write in the wrong
#   order                  Only visible when a write fails partway: every write
#                          succeeding leaves the same image whatever order they
#                          went in. faultcheck's sweep is the only oracle for it,
#                          and only over a file whose node is exactly full, which
#                          is the case added for this.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

. "$HERE/sources.sh"
mutant_stage "$HERE" "$WORK" session.c

if [ ! -x "$HERE/mkfs" ]; then
    echo "mkfs not built - run ./build.sh first" >&2
    exit 1
fi

STAGE="$WORK/ext4"
mkdir -p "$STAGE"

fail=0

# try <desc> [--untestable <reason>] <sed expr>...
#   Against nodecheck.py, with a mutant session driver.
try() {
    local desc="$1"; shift
    local expect_miss=""
    if [ "${1:-}" = "--untestable" ]; then expect_miss="$2"; shift 2; fi
    mutant_reset "$HERE" "$WORK"
    local expr
    for expr in "$@"; do
        sed -i "$expr" "$WORK/ext4_extwrite.c"
    done
    if ! mutant_changed "$HERE" "$WORK"; then
        echo "  SKIP  $desc - the pattern did not match, so nothing was mutated"
        fail=1; return
    fi
    if ! mutant_build "$WORK" session.c sess; then
        echo "  SKIP  $desc - mutant did not build"
        fail=1; return
    fi
    if timeout 1200 "$HERE/nodecheck.py" --mkfs "$HERE/mkfs" \
                    --session "$WORK/sess" >/dev/null 2>&1; then
        if [ -n "$expect_miss" ]; then
            echo "  untestable: $desc"; echo "              $expect_miss"
        else
            echo "  MISS  $desc - the stand did not catch it"; fail=1
        fi
    else
        if [ -n "$expect_miss" ]; then
            echo "  UNEXPECTED CATCH: $desc was marked untestable but the stand caught it"
            fail=1
        else
            echo "  caught: $desc"
        fi
    fi
}

# try_fault <desc> [--untestable <reason>] <sed expr>
#   Against the one faultcheck case that appends onto a node which is exactly full,
#   since that is the only place a node and the parent naming it are written by the
#   same operation. The whole toolchain is rebuilt from the mutated sources, as
#   mutants-fault.sh does, so the mutant is not confined to the swept binary.
try_fault() {
    local desc="$1"; shift
    local expect_miss=""
    if [ "${1:-}" = "--untestable" ]; then expect_miss="$2"; shift 2; fi
    local f
    for f in $EXT4_SOURCES $EXT4_HEADERS; do cp "$EXT4_DIR/$f" "$STAGE/"; done
    sed -i "$1" "$STAGE/ext4_extwrite.c"
    if cmp -s "$EXT4_DIR/ext4_extwrite.c" "$STAGE/ext4_extwrite.c"; then
        echo "  SKIP  $desc - the pattern did not match, so nothing was mutated"
        fail=1; return
    fi
    local SRC=""
    for f in $EXT4_SOURCES; do SRC="$SRC $STAGE/$f"; done
    if ! (cd "$WORK" &&
          cc -O2 -std=c99 -I"$STAGE" -Wl,--wrap=malloc,--wrap=calloc,--wrap=realloc \
             -o fo "$HERE/faultop.c"  $SRC 2>/dev/null &&
          cc -O2 -std=c99 -I"$STAGE" -o mk "$HERE/mkfs.c"     $SRC 2>/dev/null &&
          cc -O2 -std=c99 -I"$STAGE" -o dw "$HERE/dirwrite.c" $SRC 2>/dev/null &&
          cc -O2 -std=c99 -I"$STAGE" -o ew "$HERE/extwrite.c" $SRC 2>/dev/null); then
        echo "  SKIP  $desc - mutant did not build"
        fail=1; return
    fi
    if timeout 1200 "$HERE/faultcheck.py" --faultop "$WORK/fo" --mkfs "$WORK/mk" \
                    --dirwrite "$WORK/dw" --extwrite "$WORK/ew" \
                    --only "append 4 blocks onto" >/dev/null 2>&1; then
        if [ -n "$expect_miss" ]; then
            echo "  untestable: $desc"; echo "              $expect_miss"
        else
            echo "  MISS  $desc - the sweep did not notice"; fail=1
        fi
    else
        if [ -n "$expect_miss" ]; then
            echo "  UNEXPECTED CATCH: $desc was marked untestable but the sweep caught it"
            fail=1
        else
            echo "  caught: $desc"
        fi
    fi
}

echo "mutants of the deferred node write in ext4_extwrite.c:"

# ── Undoing the deferral: correct, e2fsck-clean, only the count sees it ─────
try "the node is put down after every block again" \
    's|^        path_touch(&p, p.depth);|        path_touch(\&p, p.depth);\n        if (path_flush(fs, \&p, inode_seed) != EXTW_OK) break;|'

try "the path is re-found every iteration, over a node that holds changes" \
    's|^    for (uint32_t i = 0; i < count; i++) {|&\n        rc = find_rightmost_path(fs, root, storage, \&p);\n        if (rc != EXTW_OK) goto out;|'

# ── Losing a change: e2fsck calls a shorter file clean, the read-back does not ─
try "nothing is ever marked, so a run of appends is never written back" \
    's|^    if (level > 0) p->dirty\[level\] = 1;|    (void)p; (void)level;|'

try "growing the tree reuses the buffers without putting them down first" \
    's|^        int rc = path_flush(fs, p, inode_seed);|        int rc = EXTW_OK;|'

try "a level is marked as it is linked, before the new chain is whole" \
    --untestable "not reachable by any stand here, and the reason is arithmetic rather
              than effort. Marking early only differs from marking late when the chain
              build fails partway, and the first level of a chain marks level 0, which
              path_touch ignores because the root rides in the inode. So the earliest
              harmful case needs a chain of three levels whose THIRD allocation is the
              one that runs out of space - a depth-3 tree, every node on the right edge
              full, and the volume ending within one block of that moment. Building
              that on purpose is a stand that tests its own setup and nothing else." \
    's|            wr16(pn + EH_ENTRIES_OFF, (uint16_t)(pe + 1));|&\n            path_touch(p, level - 1);|'

# ── Writing in the wrong order: only a failed write can show it ─────────────
try "a parent reaches the disk before the child it names" \
    's|    for (int level = p->depth; level >= 1; level--) {|    for (int level = 1; level <= p->depth; level++) {|'

# ── The commit order the size and the root are caught between (#164) ───────
try "the size is committed after the tree instead of before it" \
    's|^    int root_moved = memcmp(root_after, root_before, INODE_IBLOCK_SIZE) != 0;|    int root_moved = 1;|' \
    '/^    memcpy(root, root_before, INODE_IBLOCK_SIZE);$/,+5d'

try "the new root goes down with the size, ahead of the tree it names" \
    's|^    memcpy(root, root_before, INODE_IBLOCK_SIZE);|    /* mutant: whatever the loop left in the root */|'

try "the root is never committed once the tree has changed shape" \
    's|^    if (root_moved) {|    if (0) {|'

try_fault "the inode is committed even though the tree could not be written" \
    's|^    rc = path_flush(fs, &p, inode_seed);|    rc = path_flush(fs, \&p, inode_seed); rc = EXTW_OK;|'

# ── Defence whose first line is intact ─────────────────────────────────────
try "the refusal to re-read a node that holds changes is dropped" \
    --untestable "not a defect on its own, and worth writing down rather than leaving as
              a puzzle. The guard exists for the case where grow_right_edge stops
              flushing; while that flush is there nothing ever reaches
              find_rightmost_path with a level marked, so removing the check changes
              no behaviour at all. It is caught in combination and not alone, and a
              suite that mutates one thing at a time cannot reach it." \
    's|^        if (p->dirty\[level\]) return EXTW_ERR_FORMAT;|        if (0) return EXTW_ERR_FORMAT;|'

if [ "$fail" -eq 0 ]; then
    echo "RESULT: every mutant was caught"
else
    echo "RESULT: see above"
fi
exit $fail
