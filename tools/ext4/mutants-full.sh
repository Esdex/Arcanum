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

# Measures fullcheck.py against the commit-on-EXTW_ERR_FULL path in ext4_extwrite.c.
#
#   ./mutants-full.sh
#
# fullcheck proves that when the append is refused with EXTW_ERR_FULL the partial
# write it did place is left e2fsck-clean - the same "a short write is committed,
# not abandoned" property the out-of-space path has, on a different bail. These
# mutants break that commit in the two ways it can go wrong, and the post-refusal
# e2fsck (never our own reader) has to catch each.
#
# Both are specific to the FULL bail: the fragmentation setup only ever does sub-cap
# appends, which never reach this code, so a caught mutant fails at the fill step's
# e2fsck and not at the setup's - which is what pins the failure to the FULL commit
# rather than to appending in general.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

. "$HERE/sources.sh"
mutant_stage "$HERE" "$WORK" fullwrite.c

fail=0

try() {
    local desc="$1" expr="$2"
    mutant_reset "$HERE" "$WORK"
    sed -i "$expr" "$WORK/ext4_extwrite.c"
    if ! mutant_changed "$HERE" "$WORK"; then
        echo "  SKIP  $desc - the pattern did not match, so nothing was mutated"
        fail=1; return
    fi
    if ! (cd "$WORK" && cc -O2 -std=c99 -o fw fullwrite.c $EXT4_SOURCES 2>/dev/null); then
        echo "  SKIP  $desc - mutant did not build"
        fail=1; return
    fi
    if "$HERE/fullcheck.py" --fullwrite "$WORK/fw" >/dev/null 2>&1; then
        echo "  MISS  $desc - the harness did not catch it"; fail=1
    else
        echo "  caught: $desc"
    fi
}

echo "commit-on-EXTW_ERR_FULL mutation tests (each should read caught):"

# The partial write is not committed at all on the FULL bail: the inode, its size
# and block counts, and the free counts are never written back, while the blocks the
# loop placed are on disk and marked in use - orphan blocks e2fsck reports.
try "the FULL bail skips committing its partial write entirely" \
    's@int append_rc = rc;@int append_rc = rc; if (append_rc == EXTW_ERR_FULL) goto out;@'

# The block allocated for the extent that could not be linked is not given back when
# the tree refuses to grow, so it is marked in use but attached to no inode - a
# single orphan block, the subtle version of the same fault.
try "the over-allocated block is stranded rather than freed when the tree is full" \
    's@if (rc != EXTW_OK) { ext4_free_block(fs, (uint64_t)got); break; }@if (rc != EXTW_OK) { break; }@'

echo
if [ "$fail" -ne 0 ]; then
    echo "RESULT: the harness has a gap - a broken FULL bail passed it"
    exit 1
fi
echo "RESULT: every mutant was caught"
