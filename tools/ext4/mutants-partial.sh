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

# Measures partialcheck.py against a broken partial read (#173).
#
#   ./mutants-partial.sh
#
# The thing being guarded is narrow and easy to get subtly wrong: a read that
# refuses too much, a read that refuses too little, and - worst - a read that
# returns a plausible LENGTH of zeroes instead of the file's own bytes. Every
# mutant below is a way one of those three could be written by accident.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

. "$HERE/sources.sh"
mutant_stage "$HERE" "$WORK" partialread.c

if [ ! -x "$HERE/mkfs" ]; then
    echo "mkfs not built - run ./build.sh first" >&2
    exit 1
fi

fail=0

# try <desc> [--untestable <reason>] <sed expr>...
try() {
    local desc="$1"; shift
    local expect_miss=""
    if [ "${1:-}" = "--untestable" ]; then expect_miss="$2"; shift 2; fi
    mutant_reset "$HERE" "$WORK"
    local expr
    for expr in "$@"; do
        sed -i "$expr" "$WORK/ext4_extents.c"
    done
    if ! mutant_changed "$HERE" "$WORK"; then
        echo "  SKIP  $desc - the pattern did not match, so nothing was mutated"
        fail=1; return
    fi
    if ! mutant_build "$WORK" partialread.c pr; then
        echo "  SKIP  $desc - mutant did not build"
        fail=1; return
    fi
    if timeout 600 "$HERE/partialcheck.py" --partialread "$WORK/pr" >/dev/null 2>&1; then
        if [ -n "$expect_miss" ]; then
            echo "  untestable: $desc"; echo "              $expect_miss"
        else
            echo "  MISS  $desc - the stand did not catch it"; fail=1
        fi
    else
        if [ -n "$expect_miss" ]; then
            echo "  UNEXPECTED CATCH: $desc was marked untestable but the stand caught it"
            fail=1
        else
            echo "  caught: $desc"
        fi
    fi
}

echo "mutants of the partial read in ext4_extents.c:"

# ── Refusing too much ───────────────────────────────────────────────────────
# The eight-space indent is what tells this line from the ordinary window test on
# line 308, which reads `if (beg >= r->want_end)   return 1;` with a comment. A
# pattern spanning both lines cannot be written here: sed works a line at a time,
# and a \n in the PATTERN half never matches anything.
try "damage past the window stops the read anyway" \
    's|^        if (beg >= r->want_end) return 1;$|        ;|'

try "a bad entry refuses the walk again, so nothing can be partial" \
    's|                if (!report_bad) return EXT4_ERR_RANGE;|                return EXT4_ERR_RANGE;|'

# ── Refusing too little ─────────────────────────────────────────────────────
try "a strict read hands back the good part instead of refusing" \
    's|        return partial ? (long)(ctx.bad_at - offset) : ctx.rc;|        return (long)(ctx.bad_at - offset);|'

try "the partial read claims the whole window" \
    's|        return partial ? (long)(ctx.bad_at - offset) : ctx.rc;|        return partial ? (long)length : ctx.rc;|'

try "the prefix is measured from the wrong end" \
    's|        r->bad_at = beg < r->want_start ? r->want_start : beg;|        r->bad_at = r->want_end;|'

# ── Returning a length of nothing ───────────────────────────────────────────
try "the prefix is right but the bytes are never copied" \
    's|        memcpy(r->out + (from - r->want_start), block + blk_offset, (size_t)chunk);|        (void)block;|'

try "a bad entry is walked through as if it were data" \
    's|                run.bad      = 1;|                run.bad      = 0;|'

try "an unreadable index block is reported as the end of the file" \
    's|            ext4_extent_run bad = { rd32(entry), 0, 0, 0, 1 };|            ext4_extent_run bad = { 0, 0, 0, 0, 1 };|'

# ── The flag itself ─────────────────────────────────────────────────────────
try "the flag is left as whatever was on the stack" \
    's|    run->bad      = 0;          /\* set by the walk, never by the entry itself \*/||'

if [ "$fail" -ne 0 ]; then
    echo
    echo "RESULT: a mutant went unnoticed"
    exit 1
fi
echo
echo "RESULT: every mutant was caught"
