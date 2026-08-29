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

# Measures wtimecheck.py against a broken s_wtime (#156).
#
#   ./mutants-wtime.sh
#
# The field is one line of code and every way of getting it wrong is quiet: written at
# the wrong offset it lands in another field, written before the checksum it leaves a
# superblock that disagrees with itself, written unconditionally it puts 1970 on every
# volume the host tools touch and makes every other stand non-deterministic.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

. "$HERE/sources.sh"
mutant_stage "$HERE" "$WORK" alloc.c

fail=0

try() {
    local desc="$1" file="$2" expr="$3"
    mutant_reset "$HERE" "$WORK"
    sed -i "$expr" "$WORK/$file"
    if ! mutant_changed "$HERE" "$WORK"; then
        echo "  SKIP  $desc - the pattern did not match, so nothing was mutated"
        fail=1; return
    fi
    if ! mutant_build "$WORK" alloc.c am; then
        echo "  SKIP  $desc - mutant did not build"
        fail=1; return
    fi
    if "$HERE/wtimecheck.py" --alloc "$WORK/am" >/dev/null 2>&1; then
        echo "  MISS  $desc - the stand did not catch it"; fail=1
    else
        echo "  caught: $desc"
    fi
}

echo "mutants of the superblock write time against wtimecheck.py:"

try "the time is stamped after the checksum, so the two disagree" ext4_alloc.c \
    '/if (fs->now) wr32(fs->sb + EXT4_SB_WTIME_OFF, fs->now);/d; s/^    wr32(fs->sb + EXT4_SB_CSUM_OFF, ext4_superblock_csum(fs->sb));/&\n    if (fs->now) wr32(fs->sb + EXT4_SB_WTIME_OFF, fs->now);/'

try "the time goes to the wrong offset" ext4_alloc.c \
    's/wr32(fs->sb + EXT4_SB_WTIME_OFF, fs->now)/wr32(fs->sb + EXT4_SB_WTIME_OFF + 4, fs->now)/'

try "stamped even when no clock was supplied" ext4_alloc.c \
    's/    if (fs->now) wr32(fs->sb + EXT4_SB_WTIME_OFF, fs->now);/    wr32(fs->sb + EXT4_SB_WTIME_OFF, fs->now);/'

try "the mount time is written as well" ext4_alloc.c \
    's/    if (fs->now) wr32(fs->sb + EXT4_SB_WTIME_OFF, fs->now);/&\n    if (fs->now) wr32(fs->sb + 0x2C, fs->now);/'

try "nothing is stamped at all" ext4_alloc.c \
    's/    if (fs->now) wr32(fs->sb + EXT4_SB_WTIME_OFF, fs->now);//'

if [ "$fail" -eq 0 ]; then
    echo "RESULT: every mutant was caught"
else
    echo "RESULT: see above"
fi
exit $fail
