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

# Measures runcheck.py against a broken run allocator (#161).
#
#   ./mutants-run.sh
#
# Taking blocks a run at a time fails in three directions, and they are not equally
# loud.
#
#   Hand out what is not marked   The worst thing in this tree. A block an inode
#                                 points at while the disk still calls it free is
#                                 handed to a second file by the next session, and
#                                 that is the one kind of damage e2fsck cannot
#                                 repair without taking something away.
#   Never give the tail back      A leak. Every operation would leave blocks marked
#                                 and owned by nobody, so the volume is permanently
#                                 dirty to e2fsck rather than momentarily so after a
#                                 crash.
#   Go back to one at a time      Perfectly correct, e2fsck-clean, and the whole
#                                 point of #161 undone. Nothing but the write count
#                                 can see it.
#
# All three are below.

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

fail=0

# try <desc> [--untestable <reason>] <sed expr>...
try() {
    local desc="$1"; shift
    local expect_miss=""
    if [ "${1:-}" = "--untestable" ]; then expect_miss="$2"; shift 2; fi
    mutant_reset "$HERE" "$WORK"
    local expr
    for expr in "$@"; do
        sed -i "$expr" "$WORK/ext4_alloc.c"
    done
    if ! mutant_changed "$HERE" "$WORK"; then
        echo "  SKIP  $desc - the pattern did not match, so nothing was mutated"
        fail=1; return
    fi
    if ! mutant_build "$WORK" session.c sess; then
        echo "  SKIP  $desc - mutant did not build"
        fail=1; return
    fi
    if "$HERE/runcheck.py" --mkfs "$HERE/mkfs" --session "$WORK/sess" >/dev/null 2>&1; then
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

echo "mutants of the run allocator in ext4_alloc.c against runcheck.py:"

# ── Handing out what is not marked ─────────────────────────────────────────
try "only the first block of a run is marked, the rest are handed out free" \
    's/^        for (uint32_t k = 0; k < n; k++)                      \/\* 1 \*\//        for (uint32_t k = 0; k < 1; k++)/'

try "the free counts move by one however many blocks the run took" \
    's/group_set_free_blocks(fs, d, group_free_blocks(fs, d) - n);/group_set_free_blocks(fs, d, group_free_blocks(fs, d) - 1);/' \
    's/sb_set_free_blocks(fs, ext4_sb_free_blocks(fs) - n);/sb_set_free_blocks(fs, ext4_sb_free_blocks(fs) - 1);/'

try "a reservation is handed out whatever was asked for" \
    --untestable "not a defect, which is worth writing down rather than leaving as a
              puzzle. A reserved block is marked on disk and owned by nobody, so handing
              it to a different caller is sound - it only puts a metadata block in the
              middle of a data run, and no oracle here judges layout. The goal check
              earns its place on locality, not on safety." \
    's/    if (fs->resv_left > 0 && fs->resv_next == goal) {/    if (fs->resv_left > 0) {/'

try "the run is written to the bitmap after it is handed out, not before" \
    '/^        if (write_bitmap(fs, d)) return ALLOC_CORRUPT;$/d'

# ── Never giving the tail back ─────────────────────────────────────────────
try "the flush does not give back what nobody took" \
    's/^static int release_reservation(ext4_wfs \*fs) {/&\n    if (fs) { fs->resv_left = 0; return 0; }/'

try "the tail is cleared in memory but the bitmap on disk keeps it" \
    '/^static int release_reservation/,/^}/ s|^    if (write_bitmap(fs, d)) return -1;|    /* mutant: the disk keeps the marks */|'

try "a new run is started without giving the old one back" \
    's/^    if (release_reservation(fs)) return -1;$/    \/* mutant: the previous run is abandoned *\//'

# ── Going back to one block at a time ──────────────────────────────────────
try "runs are never longer than one block" \
    's/uint32_t want = (goal != 0 && goal == fs->last_alloc + 1) ? EXT4_ALLOC_RUN : 1;/uint32_t want = 1;/'

try "the run is taken but never kept, so every block is allocated afresh" \
    's/^    if (got > 1) {/    if (0) {/'

if [ "$fail" -eq 0 ]; then
    echo "RESULT: every mutant was caught"
else
    echo "RESULT: see above"
fi
exit $fail
