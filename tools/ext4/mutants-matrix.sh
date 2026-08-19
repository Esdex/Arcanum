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

# Measures matrixcheck.py.
#
#   ./mutants-matrix.sh
#
# matrixcheck sweeps the shapes somebody else's mke2fs makes, one feature at a time.
# Every mutant here is a field derived from the superblock in a way that is right
# for the common volume and wrong for one of those shapes - which is what the whole
# stand exists to notice, and what the corpus every other stand uses cannot.
#
# The first is the worst defect the #147 audit found: writing to a foreign container
# corrupted it so completely that fuse2fs then refused to mount it, and our own
# checksum oracle repeated the same mistake and so agreed the image was fine.

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
    # fsmeta is built from the staged sources too. That is deliberate: it is the
    # oracle whose independence this suite is partly about, and building it pristine
    # would hide a mutant that both sides share - which is precisely what happened
    # with the checksum seed.
    if ! (cd "$WORK" &&
          cc -O2 -std=c99 -I"$STAGE" -o bn "$HERE/bench.c"      $SRC 2>/dev/null &&
          cc -O2 -std=c99 -I"$STAGE" -o dw "$HERE/dirwrite.c"   $SRC 2>/dev/null &&
          cc -O2 -std=c99 -I"$STAGE" -o fm "$HERE/fsmeta.c"     $SRC 2>/dev/null &&
          cc -O2 -std=c99 -I"$STAGE" -o cw "$HERE/chunkwrite.c" $SRC 2>/dev/null); then
        echo "  SKIP  $desc - mutant did not build"
        fail=1; return
    fi
    if timeout 900 "$HERE/matrixcheck.py" --bench "$WORK/bn" --dirwrite "$WORK/dw" \
                   --fsmeta "$WORK/fm" --chunkwrite "$WORK/cw" >/dev/null 2>&1; then
        echo "  MISS  $desc - the sweep did not notice"; fail=1
    else
        echo "  caught: $desc"
    fi
}

echo "interop-matrix mutation tests (each should read caught):"

# The seed every metadata checksum is computed from. s_checksum_seed only holds it
# when metadata_csum_seed is on; without that feature the field is not maintained
# and the seed is the crc32c of the UUID. Reading it unconditionally is correct for
# every volume that has the feature and silently destroys one that does not.
try "the checksum seed is read whether or not it is maintained" ext4_alloc.c \
    's@    if (rd32(fs->sb + EXT4_SB_FEATURE_INCOMPAT_OFF) \& EXT4_FEATURE_INCOMPAT_CSUM_SEED)@    if (1)@'

# Descriptors are 32 bytes unless the 64BIT feature widens them. Assuming the
# narrow form is right for a `-O ^64bit` volume and wrong for every other row.
try "group descriptors are assumed to be 32 bytes" ext4_alloc.c \
    's@    fs->desc_size = (incompat \& EXT4_FEATURE_INCOMPAT_64BIT)@    fs->desc_size = (0)@'

echo
if [ "$fail" -ne 0 ]; then
    echo "RESULT: the harness has a gap - a shape it sweeps was mishandled unnoticed"
    exit 1
fi
echo "RESULT: every mutant was caught"
