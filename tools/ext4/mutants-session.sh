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

# Measures sessioncheck.py against a broken ext4_session.c (#155, second half).
#
#   ./mutants-session.sh
#
# Holding a filesystem handle across operations fails in two directions, and as
# with the block cache only one of them is loud.
#
#   Keep too much  and an operation that died half way lends its wreckage - a
#                  descriptor table and free counts ahead of the disk - to the next
#                  operation, which flushes it. That is a corrupted volume with
#                  nothing reported, the worst failure in this tree.
#   Keep too little and every ask reopens. Correct, byte-identical, and the entire
#                  point of the change gone. No safety check anywhere would notice,
#                  which is exactly why the stand counts opens.
#
# Both directions are below and the stand has to catch every one.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

. "$HERE/sources.sh"
mutant_stage "$HERE" "$WORK" session.c

# sessioncheck.py needs a formatter as well as the driver. It is built from
# pristine sources on purpose: mkfs must lay down a sound filesystem for the
# mutant to be judged on, and nothing here mutates a file it uses.
if [ ! -x "$HERE/mkfs" ]; then
    echo "mkfs not built - run ./build.sh first" >&2
    exit 1
fi

fail=0

try() {
    local desc="$1"; shift
    mutant_reset "$HERE" "$WORK"
    local expr
    for expr in "$@"; do
        sed -i "$expr" "$WORK/ext4_session.c"
    done
    if ! mutant_changed "$HERE" "$WORK"; then
        echo "  SKIP  $desc - the pattern did not match, so nothing was mutated"
        fail=1; return
    fi
    if ! mutant_build "$WORK" session.c sess; then
        echo "  SKIP  $desc - mutant did not build"
        fail=1; return
    fi
    if "$HERE/sessioncheck.py" --mkfs "$HERE/mkfs" --session "$WORK/sess" >/dev/null 2>&1; then
        echo "  MISS  $desc - the stand did not catch it"; fail=1
    else
        echo "  caught: $desc"
    fi
}

echo "mutants of ext4_session.c against sessioncheck.py:"

# ── Keeping too much: the silent direction ─────────────────────────────────
# The rule the whole module exists for. Closing after every operation was never a
# performance choice; it was what made a dead operation harmless, because the next
# one re-read the disk. If a failed write no longer poisons the handle, the
# descriptor table that operation had already changed in memory is flushed by the
# NEXT one, over a disk that never received the rest of it.
try "a failed write does not poison the handle" \
    '/^static int sess_write/,/^}/ s/^    if (rc) s->w_poisoned = 1;/    \/* mutant: the failure is not remembered *\//'

try "a failed flush does not poison the handle" \
    '/^static int sess_flush/,/^}/ s/^    if (rc) s->w_poisoned = 1;/    \/* mutant: the failure is not remembered *\//'

# Closing a poisoned handle must throw its memory away. Writing it out first is the
# one thing that turns "we do not know what is on disk" into "the disk now holds
# what the dead operation believed".
try "a poisoned handle is flushed on its way out" \
    's/^    ext4_fs_close(&s->w);/    ext4_fs_flush(\&s->w);\n    ext4_fs_close(\&s->w);/'

# A drop is the caller saying the volume may not be what we think. Keeping the
# reader through it keeps a parse of a filesystem that a format has replaced -
# including its checksum seed, which is how a foreign volume was corrupted in #147.
try "drop forgets the writable handle but keeps the reader" \
    '/^void ext4_session_drop/,/^}/ s/^    close_reader(s);/    \/* mutant: the old parse stays *\//'

try "close_reader forgets nothing" \
    's/^static void close_reader(ext4_session \*s) {/&\n    (void)s; return;/'

# The reader reads through a context that carries its block size, and ext4_open
# needs that context back at the bootstrap 1 KiB before it can find the superblock.
# It matters only on the SECOND open, which is why nothing noticed until a drop.
try "the read context is not put back to the bootstrap size before reopening" \
    's/^        if (s->set_bs) s->set_bs(s->reader_ctx, 1024);/        \/* mutant: reopen at whatever size was left *\//'

try "the read context is never told the real block size" \
    's/^        if (s->set_bs) s->set_bs(s->reader_ctx, s->r.block_size);/        \/* mutant *\//'

# s_wtime has to record the operation happening, not the one that opened the mount.
try "the clock is taken once, at open, instead of on every ask" \
    's/^    s->w.now = now;/    \/* mutant: whatever the opening operation set *\//' \
    's/^        s->w_open = 1;/        s->w.now = now;\n        s->w_open = 1;/'

# ── Keeping too little: correct, and pointless ─────────────────────────────
try "every ask reopens the writable handle" \
    's/^    if (s->w_poisoned) close_writer(s);/    close_writer(s);/'

try "every ask reopens the reader" \
    's/^    if (!s->r_open) {/    close_reader(s);\n    if (!s->r_open) {/'

# The poison must be cleared when it is acted on, or the handle is thrown away for
# the rest of the mount over one failure that has already been dealt with.
try "the poison is never cleared, so every later ask reopens" \
    's/^    s->w_poisoned = 0;/    \/* mutant: the handle stays condemned *\//'

# A failed write says nothing about the reader: it holds geometry that cannot change
# while the filesystem exists. Dropping it anyway is safe and costs the superblock
# read this change exists to remove.
try "a poisoned write throws the reader away too" \
    's/^    if (s->w_poisoned) close_writer(s);/    if (s->w_poisoned) { close_writer(s); close_reader(s); }/'

if [ "$fail" -eq 0 ]; then
    echo "RESULT: every mutant was caught"
else
    echo "RESULT: see above"
fi
exit $fail
