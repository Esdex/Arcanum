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

# Measures interopcheck.py.
#
#   ./mutants-interop.sh
#
# interopcheck is the only stand that hands the filesystem to another ext4 *driver*
# and lets it mount and write - everything else compares us against e2fsprogs'
# tools. That makes it the one place a shared misreading of the format would show:
# fuse2fs agrees with us only where we are both right, not where we happened to
# assume alike.
#
# So the mutants are things that leave a filesystem our own reader is perfectly
# happy with. If our reader would reject it too, the mutant proves nothing about
# what this stand adds.

set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

. "$HERE/sources.sh"

STAGE="$WORK/ext4"
mkdir -p "$STAGE"
stage() { for f in $EXT4_SOURCES $EXT4_HEADERS; do cp "$EXT4_DIR/$f" "$STAGE/"; done; }
stage

fail=0

try() {
    local desc="$1" file="$2" expr="$3"
    stage
    sed -i "$expr" "$STAGE/$file"
    if cmp -s "$EXT4_DIR/$file" "$STAGE/$file"; then
        echo "  SKIP  $desc - the pattern did not match, so nothing was mutated"
        fail=1; return
    fi
    local SRC=""
    for f in $EXT4_SOURCES; do SRC="$SRC $STAGE/$f"; done
    if ! (cd "$WORK" &&
          cc -O2 -std=c99 -I"$STAGE" -o bn "$HERE/bench.c"    $SRC 2>/dev/null &&
          cc -O2 -std=c99 -I"$STAGE" -o dw "$HERE/dirwrite.c" $SRC 2>/dev/null &&
          cc -O2 -std=c99 -I"$STAGE" -o fm "$HERE/fsmeta.c"   $SRC 2>/dev/null); then
        echo "  SKIP  $desc - mutant did not build"
        fail=1; return
    fi
    if timeout 600 "$HERE/interopcheck.py" --bench "$WORK/bn" --dirwrite "$WORK/dw" \
                   --fsmeta "$WORK/fm" >/dev/null 2>&1; then
        echo "  MISS  $desc - the other driver did not object"; fail=1
    else
        echo "  caught: $desc"
    fi
}

echo "interop mutation tests (each should read caught):"

# A directory block's checksum is seeded per inode. Seeding it with the filesystem
# seed alone produces a block our own writer and our own reader agree on perfectly -
# they make the same mistake in both directions - and that no other driver accepts.
try "directory blocks are checksummed without the inode in the seed" ext4_dirwrite.c \
    's@static uint32_t inode_seed_of(ext4_wfs \*w, const ext4_fs \*r, uint32_t ino) {@static uint32_t inode_seed_of(ext4_wfs *w, const ext4_fs *r, uint32_t ino) { return w->csum_seed;@'

# The same shape one level down, in the path this stand actually walks: adding a
# name splits the gap inside some entry's rec_len. Shrinking the entry in front by
# four bytes too many leaves a chain our own reader follows without complaint - it
# goes where the rec_len says - and that does not add up to the block for anything
# checking the arithmetic.
#
# The first attempt here aimed at the htree rebuild instead, which this stand never
# reaches: its container is made without dir_index, so there is no index to rebuild.
# A mutant in unreached code reads as a harness gap and is not one.
try "the entry in front gives up four bytes too many" ext4_dirwrite.c \
    's@                    wr16(buf + off + 4, (uint16_t)used);   /\* shrink to fit \*/@                    wr16(buf + off + 4, (uint16_t)(used - 4));@'

echo
if [ "$fail" -ne 0 ]; then
    echo "RESULT: the harness has a gap - the other driver accepted what it should not"
    exit 1
fi
echo "RESULT: every mutant was caught"
