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

# Measures mklinkcheck.py against broken link creation (#128).
#
#   ./mutants-mklink.sh
#
# The two kinds fail in different places and only one of them is loud.
#
#   The wrong shape        A symlink stored inline while claiming extents, or the
#                          boundary drawn a byte off, produces a volume e2fsck may
#                          well call clean and a desktop reads as something else.
#                          debugfs and fuse2fs are the oracles, not our own reader.
#   The wrong count        A link count too high leaks an inode a check reclaims.
#                          Too LOW frees a file that another name still points at,
#                          and it disappears from a place nobody touched. e2fsck
#                          sees both, which is why every step here is checked
#                          against it.
#   A refusal that wrote   Refusing after taking an inode leaves it stranded. The
#                          image is hashed either side of every refusal.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

. "$HERE/sources.sh"
mutant_stage "$HERE" "$WORK" dirwrite.c
for f in mkfs.c extwrite.c pathresolve.c; do cp "$HERE/$f" "$WORK/"; done

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
    if ! mutant_build "$WORK" dirwrite.c dw || ! mutant_build "$WORK" mkfs.c mk \
            || ! mutant_build "$WORK" extwrite.c ew \
            || ! mutant_build "$WORK" pathresolve.c pr; then
        echo "  SKIP  $desc - mutant did not build"
        fail=1; return
    fi
    if timeout 300 "$HERE/mklinkcheck.py" --mkfs "$WORK/mk" --dirwrite "$WORK/dw" \
                    --extwrite "$WORK/ew" --pathresolve "$WORK/pr" >/dev/null 2>&1; then
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

echo "mutants of link creation in ext4_create.c against mklinkcheck.py:"

# ── The wrong shape ────────────────────────────────────────────────────────
try "the boundary is drawn one byte too high, so 60 goes inside a 60-byte field" \
    ext4_create.c \
    's|#define EXT4_FAST_SYMLINK_MAX 59|#define EXT4_FAST_SYMLINK_MAX 60|'

try "the boundary is drawn one byte too low, so 59 needlessly owns a block" \
    ext4_create.c \
    's|#define EXT4_FAST_SYMLINK_MAX 59|#define EXT4_FAST_SYMLINK_MAX 58|'

try "an inline target keeps the extents flag it was given" \
    ext4_create.c \
    's|        wr32(inode + INODE_FLAGS_OFF, 0);|        \/* mutant: the flag stays *\/|'

try "the size is left at zero, so the target has no length" \
    ext4_create.c \
    's|        wr32(inode + INODE_SIZE_LO_OFF, (uint32_t)len);|        \/* mutant: no size *\/|'

try "the entry is recorded as a regular file rather than a link" \
    ext4_create.c \
    's|    rc = ext4_dir_add(w, r, dir_ino, (uint32_t)ino, EXT4_FT_SYMLINK, name);|    rc = ext4_dir_add(w, r, dir_ino, (uint32_t)ino, EXT4_FT_REG_FILE, name);|'

# ── The wrong count ────────────────────────────────────────────────────────
try "a second name is added without raising the count" \
    ext4_create.c \
    's|    rc = ext4_inode_adjust_links(w, target_ino, +1);|    rc = EXTW_OK;|'

try "the count is raised twice for one name" \
    ext4_create.c \
    's|    rc = ext4_inode_adjust_links(w, target_ino, +1);|    ext4_inode_adjust_links(w, target_ino, +1);\n    rc = ext4_inode_adjust_links(w, target_ino, +1);|'

# ── Refusing things that must be refused ───────────────────────────────────
try "a directory is allowed a second name" \
    ext4_create.c \
    's|    if ((mode \& EXT4_S_IFMT) == EXT4_S_IFDIR) {|    if (0) {|'

try "an empty or oversized target is accepted" \
    ext4_create.c \
    's|    if (len == 0 \|\| len > EXT4_SYMLINK_TARGET_MAX) {|    if (0) {|'

try "a name that is already taken is used anyway" \
    ext4_create.c \
    's|        return EXT4_DIRW_ERR_EXISTS;|        rc = EXT4_DIRW_ERR_ABSENT;|'

if [ "$fail" -eq 0 ]; then
    echo "RESULT: every mutant was caught"
else
    echo "RESULT: see above"
fi
exit $fail
