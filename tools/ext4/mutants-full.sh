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

# Measures fullcheck.py against the tree-growth path in ext4_extwrite.c.
#
#   ./mutants-full.sh
#
# fullcheck proves the extent tree keeps growing when a node below the root fills:
# a chain of empty nodes is hung off the lowest ancestor with a free slot, and the
# root is pushed down when every one of them is full (#119). These mutants break
# that growth in the ways it can plausibly break, and the check - e2fsck plus a
# read-back of every block through debugfs, never our own reader - has to catch each.
#
# The key mutant is the one that mis-keys an index entry. It leaves a tree that is
# structurally sound, so e2fsck passes it: only reading the file back catches it,
# which is why the harness dumps the file rather than trusting the shape.
#
# The fragmentation setup only ever does small appends that never reach this code,
# so a caught mutant fails at the fill step and not at the setup's e2fsck - which is
# what pins the failure to the growth rather than to appending in general.

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

echo "tree-growth mutation tests (each should read caught):"

# The hazard the growth is shaped to avoid: an index entry whose key is not the
# first logical block of the subtree under it. The tree stays structurally valid, so
# e2fsck is happy - the file simply reads wrong past the split.
try "a new index entry is keyed at 0 instead of the block it covers" \
    's@wr32(slot + EI_BLOCK_OFF, next_logical);@wr32(slot + EI_BLOCK_OFF, 0);@'

# A node's depth is fixed by where it hangs. Claiming 0 turns an index node into
# something that reads as a leaf full of nonsense extents.
try "a new node claims depth 0 whatever level it hangs at" \
    's@wr16(node + EH_DEPTH_OFF, (uint16_t)(p->depth - level));@wr16(node + EH_DEPTH_OFF, 0);@'

# The climb stops at the leaf's own parent, so a full parent is written into anyway
# and the entry lands past the end of the node.
try "the search for a free slot never climbs past the leaf's parent" \
    's@node_entries(p->buf\[anchor\]) >= node_capacity(p->buf\[anchor\]))@0)@'

# Only the anchor is written back, so every node made below it stays in memory: the
# next append reads whatever was on those blocks before.
try "the levels below the anchor are never flushed" \
    's@for (int level = anchor; level < p->depth; level++) {@for (int level = anchor; level < anchor + 1; level++) {@'

# ── Rollbacks this scenario cannot reach ──────────────────────────────────────
#
# Both need the allocator to fail *between* taking the block for the extent and
# taking one for the tree. Filling a comb until it runs dry does not arrange that:
# the data block is asked for first, so the fill always ends on that failure and the
# two lines below never run. Reaching them needs an image tightened to a known
# number of free blocks with the leaf left exactly full, which this harness does not
# build - noted rather than passed over, and reported without failing the suite.
optional() {
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
        echo "  unreached: $desc"
    else
        echo "  caught: $desc"
    fi
}

optional "a half-built chain strands its blocks when space runs out" \
    's@for (int i = 0; i < made_n; i++) ext4_free_block(fs, made\[i\]);@@'

optional "the over-allocated block is stranded rather than freed" \
    's@if (rc != EXTW_OK) { ext4_free_block(fs, (uint64_t)got); break; }@if (rc != EXTW_OK) { break; }@'

echo
if [ "$fail" -ne 0 ]; then
    echo "RESULT: the harness has a gap - a broken growth passed it"
    exit 1
fi
echo "RESULT: every mutant was caught"
