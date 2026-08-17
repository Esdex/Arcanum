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

# Measures bigcheck.py against arithmetic that only goes wrong past 4 GB.
#
#   ./mutants-big.sh
#
# Both mutants truncate a 64-bit byte offset to 32 bits, which is the whole failure
# mode this stand exists for: every offset in the library is computed in uint64_t
# and every one of them was, until #147, only ever exercised below 300 MB. A
# truncation is invisible on a small image - the top bits are zero - so on the rest
# of this directory's corpus both of these mutants pass everything.
#
# The images are sparse and built by mke2fs, not by us, so they are made once and
# reused for every mutant. bigcheck works on a copy and stamps a fresh mtime each
# run, so a cached image cannot make a mutant that writes nowhere look correct -
# that is checked, not assumed.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
IMAGES="$(mktemp -d)"
trap 'rm -rf "$WORK" "$IMAGES"' EXIT

. "$HERE/sources.sh"
mutant_stage "$HERE" "$WORK" extwrite.c
cp "$HERE/bench.c" "$HERE/alloc.c" "$WORK/"

fail=0

# Build the images once with pristine tools, and check the stand is green before
# any mutant runs. A stand that was already failing would report every mutant as
# caught and mean nothing.
echo "building the 5 GB images and checking the stand is green first..."
if ! "$HERE/bigcheck.py" --keep "$IMAGES" >/dev/null 2>&1; then
    echo "  the stand does not pass on unmutated sources - fix that first"
    exit 1
fi

try() {
    local desc="$1" file="$2" expr="$3"
    mutant_reset "$HERE" "$WORK"
    sed -i "$expr" "$WORK/$file"
    if ! mutant_changed "$HERE" "$WORK"; then
        echo "  SKIP  $desc - the pattern did not match, so nothing was mutated"
        fail=1; return
    fi
    if ! (cd "$WORK" && cc -O2 -std=c99 -o bench bench.c $EXT4_SOURCES 2>/dev/null &&
                        cc -O2 -std=c99 -o alloc alloc.c $EXT4_SOURCES 2>/dev/null &&
                        cc -O2 -std=c99 -o extwrite extwrite.c $EXT4_SOURCES 2>/dev/null); then
        echo "  SKIP  $desc - mutant did not build"
        fail=1; return
    fi
    if "$HERE/bigcheck.py" --keep "$IMAGES" --bench "$WORK/bench" \
                           --alloc "$WORK/alloc" --extwrite "$WORK/extwrite" \
                           >/dev/null 2>&1; then
        echo "  MISS  $desc - the harness did not catch it"; fail=1
    else
        echo "  caught: $desc"
    fi
}

echo "large-offset mutation tests (each should read caught):"

# Where an inode lives. This is the one that reaches the device: the app's write
# path goes through ext4_io, and inode_offset is the only place a byte offset is
# narrowed on the way there.
try "the inode's byte offset is truncated to 32 bits" ext4_extwrite.c \
    's@    \*out = (off_t)(itable \* fs->block_size + (uint64_t)index \* fs->inode_size);@    *out = (off_t)(uint32_t)(itable * fs->block_size + (uint64_t)index * fs->inode_size);@'

# Where a block lives, in the host file backend. Host-only - the device supplies
# its own callbacks - but it is the same arithmetic, and it is what the whole
# harness reads and writes through, so a truncation here silently invalidates
# every other stand the moment an image grows past 4 GB.
try "the host backend's block offset is truncated to 32 bits" ext4_alloc.c \
    's@    off_t at = (off_t)block \* block_size;@    off_t at = (off_t)(uint32_t)(block * block_size);@g'

echo
if [ "$fail" -ne 0 ]; then
    echo "RESULT: the harness has a gap - a truncated offset passed it"
    exit 1
fi
echo "RESULT: every mutant was caught"
