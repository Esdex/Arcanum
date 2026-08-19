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

# Measures asancheck.sh.
#
#   ./mutants-asan.sh
#
# asancheck exists for the class of defect no other stand can see: one where the
# image that comes out is perfectly correct and the damage was done to memory on
# the way. #146 was exactly that - a 256-byte copy into a 128-byte allocation whose
# resulting filesystem passed e2fsck every time, because write_inode only ever put
# inode_size bytes on disk.
#
# So every mutant here is a buffer overrun that leaves a *valid* image behind. If a
# mutant were caught by e2fsck it would prove nothing about this stand.

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
    if EXT4_DIR="$STAGE" timeout 600 "$HERE/asancheck.sh" >/dev/null 2>&1; then
        echo "  MISS  $desc - the sanitizer run did not notice"; fail=1
    else
        echo "  caught: $desc"
    fi
}

echo "sanitizer mutation tests (each should read caught):"

# #146 itself. The source is a fixed EXT4_MAX_INODE_SIZE buffer and the destination
# is the inode's real size, so on the 128-byte-inode rows this writes 128 bytes past
# the end of the allocation - and the image it produces is still correct, which is
# the whole point of having a stand that watches memory rather than output.
try "the dead inode is copied without bounding it" ext4_create.c \
    's@    memcpy(dead, inode, keep);@    memcpy(dead, inode, sizeof(inode));@g'

# The other direction: a buffer smaller than the inode it will be written back
# from. The read into it clamps, so nothing goes wrong there; the overrun is in
# ext4_write_inode_raw, which writes fs->inode_size bytes out of it. Reached on the
# indexed-directory row, where the rebuild hands that buffer straight to it.
try "a directory inode buffer is smaller than an inode" ext4_dirwrite.c \
    's@    uint8_t dir\[EXT4_MAX_INODE_SIZE\];@    uint8_t dir[128];@g'

echo
if [ "$fail" -ne 0 ]; then
    echo "RESULT: the harness has a gap - an overrun that leaves a valid image passed"
    exit 1
fi
echo "RESULT: every mutant was caught"
