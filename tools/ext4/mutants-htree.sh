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

# Measures htreecheck.py, not the rebuild.
#
#   ./mutants-htree.sh
#
# Rebuilding a hash-indexed directory as a linear one (#141) is a whole directory
# rewritten in place, and its failures are quiet ones: an entry left behind in a
# block the rebuild stopped short of is a duplicate name, an entry dropped is a
# file that no longer exists, and a chain that does not add up to the block is
# space gone for good. None of the three is a checksum failure, because every
# block is restamped either way. e2fsck sees all of them, which is why it is the
# judge here and our own reader never is.
#
# The images are built once and reused by every mutant: they take an mke2fs and an
# `e2fsck -fyD` each, and rebuilding them per mutant would cost more than the
# mutants do. Nothing under test is involved in building them.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

. "$HERE/sources.sh"
mutant_stage "$HERE" "$WORK" dirwrite.c
# Two drivers, because the rebuild has three entry points and one of them - the
# "..' rewrite a move does - is only reachable through rename.
cp "$HERE/rename.c" "$WORK/"

IMAGES="$WORK/images"
mkdir -p "$IMAGES"

fail=0

echo "building the indexed-directory images and checking the pristine tools first"
if ! "$HERE/htreecheck.py" --images "$IMAGES" >"$WORK/base.log" 2>&1; then
    echo "  the unmutated tools do not pass htreecheck.py - nothing below means"
    echo "  anything until that is fixed:"
    sed 's/^/    /' "$WORK/base.log"
    exit 1
fi

# try <description> <sed-script> [expect-miss-reason]
try() {
    local desc="$1" expr="$2" expect_miss="${3:-}"
    mutant_reset "$HERE" "$WORK"
    sed -i "$expr" "$WORK/ext4_dirwrite.c"
    if ! mutant_changed "$HERE" "$WORK"; then
        echo "  SKIP  $desc - the pattern did not match, so nothing was mutated"
        fail=1
        return
    fi
    if ! mutant_build "$WORK" dirwrite.c dw; then
        echo "  SKIP  $desc - mutant did not build"
        fail=1
        return
    fi
    # The rename driver links the same library, so a mutant has to be built into
    # it too or the "..' path would be judged on pristine code.
    if ! mutant_build "$WORK" rename.c rn; then
        echo "  SKIP  $desc - the rename driver did not build"
        fail=1
        return
    fi
    if "$HERE/htreecheck.py" --images "$IMAGES" --bench "$HERE/bench" \
                             --dirwrite "$WORK/dw" --rename "$WORK/rn" \
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

echo "hash-indexed directory mutation tests (each should read caught, or untestable with a reason):"

# Whether the rebuild happens at all. The first is the behaviour this replaced.

try "a hash-indexed directory is refused instead of being rebuilt" \
    's@    if (!is_htree(dir)) return EXT4_DIRW_OK;@    if (is_htree(dir)) return EXT4_DIRW_ERR_HTREE;@'

try "the directory is written to without being rebuilt first" \
    's@    if (!is_htree(dir)) return EXT4_DIRW_OK;@    return EXT4_DIRW_OK;@'

# The flag. Two halves: clearing it in the inode, and that inode reaching disk.

try "the INDEX flag is left set" \
    's@         rd32(dir + INODE_FLAGS_OFF) \& ~EXT4_INODE_FLAG_INDEX);@         rd32(dir + INODE_FLAGS_OFF));@'

try "the inode is never written back, so the cleared flag never reaches disk" \
    's@    if (ext4_write_inode_raw(w, dir_ino, dir) != EXTW_OK) {@    if (0) {@'

# What the rebuild reads. Everything past the root lives in the leaves, and an
# indexed directory keeps its leaves anywhere but the first block.

try "only the first block of the old directory is read" \
    's@        int prc = read_dir_block(w, r, dir, b, src, \&phys);@        int prc = (b == 0) ? read_dir_block(w, r, dir, b, src, \&phys) : EXT4_DIRW_ERR_NOROOM;@'

try "the blocks the rebuild no longer needs are left holding the old leaves" \
    's@        for (uint32_t b = out_index + 1; b < blocks; b++) {@        for (uint32_t b = blocks; b < blocks; b++) {@'

# What it writes. The chain in each rebuilt block has to add up to exactly the
# block, and every entry has to arrive whole.

try "the last entry in a rebuilt block does not reach the end of it" \
    's@        wr16(dst + last_off + 4, (uint16_t)(limit - last_off));@@'

try "a rebuilt block's chain is allowed to run into the checksum tail" \
    's@    const uint32_t limit = with_tail ? w->block_size - DIR_TAIL_SIZE@    const uint32_t limit = with_tail ? w->block_size@'

try "a rebuilt block is written without a checksum tail" \
    's@    if (with_tail) ext4_dir_stamp_tail(dst, w->block_size, seed);@@'

try "entry records not rounded up to four bytes" \
    's@                    wr16(dst + used + 4, (uint16_t)need);@                    wr16(dst + used + 4, (uint16_t)(DIRENT_HEADER + nlen));@'

try "the file type is dropped when an entry is moved" \
    's@                    dst\[used + 7\] = ftype;@                    dst[used + 7] = 0;@'

try "the name is not copied with the entry" \
    's@                    memcpy(dst + used + DIRENT_HEADER, src + off + DIRENT_HEADER, nlen);@@'

try "room is reserved for a checksum tail on a volume that has none" \
    's@    const int with_tail = r->has_metadata_csum;@    const int with_tail = 1;@' \
    "Twelve bytes a block spent on a tail nothing reads. e2fsck does not object -
              without metadata_csum those bytes are a dead entry, which is exactly what a
              block full of removed names looks like - and the volume has room, so no
              rebuild here is pushed past what it found. It is waste, not corruption, and
              the harness is right not to fail it. What would show is a directory arriving
              packed tight enough that the shortfall costs a whole block, and nothing
              available builds one: e2fsck -D fills its leaves to about 80 percent."

# The shape the rebuild is willing to work on. Both are reached by an image whose
# first block has been broken on purpose, and both are checked by the image having
# to come back byte-for-byte identical.

try "the head of the directory is taken on trust" \
    's@if (seen == 0 @if (0 @'

try "the second entry is not required to be '..'" \
    's@if (seen == 1 @if (0 @'

try "the rebuild is written without being measured first" \
    's@    int rc = flatten_pass(w, r, dir, blocks, seed, src, NULL, \&need);@    int rc = EXT4_DIRW_OK;@'

try "the output is allowed to overtake the input" \
    's@                    if (out_index > b) return EXT4_DIRW_ERR_NOROOM;@@' \
    "This fires only if the rebuilt directory needs more blocks than the one it
              replaces, and it cannot: the root and every interior node give up a whole
              block each, and the packing has no gaps where the source had plenty. Every
              image here finishes with tens of blocks to spare - the deep one uses 178 of
              218. It is the guard that makes writing over the blocks still being read
              safe, and it is held up by that argument rather than by any image."

echo
if [ "$fail" -ne 0 ]; then
    echo "RESULT: the harness has a gap - a broken rebuild passed it"
    exit 1
fi
echo "RESULT: every testable mutant was caught"
