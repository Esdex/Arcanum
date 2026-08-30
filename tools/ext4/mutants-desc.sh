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

# Measures desccheck.py against a broken descriptor flush (#160).
#
#   ./mutants-desc.sh
#
# Writing only what changed fails in two directions and, as always, only one of them
# is loud.
#
#   Write too little  and a counter the driver is allocating from never reaches the
#                     disk. Nothing reports it. The volume simply disagrees with
#                     itself until e2fsck is run, or until a block is handed out
#                     that something else already owns.
#   Write too much    and every flush puts the whole table down again. Perfectly
#                     correct, e2fsck-clean, and the entire point of #160 gone. No
#                     safety check anywhere would notice, which is why the stand
#                     compares the write count between a small volume and a large
#                     one rather than only checking the result.
#
# Both directions are below.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

. "$HERE/sources.sh"
mutant_stage "$HERE" "$WORK" session.c

if [ ! -x "$HERE/mkfs" ]; then
    echo "mkfs not built - run ./build.sh first" >&2
    exit 1
fi

fail=0

# try <desc> [--untestable <reason>] <sed expr>...
#
# A mutant nothing can catch is recorded with its reason rather than left as a MISS,
# and the run fails if the stand DOES catch one that is marked untestable - a reason
# that has quietly stopped being true is worse than no reason.
try() {
    local desc="$1"; shift
    local expect_miss=""
    if [ "${1:-}" = "--untestable" ]; then expect_miss="$2"; shift 2; fi
    mutant_reset "$HERE" "$WORK"
    local expr
    for expr in "$@"; do
        sed -i "$expr" "$WORK/ext4_alloc.c"
    done
    if ! mutant_changed "$HERE" "$WORK"; then
        echo "  SKIP  $desc - the pattern did not match, so nothing was mutated"
        fail=1; return
    fi
    if ! mutant_build "$WORK" session.c sess; then
        echo "  SKIP  $desc - mutant did not build"
        fail=1; return
    fi
    if "$HERE/desccheck.py" --mkfs "$HERE/mkfs" --session "$WORK/sess" >/dev/null 2>&1; then
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

echo "mutants of the descriptor flush in ext4_alloc.c against desccheck.py:"

# ── Writing too little: the silent direction ───────────────────────────────
try "nothing is ever written, because everything looks unchanged" \
    's/^        if (memcmp(fs->desc + i, fs->desc_shadow + i, span) == 0) {/        if (1) {/'

try "the flush writes no descriptors at all" \
    's/^static int flush_descriptors(ext4_wfs \*fs) {/&\n    (void)fs; return 0;/'

# The shadow says what the disk has RECEIVED. Advancing it over bytes that were not
# written makes the next flush believe they are already down, and the change is gone
# for good rather than retried.
try "the shadow is advanced before the write instead of after" \
    --untestable "a descriptor write that fails takes the whole handle with it, so nothing
              ever reads the shadow again: ext4_session.c poisons the writable handle on
              any failed write and reopens it off the disk, and jni_ext4.cpp tears the
              operation as well. The ordering here is defensive rather than load-bearing.
              The day anything retries a flush on the same handle it becomes load-bearing
              at once, and this line will be why it works." \
    '/^        if (ext4_io_pwrite(&fs->io, desc_at + run, fs->desc + run, i - run)) return -1;$/d' \
    's|^        memcpy(fs->desc_shadow + run, fs->desc + run, i - run);|        memcpy(fs->desc_shadow + run, fs->desc + run, i - run);\n        if (ext4_io_pwrite(\&fs->io, desc_at + run, fs->desc + run, i - run)) return -1;|'

try "a run is written but only its first block" \
    's/if (ext4_io_pwrite(&fs->io, desc_at + run, fs->desc + run, i - run)) return -1;/if (ext4_io_pwrite(\&fs->io, desc_at + run, fs->desc + run, bs)) return -1;/'

try "the comparison looks at one byte instead of the block" \
    's/if (memcmp(fs->desc + i, fs->desc_shadow + i, span) == 0) {/if (memcmp(fs->desc + i, fs->desc_shadow + i, 1) == 0) {/'

try "the shadow is never brought up to date, so a change is written once and then forgotten" \
    's/^        memcpy(fs->desc_shadow + run, fs->desc + run, i - run);/        \/* mutant: the shadow stays where it was *\//'

# ── Writing too much: correct, and pointless ───────────────────────────────
try "the shadow does not start from the disk, so the first flush writes the lot" \
    's/^    memcpy(fs->desc_shadow, fs->desc, desc_len);/    memset(fs->desc_shadow, 0xAA, desc_len);/'

try "every flush writes the whole table again (the behaviour #160 is about)" \
    's/^static int flush_descriptors(ext4_wfs \*fs) {/&\n    return ext4_io_pwrite(\&fs->io, (uint64_t)(fs->first_data_block + 1) * fs->block_size,\n                          fs->desc, (size_t)fs->groups * fs->desc_size) ? -1 : 0;/'

if [ "$fail" -eq 0 ]; then
    echo "RESULT: every mutant was caught"
else
    echo "RESULT: see above"
fi
exit $fail
