#!/usr/bin/env bash
# Measures appendcheck.py, not the extent writer.
#
#   ./mutants-extwrite.sh <cases-dir>
#
# Only ext4_extwrite.c is mutated. bench and fsmeta stay built from pristine
# sources - they are the independent readers, and a mutant that broke the writer
# and the reader the same way would agree with itself and be reported as a pass.
# debugfs, which appendcheck.py also compares against, cannot be mutated at all,
# which is exactly why it is in there.
#
# Two of these mutants are bugs that were actually written and then caught while
# building this layer, both about a file whose i_size runs past its last extent:
# appending at the wrong place, and merging across the gap. They are kept as
# mutants so that fixing them stays fixed.

set -euo pipefail

CASES="${1:?usage: ./mutants-extwrite.sh <cases-dir>}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

cp "$HERE/ext4_extwrite.h" "$HERE/ext4_alloc.h" "$HERE/ext4_alloc.c" \
   "$HERE/ext4_csum.h" "$HERE/ext4_csum.c" "$HERE/extwrite.c" "$WORK/"

fail=0

# try <description> <sed-script> [expect-miss-reason]
try() {
    local desc="$1" expr="$2" expect_miss="${3:-}"
    cp "$HERE/ext4_extwrite.c" "$WORK/m.c"
    sed -i "$expr" "$WORK/m.c"
    if cmp -s "$HERE/ext4_extwrite.c" "$WORK/m.c"; then
        echo "  SKIP  $desc - the pattern did not match, so nothing was mutated"
        fail=1
        return
    fi
    if ! (cd "$WORK" && cc -O2 -std=c99 -o ew extwrite.c m.c ext4_alloc.c ext4_csum.c 2>/dev/null); then
        echo "  SKIP  $desc - mutant did not build"
        fail=1
        return
    fi
    if "$HERE/appendcheck.py" --cases "$CASES" --bench "$HERE/bench" \
                              --extwrite "$WORK/ew" --fsmeta "$HERE/fsmeta" \
                              >/dev/null 2>&1; then
        if [ -n "$expect_miss" ]; then
            echo "  untestable: $desc"
            echo "              $expect_miss"
        else
            echo "  MISS  $desc - the harness did not catch it"
            fail=1
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

echo "extent writer mutation tests (each should read caught, or untestable with a reason):"

# The inode fields. e2fsck checks both of these against the tree it walks.

try "i_size not updated" \
    's@    inode_size_set(inode, (uint64_t)next_logical \* fs->block_size);@@'

try "i_blocks not updated" \
    's@    inode_blocks_set(inode, inode_blocks_get(inode) + added \* (fs->block_size / 512));@@'

try "i_blocks counted in filesystem blocks, not 512-byte sectors" \
    's@inode_blocks_get(inode) + added \* (fs->block_size / 512)@inode_blocks_get(inode) + added@'

try "inode checksum not recomputed after the tree changes" \
    's@    wr16(buf + EXT4_INODE_CSUM_LO_OFF, (uint16_t)c);@@'

# Where the append lands. Both of these were real bugs during development.

try "append placed at the last extent, ignoring a longer i_size" \
    's@    if (by_size > next_logical) next_logical = by_size;@@'

try "merge decided on physical adjacency alone, ignoring the logical gap" \
    's@    int logically_next = last &&@    int logically_next = last \&\& (1 ||@; s@        rd32(last + EE_BLOCK_OFF) + last_len == next_logical;@        rd32(last + EE_BLOCK_OFF) + last_len == next_logical);@'

# The extent entries themselves.

try "new extent records the wrong logical block" \
    's@            wr32(slot + EE_BLOCK_OFF, next_logical);@            wr32(slot + EE_BLOCK_OFF, next_logical + 1);@'

try "entry count not increased after adding an extent" \
    's@            wr16(root + EH_ENTRIES_OFF, entries);@@'

try "extents never merged, a new entry every time" \
    's@        if (last && logically_next && !last_uninit && (uint64_t)got == goal &&@        if (0 \&\& last \&\& logically_next \&\& !last_uninit \&\& (uint64_t)got == goal \&\&@'

try "preallocated extents merged into as if they held data" \
    's@!last_uninit && (uint64_t)got == goal@(uint64_t)got == goal@'

# The data itself. None of the structural checks look at file contents, which is
# the whole reason the appended blocks carry a pattern keyed to their position.

try "data block never written" \
    's@        rc = write_data_block(fs, (uint64_t)got, block);@        rc = EXTW_OK;@'

try "every appended block written to the first one's address" \
    's@        rc = write_data_block(fs, (uint64_t)got, block);@        rc = write_data_block(fs, (uint64_t)got - i, block);@'

echo
if [ "$fail" -ne 0 ]; then
    echo "RESULT: the harness has a gap - a broken writer passed it"
    exit 1
fi
echo "RESULT: every testable mutant was caught"
