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

. "$HERE/sources.sh"
mutant_stage "$HERE" "$WORK" extwrite.c

fail=0
CHECK_EXTRA=()          # extra appendcheck.py arguments for the mutants below

# try <description> <sed-script> [expect-miss-reason]
try() {
    local desc="$1" expr="$2" expect_miss="${3:-}"
    mutant_reset "$HERE" "$WORK"
    sed -i "$expr" "$WORK/ext4_extwrite.c"
    if ! mutant_changed "$HERE" "$WORK"; then
        echo "  SKIP  $desc - the pattern did not match, so nothing was mutated"
        fail=1
        return
    fi
    if ! mutant_build "$WORK" extwrite.c ew; then
        echo "  SKIP  $desc - mutant did not build"
        fail=1
        return
    fi
    if "$HERE/appendcheck.py" --cases "$CASES" --bench "$HERE/bench" \
                              --extwrite "$WORK/ew" --fsmeta "$HERE/fsmeta" \
                              ${CHECK_EXTRA[@]+"${CHECK_EXTRA[@]}"} >/dev/null 2>&1; then
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
    's@    inode_blocks_set(inode, inode_blocks_get(inode) +@    if (0) inode_blocks_set(inode, inode_blocks_get(inode) +@'

try "i_blocks counted in filesystem blocks, not 512-byte sectors" \
    's@(data_blocks + meta_blocks) \* (fs->block_size / 512));@(data_blocks + meta_blocks));@'

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
    's@            wr16(leaf + EH_ENTRIES_OFF, (uint16_t)(ent + 1));@@'

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

# Pushing a full root down into a block of its own. Reached by the 28 files whose
# root already held four extents, which before this existed were skipped outright.

try "new node written without its tail checksum" \
    's@    wr32(buf + off, ext4_extent_block_csum(inode_seed, buf, fs->block_size));@@'

try "root depth not increased after the split" \
    's@    wr16(root + EH_DEPTH_OFF, (uint16_t)(depth + 1));@@'

try "index keyed on the wrong logical block" \
    's@    wr32(root + 12 + EI_BLOCK_OFF, first_logical);@    wr32(root + 12 + EI_BLOCK_OFF, first_logical + 1);@'

try "index points one block past the node it moved" \
    's@    ei_set_child(root + 12, (uint64_t)blk);@    ei_set_child(root + 12, (uint64_t)blk + 1);@'

try "moved node claims one entry fewer than it holds" \
    's@    wr16(scratch + EH_ENTRIES_OFF, ent);@    wr16(scratch + EH_ENTRIES_OFF, (uint16_t)(ent - 1));@'

try "moved node keeps the root's capacity of four" \
    's@    wr16(scratch + EH_MAX_OFF, (uint16_t)entries_per_extent_block(fs));@    wr16(scratch + EH_MAX_OFF, 4);@'

try "the tree's own block not counted in i_blocks" \
    's@    (\*meta_blocks)++;@@'

try "capacity computed without reserving the tail" \
    's@    return (fs->block_size - 12 - 4) / 12;@    return (fs->block_size - 12) / 12;@' \
    "At 1 KiB, 2 KiB and 4 KiB the two forms give the same answer - 84, 169 and 340 -
              because the division discards the remainder the tail would have used. Only a
              block size where 12 divides differently would push the tail past the end of
              the block, and mke2fs will not make one here. Held up by review alone."

# Giving a full leaf an empty sibling. Only reached once a leaf actually fills,
# which three blocks never do - hence the larger append for this group.

CHECK_EXTRA=(--count 900 --limit 8)

try "new sibling leaf keyed on the wrong logical block" \
    's@        wr32(slot + EI_BLOCK_OFF, next_logical);@        wr32(slot + EI_BLOCK_OFF, next_logical + 1);@'

try "new sibling leaf not marked empty" \
    's@        wr16(leaf + EH_ENTRIES_OFF, 0);@        wr16(leaf + EH_ENTRIES_OFF, 1);@'

try "parent not told it gained a child" \
    's@        wr16(pn + EH_ENTRIES_OFF, (uint16_t)(pe + 1));@@'

try "parent left unwritten after gaining a child" \
    's@        rc = flush_level(fs, p, parent, inode_seed);@        rc = EXTW_OK;@'

try "new sibling given the depth of a leaf it is not" \
    's@        wr16(leaf + EH_DEPTH_OFF, 0);@        wr16(leaf + EH_DEPTH_OFF, 1);@'

# Running out of space part way. Needs an append far larger than the image, so
# that the short-write path is the one being exercised.

CHECK_EXTRA=(--count 100000 --limit 4)

# Anchored on `int append_rc`, which only the append path has. The obvious
# anchor - the write_inode call - now appears in the truncation path too, where
# there is no append_rc, so mutating both stopped compiling and the suite
# reported SKIP rather than quietly testing nothing.
try "a short append abandoned instead of committed" \
    's@    int append_rc = rc;@    int append_rc = rc; if (rc != EXTW_OK) goto out;@'

try "a short append reports the full count as written" \
    's@    if (appended) \*appended = (uint32_t)data_blocks;@    if (appended) *appended = count;@'

CHECK_EXTRA=()

echo
if [ "$fail" -ne 0 ]; then
    echo "RESULT: the harness has a gap - a broken writer passed it"
    exit 1
fi
echo "RESULT: every testable mutant was caught"
