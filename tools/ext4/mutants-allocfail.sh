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

# Measures allocfailcheck.py against the branches that only run out of memory.
#
#   ./mutants-allocfail.sh
#
# allocfailcheck found no defect, which is worth exactly nothing until a broken
# version of one of those branches is shown to fail it. Every mutant here breaks a
# `if (!p)` check - the only code an allocation failure reaches - in a different
# way: dereferencing the null, and carrying on as though the allocation had worked.
#
# These are also the mutants that prove the sweep reaches the branches at all. A
# stand that never made an allocation fail would report the same green.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

. "$HERE/sources.sh"
mutant_stage "$HERE" "$WORK" faultop.c

fail=0

# try <description> <file> <sed> [why-it-cannot-be-caught]
try() {
    local desc="$1" file="$2" expr="$3" expect_miss="${4:-}"
    mutant_reset "$HERE" "$WORK"
    sed -i "$expr" "$WORK/$file"
    if ! mutant_changed "$HERE" "$WORK"; then
        echo "  SKIP  $desc - the pattern did not match, so nothing was mutated"
        fail=1; return
    fi
    # Same --wrap flags the real build uses; without them the mutant would count no
    # allocations and the stand would pass it for the wrong reason.
    if ! (cd "$WORK" && cc -O2 -std=c99 -Wl,--wrap=malloc,--wrap=calloc,--wrap=realloc \
                           -o fo faultop.c $EXT4_SOURCES 2>/dev/null); then
        echo "  SKIP  $desc - mutant did not build"
        fail=1; return
    fi
    if "$HERE/allocfailcheck.py" --faultop "$WORK/fo" >/dev/null 2>&1; then
        if [ -n "$expect_miss" ]; then
            echo "  untestable: $desc"
            echo "              $expect_miss"
        else
            echo "  MISS  $desc - the harness did not catch it"; fail=1
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

echo "out-of-memory mutation tests (each should read caught):"

# The inode buffer every append, truncate and set_size starts with. Without the
# check the very next line writes through a null pointer.
try "a failed inode allocation is used anyway" ext4_extwrite.c \
    's@    if (!inode || !block || !storage) {@    if (0) {@'

# The block buffer the directory writer formats a new block in.
try "a failed block allocation is used anyway" ext4_dirwrite.c \
    's@    if (!buf) return EXT4_DIRW_ERR_IO;@    if (0) return EXT4_DIRW_ERR_IO;@'

# The inode is already allocated when the buffer to fill it in fails, so the
# failure path hands it back. Keeping it instead leaves an inode marked in use that
# nothing will ever name.
try "an inode is kept after the buffer for it fails" ext4_create.c \
    's@    if (!inode) { ext4_free_inode(w, (uint32_t)ino); return EXT4_DIRW_ERR_IO; }@    if (!inode) { return EXT4_DIRW_ERR_IO; }@g' \
    "An inode nobody names is a residual the unmutated code already leaves at some
              points - measured: mkdir, unlink and rmdir all produce \"Connect to
              /lost+found?\" on this sweep without any mutant. That is the no-journal
              limit written down as F7 in the code review, not a defect, so the bar here
              cannot forbid it without failing the real code. Keeping the mutant as a
              record of what this stand does not judge."

echo
if [ "$fail" -ne 0 ]; then
    echo "RESULT: the harness has a gap - a broken out-of-memory path passed it"
    exit 1
fi
echo "RESULT: every mutant was caught"
