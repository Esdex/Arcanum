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

# Measures fsckcheck.py --inodes, not the inode allocator.
#
#   ./mutants-ialloc.sh <cases-dir>
#
# Only ext4_ialloc.c is mutated. fsmeta stays built from pristine sources - it is
# the independent checksum oracle, and a mutant breaking both the same way would
# agree with itself and be reported as a pass.

set -euo pipefail

CASES="${1:?usage: ./mutants-ialloc.sh <cases-dir>}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

. "$HERE/sources.sh"
mutant_stage "$HERE" "$WORK" alloc.c

fail=0
EXTRA=(--inodes 4)

try() {
    local desc="$1" expr="$2" expect_miss="${3:-}"
    mutant_reset "$HERE" "$WORK"
    sed -i "$expr" "$WORK/ext4_ialloc.c"
    if ! mutant_changed "$HERE" "$WORK"; then
        echo "  SKIP  $desc - the pattern did not match, so nothing was mutated"
        fail=1
        return
    fi
    if ! mutant_build "$WORK" alloc.c am; then
        echo "  SKIP  $desc - mutant did not build"
        fail=1
        return
    fi
    if "$HERE/fsckcheck.py" --cases "$CASES" --alloc "$WORK/am" \
                            --fsmeta "$HERE/fsmeta" "${EXTRA[@]}" \
                            >/dev/null 2>&1; then
        if [ -n "$expect_miss" ]; then
            echo "  untestable: $desc"
            echo "              $expect_miss"
        else
            echo "  MISS  $desc - the harness did not catch it"
            fail=1
        fi
    else
        if [ -n "$expect_miss" ]; then
            echo "  UNEXPECTED CATCH: $desc was marked untestable but the harness caught it"
            fail=1
        else
            echo "  caught: $desc"
        fi
    fi
}

echo "inode allocator mutation tests (each should read caught, or untestable with a reason):"

# The counters and checksums, the same five-step chain the block allocator has.

try "superblock free inode count never decremented" \
    's@            sb_set_free_inodes(fs, ext4_sb_free_inodes(fs) - 1);@@'

try "group free inode count never decremented" \
    's@            group_set_free_inodes(fs, d, group_free_inodes(fs, d) - 1);@@'

try "inode bitmap checksum not recomputed" \
    's@            store_inode_bitmap_csum(fs, d, bitmap);@@'

try "descriptor checksum not recomputed" \
    's@            store_desc_csum(fs, g, d);@@'

try "descriptor checksum computed before the free count is updated" \
    's@            group_set_free_inodes(fs, d, group_free_inodes(fs, d) - 1);@            store_desc_csum(fs, g, d); group_set_free_inodes(fs, d, group_free_inodes(fs, d) - 1);@; s@            store_desc_csum(fs, g, d);$@@'

# The bitmap itself.

try "already-set bits handed out again" \
    's@            if (bitmap.i >> 3. & (1u << (i \& 7))) continue;@@'

try "inode numbers reported zero-based" \
    's@            result = (int64_t)(g \* ipg + i + 1);   /\* inode numbers start at 1 \*/@            result = (int64_t)(g * ipg + i);@'

# bg_itable_unused, which blocks have no equivalent of. Most images in the corpus
# hand out their very first inode from inside the never-used tail, so these are
# reached immediately rather than needing a large run.

try "never-used tail not shortened when allocating into it" \
    's@                group_set_itable_unused(fs, d, ipg - (i + 1));@@'

try "tail shortened by one inode too few" \
    's@                group_set_itable_unused(fs, d, ipg - (i + 1));@                group_set_itable_unused(fs, d, ipg - i);@'

# Filling the image. Four inodes never leave the first group with room in it, so
# the rule about INODE_UNINIT groups is unexercised until everything ahead of them
# is taken - the same reachability problem BLOCK_UNINIT had on the block side.

EXTRA=(--ifill --limit 4)

try "an INODE_UNINIT group is skipped instead of being initialised" \
    's@            if (init_inode_group(fs, g, d, bitmap)) continue;@            continue;@'

try "allocation stops one group early" \
    's@    for (uint32_t g = 0; g < fs->groups; g++) {@    for (uint32_t g = 0; g + 1 < fs->groups; g++) {@'

# Building the bitmap. Without the zeroing, `bitmap` still holds the last group
# read - so inodes read as taken that were never taken, and the checksum stamped
# over it does not describe what went to disk.

try "the rebuilt inode bitmap is not zeroed" \
    's@    memset(buf, 0, ipg / 8);@@'

try "the group is left flagged after its bitmap is built" \
    's@         (uint16_t)(rd16(d + EXT4_GD_FLAGS_OFF) \& ~EXT4_BG_INODE_UNINIT));@         (uint16_t)rd16(d + EXT4_GD_FLAGS_OFF));@'

# Reached because mke2fs leaves the last group INODE_UNINIT, and e2fsck checks the
# padding at the end of that group's bitmap block - "Padding at end of inode bitmap
# is not set". Neither the bitmap checksum nor the free counts cover those bits.
try "the padding past the group's own inodes is not marked in the bitmap block" \
    's@    for (uint32_t bit = ipg; bit < fs->block_size \* 8u; bit++)@    for (uint32_t bit = ipg; bit < ipg; bit++)@'

EXTRA=(--inodes 4)

# The free path, reached only by the round trip.

try "free does not clear the bit" \
    's@    bitmap.i >> 3. \&= (uint8_t)~(1u << (i \& 7));@@'

try "free does not give the inode back to the group count" \
    's@    group_set_free_inodes(fs, d, group_free_inodes(fs, d) + 1);@@'

try "free does not give the inode back to the superblock count" \
    's@    sb_set_free_inodes(fs, ext4_sb_free_inodes(fs) + 1);@@'

echo
if [ "$fail" -ne 0 ]; then
    echo "RESULT: the harness has a gap - a broken allocator passed it"
    exit 1
fi
echo "RESULT: every testable mutant was caught"
