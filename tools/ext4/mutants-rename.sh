#!/usr/bin/env bash
# Measures renamecheck.py against ext4_rename (ext4_create.c) and ext4_dir_set_dotdot
# (ext4_dirwrite.c).
#
#   ./mutants-rename.sh
#
# Rename composes primitives that already have their own suites, in one order, and
# moves a fixed set of counters. Its own failures are each an omitted or wrong one
# of those: the old name left in place, a directory's ".." left naming the old
# parent, a parent link not shifted, a directory let into its own subtree, an entry
# typed wrong. Each mutant breaks exactly one and the harness has to notice.
#
# The first mutant IS the bug rename exists to prevent - the thing left reachable by
# both its old and its new name because the old one was never removed.
#
# mkfs is not rebuilt: it lays down the input tree and shares none of the sources
# these mutants touch (ext4_create.c / ext4_dirwrite.c are not linked into it), so
# the pristine binary is the honest formatter throughout.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

. "$HERE/sources.sh"
mutant_stage "$HERE" "$WORK" rename.c

fail=0

try() {
    local desc="$1" file="$2" expr="$3"
    mutant_reset "$HERE" "$WORK"
    sed -i "$expr" "$WORK/$file"
    if ! mutant_changed "$HERE" "$WORK"; then
        echo "  SKIP  $desc - the pattern did not match, so nothing was mutated"
        fail=1; return
    fi
    if ! (cd "$WORK" && cc -O2 -std=c99 -o rn rename.c $EXT4_SOURCES 2>/dev/null); then
        echo "  SKIP  $desc - mutant did not build"
        fail=1; return
    fi
    if "$HERE/renamecheck.py" --mkfs "$HERE/mkfs" --rename "$WORK/rn" >/dev/null 2>&1; then
        echo "  MISS  $desc - the harness did not catch it"; fail=1
    else
        echo "  caught: $desc"
    fi
}

echo "rename mutation tests (each should read caught):"

# The device-bug analogue: the move that leaves its subject reachable by both names.
try "the old name is never removed (left at both paths - the bug itself)" \
    ext4_create.c 's@rc = ext4_dir_remove(w, r, src_parent, src_name);@rc = EXT4_DIRW_OK;@'

try "a moved directory's '..' is left naming the old parent" \
    ext4_create.c 's@rc = ext4_dir_set_dotdot(w, r, src_ino, dst_parent);@rc = EXT4_DIRW_OK;@'

try "the new parent never gains the link its '..' is" \
    ext4_create.c 's@ext4_inode_adjust_links(w, dst_parent, 1)@EXTW_OK@'

try "the old parent never loses the link its '..' was" \
    ext4_create.c 's@ext4_inode_adjust_links(w, src_parent, -1)@EXTW_OK@'

try "a directory may be moved inside its own subtree (loop guard defeated)" \
    ext4_create.c 's@if (cur == moving_dir) return 1;@if (cur == moving_dir) return 0;@'

try "a moved directory's entry is typed as a regular file" \
    ext4_create.c 's@uint8_t ftype  = is_dir ? EXT4_FT_DIR : EXT4_FT_REG_FILE;@uint8_t ftype  = EXT4_FT_REG_FILE;@'

try "renaming a thing to itself is treated as a real move" \
    ext4_create.c 's@if (src_parent == dst_parent && strcmp(src_name, dst_name) == 0) {@if (0) {@'

try "set_dotdot points '..' at the directory itself, not the new parent" \
    ext4_dirwrite.c 's@wr32(buf + off, new_parent);@wr32(buf + off, dir_ino);@'

echo
echo "  untestable: removing the 'destination exists' guard in ext4_rename is masked"
echo "              by ext4_dir_add, which refuses a duplicate name on its own. The"
echo "              move is refused either way, so dropping the guard is invisible -"
echo "              a real observation about the layering, kept here so nobody adds"
echo "              it as a mutant expecting it to be caught."

echo
if [ "$fail" -ne 0 ]; then
    echo "RESULT: the harness has a gap - a broken rename passed it"
    exit 1
fi
echo "RESULT: every mutant was caught"
