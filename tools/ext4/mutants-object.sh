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

# Measures objectcheck.py against the three defects it was written for (#147).
#
#   ./mutants-object.sh
#
# Each mutant puts back one of the assumptions that held while the only things this
# driver had ever met were the regular files and directories it makes itself:
# that anything being deleted owns data blocks, that an inode owns nothing but its
# blocks, and that a directory entry is either a directory or a file.
#
# All three shipped, and every other stand in this directory passes with all three
# in place - the corpus has nothing else in it to notice with.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

. "$HERE/sources.sh"
mutant_stage "$HERE" "$WORK" dirwrite.c
cp "$HERE/rename.c" "$HERE/bench.c" "$WORK/"

fail=0

try() {
    local desc="$1" expr="$2"
    mutant_reset "$HERE" "$WORK"
    sed -i "$expr" "$WORK/ext4_create.c"
    if ! mutant_changed "$HERE" "$WORK"; then
        echo "  SKIP  $desc - the pattern did not match, so nothing was mutated"
        fail=1; return
    fi
    if ! (cd "$WORK" && cc -O2 -std=c99 -o dw dirwrite.c $EXT4_SOURCES 2>/dev/null &&
                        cc -O2 -std=c99 -o rn rename.c   $EXT4_SOURCES 2>/dev/null &&
                        cc -O2 -std=c99 -o bn bench.c    ext4_extents.c ext4_dir.c ext4_csum.c 2>/dev/null); then
        echo "  SKIP  $desc - mutant did not build"
        fail=1; return
    fi
    if "$HERE/objectcheck.py" --dirwrite "$WORK/dw" --rename "$WORK/rn" \
                             --bench "$WORK/bn" >/dev/null 2>&1; then
        echo "  MISS  $desc - the harness did not catch it"; fail=1
    else
        echo "  caught: $desc"
    fi
}

echo "foreign-object mutation tests (each should read caught):"

# Truncation is asked to walk an extent tree that a device node, a FIFO, a socket
# or a short symlink does not have. It fails, and unlink has already taken the name
# out of the directory - so the entry is gone and the inode is stranded.
try "everything is assumed to own data blocks" \
    's@    case EXT4_S_IFCHR:@    case 0xFFFF: /* unreachable */@'

# The attribute block stays marked in use with nothing referring to it.
try "the external attribute block is not given back" \
    's@    release_xattr_block(w, inode, ino);@@'

# The entry and the inode end up disagreeing about what the thing is.
try "every moved entry is typed as a regular file" \
    's@    case EXT4_S_IFLNK:  return EXT4_FT_SYMLINK;@    case EXT4_S_IFLNK:  return EXT4_FT_REG_FILE;@'

echo
if [ "$fail" -ne 0 ]; then
    echo "RESULT: the harness has a gap - a foreign object was mishandled unnoticed"
    exit 1
fi
echo "RESULT: every mutant was caught"
