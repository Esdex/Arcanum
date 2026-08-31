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

# Measures linkcheck.py against broken symlink handling (#163).
#
#   ./mutants-link.sh
#
# Following a link wrong is quiet in a way most defects here are not: nothing
# crashes, no check complains, the volume stays perfectly valid. What changes is
# WHICH object the app acts on - so a listing describes one thing and a delete
# removes another, and the only oracle is a fixture where the right answer and the
# wrong one are different inodes.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

. "$HERE/sources.sh"
mutant_stage "$HERE" "$WORK" pathresolve.c

fail=0

# try <desc> [--untestable <reason>] <file> <sed expr>
try() {
    local desc="$1"; shift
    local expect_miss=""
    if [ "${1:-}" = "--untestable" ]; then expect_miss="$2"; shift 2; fi
    local file="$1" expr="$2"
    mutant_reset "$HERE" "$WORK"
    sed -i "$expr" "$WORK/$file"
    if ! mutant_changed "$HERE" "$WORK"; then
        echo "  SKIP  $desc - the pattern did not match, so nothing was mutated"
        fail=1; return
    fi
    if ! mutant_build "$WORK" pathresolve.c pr; then
        echo "  SKIP  $desc - mutant did not build"
        fail=1; return
    fi
    # 60 rather than the usual generous cap: the stand runs in about two seconds,
    # and a mutant that removes the loop budget does not fail - it spins. Being
    # stopped by the clock IS the catch here, so the clock should be short.
    if timeout 60 "$HERE/linkcheck.py" --pathresolve "$WORK/pr" >/dev/null 2>&1; then
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

echo "mutants of symlink resolution against linkcheck.py:"

# ── Acting on the wrong object ─────────────────────────────────────────────
try "the last component is followed even when the caller asked it not to be" \
    ext4_path.c \
    's|    return resolve_with(r, path, 0, ino_out, is_dir_out);|    return resolve_with(r, path, 1, ino_out, is_dir_out);|'

try "the last component is never followed, so a path names the link not the file" \
    ext4_path.c \
    's|    return resolve_with(r, path, 1, ino_out, is_dir_out);|    return resolve_with(r, path, 0, ino_out, is_dir_out);|'

try "a component the path goes through is not followed either" \
    ext4_path.c \
    's|        int last = (i + 1 == resolve) \&\& stop_short == 0;|        int last = 1;|'

# ── Measuring a target from the wrong place ────────────────────────────────
try "a relative target is measured from the root instead of the link's directory" \
    ext4_path.c \
    's|        uint32_t base = (target\[0\] == .\/.) ? EXT4_ROOT_INO : dir;|        uint32_t base = EXT4_ROOT_INO;|'

try "an absolute target is measured from the link's directory instead of the root" \
    ext4_path.c \
    's|        uint32_t base = (target\[0\] == .\/.) ? EXT4_ROOT_INO : dir;|        uint32_t base = dir;|'

# ── Limits that stop a ring ────────────────────────────────────────────────
try "the budget is never spent, so a ring of links is walked forever" \
    ext4_path.c \
    's|        fs_state->total--;|        \/* mutant: nothing is counted *\/|'

try "the nesting allowance is never given back, so a long chain is refused" \
    ext4_path.c \
    's|        fs_state->depth++;|        \/* mutant: never restored *\/|'

# ── Reading the target ─────────────────────────────────────────────────────
try "readlink hands back i_block for anything, link or not" \
    ext4_extents.c \
    's|    if ((rd16(inode + INODE_MODE_OFF) \& EXT4_S_IFMT) != EXT4_S_IFLNK)|    if (0)|'

try "a short target is read as though it had blocks of its own" \
    ext4_extents.c \
    's|    if (!(rd32(inode + INODE_FLAGS_OFF) \& EXT4_INODE_FLAG_EXTENTS)) {|    if (0) {|'

try "a target with a NUL inside it is followed as far as the NUL" \
    --untestable "unreachable from any fixture this harness can build, and the reason is
              that nothing makes such a link. ln refuses a target containing a NUL,
              mke2fs and debugfs have no way to write one, and producing it means
              patching the inode bytes by hand and recomputing its checksum - a
              volume no tool in the world would hand us. The check is there for a
              corrupt or hostile image, which is fuzz.sh's ground, not this one." \
    ext4_extents.c \
    's|    if (strlen(out) != (size_t)len) return EXT4_ERR_FORMAT;|    \/* mutant: a truncated path is followed *\/|'

if [ "$fail" -eq 0 ]; then
    echo "RESULT: every mutant was caught"
else
    echo "RESULT: see above"
fi
exit $fail
