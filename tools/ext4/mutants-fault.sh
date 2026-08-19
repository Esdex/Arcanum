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

# Measures faultcheck.py.
#
#   ./mutants-fault.sh <cases-dir>
#
# faultcheck fails each write of an operation in turn and requires what is left to
# be repairable by e2fsck without losing anything. That bar is deliberately
# forgiving - there is no journal, so a residual is expected - which makes it worth
# proving it is not so forgiving as to accept anything.
#
# Every mutant here changes the *order* two writes happen in. Order is the whole of
# crash-safety without a journal: the same two writes in the other sequence leave a
# window where a fault costs data rather than tidiness, and nothing about the
# finished filesystem shows which order produced it.

set -uo pipefail

CASES="${1:-}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

. "$HERE/sources.sh"

STAGE="$WORK/ext4"
mkdir -p "$STAGE"
stage() { for f in $EXT4_SOURCES $EXT4_HEADERS; do cp "$EXT4_DIR/$f" "$STAGE/"; done; }
stage

fail=0

# try <description> <file> <sed> [why-it-cannot-be-caught]
try() {
    local desc="$1" file="$2" expr="$3" expect_miss="${4:-}"
    stage
    sed -i "$expr" "$STAGE/$file"
    if cmp -s "$EXT4_DIR/$file" "$STAGE/$file"; then
        echo "  SKIP  $desc - the pattern did not match, so nothing was mutated"
        fail=1; return
    fi
    local SRC=""
    for f in $EXT4_SOURCES; do SRC="$SRC $STAGE/$f"; done
    # faultop keeps the --wrap flags it is built with everywhere else, so that a
    # mutant binary behaves like the real one in every way but the mutation.
    if ! (cd "$WORK" &&
          cc -O2 -std=c99 -I"$STAGE" -Wl,--wrap=malloc,--wrap=calloc,--wrap=realloc \
             -o fo "$HERE/faultop.c"  $SRC 2>/dev/null &&
          cc -O2 -std=c99 -I"$STAGE" -o mk "$HERE/mkfs.c"     $SRC 2>/dev/null &&
          cc -O2 -std=c99 -I"$STAGE" -o dw "$HERE/dirwrite.c" $SRC 2>/dev/null &&
          cc -O2 -std=c99 -I"$STAGE" -o ew "$HERE/extwrite.c" $SRC 2>/dev/null); then
        echo "  SKIP  $desc - mutant did not build"
        fail=1; return
    fi
    if timeout 1200 "$HERE/faultcheck.py" --faultop "$WORK/fo" --mkfs "$WORK/mk" \
                    --dirwrite "$WORK/dw" --extwrite "$WORK/ew" >/dev/null 2>&1; then
        if [ -n "$expect_miss" ]; then
            echo "  untestable: $desc"
            echo "              $expect_miss"
        else
            echo "  MISS  $desc - the sweep did not notice"; fail=1
        fi
    else
        if [ -n "$expect_miss" ]; then
            echo "  UNEXPECTED CATCH: $desc was marked untestable but the sweep caught it"
            fail=1
        else
            echo "  caught: $desc"
        fi
    fi
}

echo "fault-order mutation tests (each should read caught):"

# Create fills the inode in before any name points at it. Naming it first leaves a
# window where a fault stops the run with a directory entry pointing at an inode
# that was never filled in - damage e2fsck has to repair, rather than an inode
# nobody names, which it merely tidies.
try "the name is added before the inode is filled in" ext4_create.c \
    's@    rc = ext4_write_inode_raw(w, (uint32_t)ino, inode);@    rc = ext4_dir_add(w, r, dir_ino, (uint32_t)ino, EXT4_FT_REG_FILE, name);@'

# Unlink takes the name out first, so a fault leaves an inode nothing refers to.
# Freeing the blocks first leaves the opposite: a name pointing at blocks that have
# already been handed back and may belong to another file by the time anyone looks.
try "the blocks are freed before the name is taken out" ext4_create.c \
    's@    rc = ext4_dir_remove(w, r, dir_ino, name);@    rc = (ext4_truncate_blocks(w, ino, 0) == EXTW_OK) ? ext4_dir_remove(w, r, dir_ino, name) : EXT4_DIRW_ERR_IO;@' \
    "The unlink sweep lists the file being unlinked in may_change - it is supposed to
              disappear - so damage to that one file is permitted by construction and this
              reordering hides inside the permission. A real limit of the bar rather than a
              gap to close: forbidding it would fail the unmutated code, which also leaves
              that file half-gone at most fault points. The next mutant reaches a directory
              whose *other* entries are not in may_change, which is where an order like this
              does show."

# The htree rebuild clears the index flag before rewriting the blocks, so a fault
# leaves an ordinary linear directory - possibly with a duplicate - which is
# repairable. Rewriting first leaves the opposite: an index still in force over
# blocks that are no longer what it describes, and every name it pointed at becomes
# unreachable. Those names are not in may_change, so the census sees them go.
try "the index is left in force while its blocks are rewritten" ext4_dirwrite.c \
    's@    wr32(dir + INODE_FLAGS_OFF,@    rc = flatten_pass(w, r, dir, blocks, seed, src, dst, NULL);\n    wr32(dir + INODE_FLAGS_OFF,@'

echo
if [ "$fail" -ne 0 ]; then
    echo "RESULT: the harness has a gap - a bad write order passed the sweep"
    exit 1
fi
echo "RESULT: every mutant was caught"
