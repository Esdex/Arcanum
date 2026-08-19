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

# Measures fuzz.sh - in its fast mode, not a campaign.
#
#   ./mutants-fuzz.sh
#
# fuzz.sh without --fuzz is an ordinary stand: it replays the seed images and the
# inputs that once hung the driver. This asks whether that replay still bites, by
# putting each of those bugs back.
#
# It exists because of what the #147 audit turned up about the *notes*. Every
# mutant below is one that some other suite records as untestable, or that no
# suite covered at all - and the reason is always the same: the corpus every other
# stand uses is well-formed, so a guard against malformed input is never the thing
# that stops a walk. The fuzz corpus is the opposite by construction.
#
# The rec_len mutant is the one worth reading twice. mutants-dir.sh still carried
# "a rec_len of zero accepted - untestable, the corpus contains no malformed
# rec_len", which was true when written and stopped being true the day fuzz.sh
# gained its dir_size_huge case. Nothing announced that. It was found by going
# through every untestable note and re-deriving it, which is the only way this kind
# of rot is ever found.

set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

. "$HERE/sources.sh"

# A private copy of the library for the mutants to be applied to. fuzz.sh builds
# its own target out of $EXT4_DIR, so pointing that at the copy is what makes a
# mutated fuzz target possible without touching the tree.
STAGE="$WORK/ext4"
mkdir -p "$STAGE"
for f in $EXT4_SOURCES $EXT4_HEADERS; do cp "$EXT4_DIR/$f" "$STAGE/"; done

fail=0

# Some defects are only reachable with two guards removed together, because either
# one on its own turns the volume away before the other is consulted. `try` takes
# the first edit and PENDING carries any others, applied before the run.
PENDING=()
also() { PENDING+=("$1" "$2"); }   # call BEFORE try; applied with it

# try <description> <file> <sed> [why-it-cannot-be-caught]
try() {
    local desc="$1" file="$2" expr="$3" expect_miss="${4:-}"
    for f in $EXT4_SOURCES $EXT4_HEADERS; do cp "$EXT4_DIR/$f" "$STAGE/"; done
    sed -i "$expr" "$STAGE/$file"
    while [ "${#PENDING[@]}" -gt 0 ]; do
        sed -i "${PENDING[1]}" "$STAGE/${PENDING[0]}"
        PENDING=("${PENDING[@]:2}")
    done
    if cmp -s "$EXT4_DIR/$file" "$STAGE/$file"; then
        echo "  SKIP  $desc - the pattern did not match, so nothing was mutated"
        fail=1; return
    fi
    # A generous ceiling: the fast mode takes about a minute, and every mutant here
    # fails either by hanging or by a sanitizer report. Being killed by the timeout
    # counts as caught, which is correct - a driver that does not come back is the
    # defect, and libFuzzer's own -timeout reports it that way too.
    if EXT4_DIR="$STAGE" timeout 420 "$HERE/fuzz.sh" >/dev/null 2>&1; then
        if [ -n "$expect_miss" ]; then
            echo "  untestable: $desc"
            echo "              $expect_miss"
        else
            echo "  MISS  $desc - the replay did not notice"; fail=1
        fi
    else
        if [ -n "$expect_miss" ]; then
            echo "  UNEXPECTED CATCH: $desc was marked untestable but the replay caught it"
            fail=1
        else
            echo "  caught: $desc"
        fi
    fi
}

echo "fuzz-replay mutation tests (each should read caught):"

# Recorded as untestable in mutants-dir.sh until this suite existed. Without the
# guard a rec_len of zero makes the walk step nowhere, for ever. Reached by the
# dir_size_huge case, whose directory runs off the end of its real content into
# blocks of zeroes - and a block of zeroes is a rec_len of zero.
try "a rec_len of zero is accepted" ext4_dir.c \
    's@        if (rec_len < DIRENT_HEADER || (rec_len \& 3) != 0) return EXT4_ERR_FORMAT;@@'

# The two bounds #147 added, each of which is a length the image chooses. Neither
# was covered by any suite; both were demonstrated by hand when they were fixed,
# which is not the same as a guard that runs again next time.
# Every one of those clauses at once, which is the state before #147 rather than a
# third of it. Either bound alone turns the volume away - the reader refuses first
# and the writer never opens - and the value is odd, so the multiple-of-eight clause
# catches it even with both caps gone. This suite's first two attempts removed one
# clause each and read MISS for that reason.
#
# It is caught on time rather than on a hang: with the clauses gone the case takes
# about 36 seconds, against the 20 fuzz.sh allows per case. Worth knowing, because
# checking it by hand with a longer timeout says "completes" and that is how this
# mutant briefly got marked untestable. Measure against the stand\'s own bar.
also ext4_alloc.c \
    's@        !fs->inodes_per_group || (fs->inodes_per_group \& 7) ||@@'
also ext4_alloc.c \
    's@        fs->inodes_per_group > 8 \* fs->block_size ||@@'
try "neither open bounds inodes_per_group" ext4_extents.c \
    's@    if (fs->inodes_per_group == 0 ||@    if (0 \&\&@'

try "a directory is walked to whatever i_size claims" ext4_dir.c \
    's@    uint64_t size = dir_walk_limit(fs, inode);@    uint64_t size = ext4_inode_size(inode);@'

echo
if [ "$fail" -ne 0 ]; then
    echo "RESULT: the harness has a gap - a malformed image passed the replay"
    exit 1
fi
echo "RESULT: every mutant was caught"
