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

# Measures freecheck.py against a broken run free (#165).
#
#   ./mutants-free.sh
#
# Giving blocks back a run at a time fails in three directions, and they are not
# equally loud.
#
#   Clear a bit that is still owned  The worst thing here. A block free on disk
#                                    while an inode still points at it is handed
#                                    to a second file by the next session, and
#                                    that is the one damage e2fsck cannot repair
#                                    without taking something away.
#   Leave a bit set                  A leak. The space is gone until a check
#                                    reclaims it, and e2fsck says so.
#   Go back to one at a time         Perfectly correct, e2fsck-clean, and the
#                                    whole point of #165 undone. Nothing but the
#                                    write count can see it.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

. "$HERE/sources.sh"
mutant_stage "$HERE" "$WORK" session.c
cp "$HERE/extwrite.c" "$WORK/"

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
    if ! mutant_build "$WORK" session.c sess || ! mutant_build "$WORK" extwrite.c ew; then
        echo "  SKIP  $desc - mutant did not build"
        fail=1; return
    fi
    if timeout 1800 "$HERE/freecheck.py" --mkfs "$HERE/mkfs" --session "$WORK/sess" \
                    --extwrite "$WORK/ew" >/dev/null 2>&1; then
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

echo "mutants of the run free in ext4_alloc.c against freecheck.py:"

# ── Clearing a bit that is still owned ─────────────────────────────────────
try "the run is not cut at the group boundary it crosses" \
    's|    uint32_t n = (uint64_t)(limit - bit) < count ? limit - bit : (uint32_t)count;|    uint32_t n = (uint32_t)count;|'

try "one block past the end of the run is cleared too" \
    's|^    for (uint32_t k = 0; k < n; k++)\n        fs->bitmap|&|' \
    '/^    for (uint32_t k = 0; k < n; k++)$/,/^        fs->bitmap\[(bit + k) >> 3\] &= (uint8_t)~(1u << ((bit + k) \& 7));$/ s|k < n;|k <= n;|'

# ── Leaving a bit set ──────────────────────────────────────────────────────
try "only the first block of the run is cleared" \
    '/^    for (uint32_t k = 0; k < n; k++)$/,/^        fs->bitmap\[(bit + k) >> 3\] &= (uint8_t)~(1u << ((bit + k) \& 7));$/ s|k < n;|k < 1;|'

try "the bitmap is changed in memory but never written" \
    's|^    if (write_bitmap(fs, d)) return -1;\n    store_bitmap_csum|&|' \
    '/^static int64_t free_in_group/,/^}/ s|^    if (write_bitmap(fs, d)) return -1;$|    /* mutant: the disk keeps the old marks */|'

try "the free counts move by one however many the run gave back" \
    's|    group_set_free_blocks(fs, d, group_free_blocks(fs, d) + n);|    group_set_free_blocks(fs, d, group_free_blocks(fs, d) + 1);|' \
    's|    sb_set_free_blocks(fs, ext4_sb_free_blocks(fs) + n);|    sb_set_free_blocks(fs, ext4_sb_free_blocks(fs) + 1);|'

try "the run stops after its first group instead of carrying on" \
    '/^int ext4_free_run/,/^}/ s|^        start += (uint64_t)n;|        return 0;\n        start += (uint64_t)n;|'

# ── Going back to one block at a time ──────────────────────────────────────
try "the bitmap is written once per freed block again" \
    's|    uint32_t n = (uint64_t)(limit - bit) < count ? limit - bit : (uint32_t)count;|    uint32_t n = 1;|'

# ── The refusal that keeps a half-freed run from happening ─────────────────
try "the run is cleared as it is checked, so a bad one is half freed" \
    '/^    for (uint32_t k = 0; k < n; k++)$/,/^            return -1;                                   \/\* already free \*\/$/ s|^            return -1;.*$|            { }|'

if [ "$fail" -eq 0 ]; then
    echo "RESULT: every mutant was caught"
else
    echo "RESULT: see above"
fi
exit $fail
