#!/usr/bin/env bash
# Measures createcheck.py, not the create/delete path.
#
#   ./mutants-create.sh <cases-dir>
#
# Nothing here writes a structure that does not already have its own suite. What
# it can get wrong is the order and the fields nobody else fills in - an inode
# that is allocated and named but not made usable, or freed without being marked
# as freed. Those are exactly the failures no single layer can see.

set -euo pipefail

CASES="${1:?usage: ./mutants-create.sh <cases-dir>}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

. "$HERE/sources.sh"
mutant_stage "$HERE" "$WORK" dirwrite.c

fail=0

try() {
    local desc="$1" expr="$2" expect_miss="${3:-}"
    mutant_reset "$HERE" "$WORK"
    sed -i "$expr" "$WORK/ext4_create.c"
    if ! mutant_changed "$HERE" "$WORK"; then
        echo "  SKIP  $desc - the pattern did not match, so nothing was mutated"
        fail=1; return
    fi
    if ! mutant_build "$WORK" dirwrite.c dw; then
        echo "  SKIP  $desc - mutant did not build"
        fail=1; return
    fi
    if "$HERE/createcheck.py" --cases "$CASES" --limit 8 --bench "$HERE/bench" \
                              --dirwrite "$WORK/dw" --extwrite "$HERE/extwrite" \
                              >/dev/null 2>&1; then
        if [ -n "$expect_miss" ]; then
            echo "  untestable: $desc"; echo "              $expect_miss"
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

echo "create and delete mutation tests (each should read caught, or untestable with a reason):"

# The fields that make a zeroed inode into a file.

try "new inode left without a link count" \
    's@    wr16(inode + INODE_LINKS_COUNT_OFF, links);@@'

try "new inode left without its mode" \
    's@    wr16(inode + INODE_MODE_OFF, mode);@@'

try "new inode not marked as using extents" \
    's@    wr32(inode + INODE_FLAGS_OFF, EXT4_INODE_FLAG_EXTENTS);@@'

try "new inode given no extent root" \
    's@    wr16(root, EXT4_EXTENT_MAGIC);@@'

try "extent root claims room it does not have" \
    's@    wr16(root + 4, 4);@    wr16(root + 4, 8);@'

try "extent root claims an entry it does not have" \
    's@    wr16(root + 2, 0);@    wr16(root + 2, 1);@'

try "i_extra_isize left zero, so the checksum covers the wrong half" \
    's@EXT4_GOOD_EXTRA_ISIZE);@0);@'

# Deleting. The half that is easy to forget.

try "freed inode left without a deletion time" \
    's@    wr32(dead + INODE_DTIME_OFF, when);@@'

try "freed inode keeps its link count" \
    's@    wr16(dead + INODE_LINKS_COUNT_OFF, 0);@@'

try "blocks kept when the last name goes" \
    's@    if (ext4_truncate_blocks(w, ino, 0) != EXTW_OK) return EXT4_DIRW_ERR_IO;@@'

try "inode not handed back when the last name goes" \
    's@    if (ext4_free_inode(w, ino)) return EXT4_DIRW_ERR_IO;@@'

# Order and rollback.

try "name added before the inode is written" \
    's@    rc = ext4_write_inode_raw(w, (uint32_t)ino, inode);@    rc = ext4_dir_add(w, r, dir_ino, (uint32_t)ino, EXT4_FT_REG_FILE, name); if (rc == EXT4_DIRW_OK) rc = ext4_write_inode_raw(w, (uint32_t)ino, inode);@'

try "inode not handed back when the name cannot be added" \
    's@        ext4_free_inode(w, (uint32_t)ino);@        ;@' \
    "The rollback only runs when adding the name fails, and it does not: a directory
              with no gap grows a block instead. Reaching it needs a filesystem with no free
              block left *and* a full directory, which is a corpus of its own. It is the
              path that decides whether a failed create leaks an inode or not, so it is
              stated rather than dropped."

echo
if [ "$fail" -ne 0 ]; then
    echo "RESULT: the harness has a gap - a broken writer passed it"
    exit 1
fi
echo "RESULT: every testable mutant was caught"
