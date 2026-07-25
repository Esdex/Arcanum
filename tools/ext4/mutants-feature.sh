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

# Measures featurecheck.py against the feature-allowlist gates at open time.
#
#   ./mutants-feature.sh
#
# The gates live in two places - the reader's INCOMPAT check in ext4_extents.c and
# the writer's INCOMPAT+RO_COMPAT check in ext4_alloc.c - and featurecheck drives
# them through bench (reader) and alloc (writer-only). Each mutant removes one gate;
# the stand has to notice that a filesystem with an unsupported feature was then let
# in, because letting one in is how a foreign container gets silently corrupted.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

. "$HERE/sources.sh"
mutant_stage "$HERE" "$WORK" bench.c
cp "$HERE/alloc.c" "$WORK/"

fail=0

try() {
    local desc="$1" file="$2" expr="$3"
    mutant_reset "$HERE" "$WORK"
    sed -i "$expr" "$WORK/$file"
    if ! mutant_changed "$HERE" "$WORK"; then
        echo "  SKIP  $desc - the pattern did not match, so nothing was mutated"
        fail=1; return
    fi
    if ! (cd "$WORK" && cc -O2 -std=c99 -o bench bench.c $EXT4_SOURCES 2>/dev/null &&
                        cc -O2 -std=c99 -o alloc alloc.c $EXT4_SOURCES 2>/dev/null); then
        echo "  SKIP  $desc - mutant did not build"
        fail=1; return
    fi
    if "$HERE/featurecheck.py" --bench "$WORK/bench" --alloc "$WORK/alloc" >/dev/null 2>&1; then
        echo "  MISS  $desc - the harness did not catch it"; fail=1
    else
        echo "  caught: $desc"
    fi
}

echo "feature-allowlist mutation tests (each should read caught):"

# The reader stops refusing an unknown INCOMPAT bit, so meta_bg/inline_data/encrypt
# images are read as if understood.
try "the reader accepts any INCOMPAT feature" ext4_extents.c \
    's@if (incompat & ~EXT4_SUPPORTED_INCOMPAT) return EXT4_ERR_FORMAT;@@'

# The writer stops refusing an unknown INCOMPAT bit (the && short-circuits that half
# of the condition; the ro_compat half still stands), so a meta_bg image is written.
try "the writer stops refusing an unknown INCOMPAT feature" ext4_alloc.c \
    's@if ((incompat & ~EXT4_SUPPORTED_INCOMPAT) ||@if (0 \&\& (incompat \& ~EXT4_SUPPORTED_INCOMPAT) ||@'

# The writer stops refusing an unknown RO_COMPAT bit, so it would allocate on a
# bigalloc/quota/verity container that it must only ever read.
try "the writer stops refusing an unknown RO_COMPAT feature" ext4_alloc.c \
    's@(ro_compat & ~EXT4_SUPPORTED_RO_COMPAT)) {@(ro_compat \& 0)) {@'

echo
if [ "$fail" -ne 0 ]; then
    echo "RESULT: the harness has a gap - an unsupported feature passed the gate"
    exit 1
fi
echo "RESULT: every mutant was caught"
