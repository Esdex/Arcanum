#!/usr/bin/env bash
# Measures trunccheck.py, not the truncation code.
#
#   ./mutants-trunc.sh <cases-dir>
#
# Shrinking is the direction with the two silent failures. Blocks dropped without
# being freed leak: the file shrinks, every structure stays valid, every checksum
# matches, and the space is simply gone. Blocks freed while still referenced is
# the mirror image and worse, because the next allocation hands them out twice.
# Neither shows up in a size, a checksum or a structural walk - only in what
# e2fsck reconstructs from the inodes, which is why it runs after every cut.
#
# Only ext4_extwrite.c is mutated; bench, fsmeta and debugfs stay pristine.

set -euo pipefail

CASES="${1:?usage: ./mutants-trunc.sh <cases-dir>}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

cp "$HERE/ext4_extwrite.h" "$HERE/ext4_alloc.h" "$HERE/ext4_alloc.c" \
   "$HERE/ext4_csum.h" "$HERE/ext4_csum.c" "$HERE/extwrite.c" "$WORK/"

fail=0
MODE=half

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
    if "$HERE/trunccheck.py" --cases "$CASES" --mode "$MODE" --bench "$HERE/bench" \
                             --extwrite "$WORK/ew" --fsmeta "$HERE/fsmeta" \
                             >/dev/null 2>&1; then
        if [ -n "$expect_miss" ]; then
            echo "  untestable: $desc"
            echo "              $expect_miss"
        else
            echo "  MISS  $desc ($MODE) - the harness did not catch it"
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

echo "truncation mutation tests (each should read caught, or untestable with a reason):"

# The two silent failures, in both directions.

try "extents dropped without their blocks being freed" \
    's@                    if (ext4_free_block(fs, phys + k)) return EXTW_ERR_FORMAT;@                    if (0) return EXTW_ERR_FORMAT;@'

try "one block too many freed when trimming an extent" \
    's@                for (uint32_t k = nlen; k < len; k++) {@                for (uint32_t k = nlen - 1; k < len; k++) {@'

try "one block too few freed when trimming an extent" \
    's@                for (uint32_t k = nlen; k < len; k++) {@                for (uint32_t k = nlen + 1; k < len; k++) {@'

try "node blocks leaked instead of freed" \
    's@if (ext4_free_block(fs, child)) return EXTW_ERR_FORMAT;@;@'

# The entries themselves.

try "trimmed extent keeps its original length" \
    's@                wr16(e + EE_LEN_OFF,@                if (0) wr16(e + EE_LEN_OFF,@'

try "trimming drops the preallocation marker" \
    's@                     (uint16_t)(uninit ? nlen + EXT4_MAX_INIT_LEN : nlen));@                     (uint16_t)nlen);@'

try "entry count left as it was after cutting" \
    's@wr16(node + EH_ENTRIES_OFF, kept);@;@'

try "the entry straddling the cut is dropped instead of trimmed" \
    's@            } else if (lo + len > keep) {@            } else if (0) {@'

# The inode.

try "i_size not reduced" \
    's@    inode_size_set(inode, want < have ? want : have);@@'

try "i_blocks not reduced" \
    's@    inode_blocks_set(inode, blocks > sectors ? blocks - sectors : 0);@@'

try "freed tree blocks left out of i_blocks" \
    's@    uint64_t sectors = (freed_data + freed_meta) \* (fs->block_size / 512);@    uint64_t sectors = freed_data * (fs->block_size / 512);@'

# Emptying a file, where the root has to stop claiming a depth it no longer has.

MODE=zero

try "emptied root keeps the depth it grew to" \
    's@    if (node_entries(root) == 0) wr16(root + EH_DEPTH_OFF, 0);@@'

try "emptied file keeps its size" \
    's@    inode_size_set(inode, want < have ? want : have);@@'

# Collapsing a tree that no longer needs its depth. e2fsck notices this one by
# itself - "extent tree could be shorter" - which is how it was found.

MODE=half

try "tree left deeper than its contents need" \
    's@    rc = collapse_root(fs, root, storage, &freed_meta);@    rc = EXTW_OK; if (0) rc = collapse_root(fs, root, storage, \&freed_meta);@'

try "collapse pulls up a child too big to fit in the root" \
    's@        if (cent > node_capacity(root)) return EXTW_OK;   /\* would not fit \*/@@'

try "collapse frees the child but leaves the root pointing at it" \
    's@        memcpy(root + 12, storage + 12, (size_t)cent \* 12);@@'

# The round trip, which is the only mode that checks the map comes back exactly.

MODE=roundtrip

try "cut leaves the extent map one block long" \
    's@    rc = truncate_node(fs, root, depth, keep_blocks, storage, 0, inode_seed,@    rc = truncate_node(fs, root, depth, keep_blocks + 1, storage, 0, inode_seed,@'

echo
if [ "$fail" -ne 0 ]; then
    echo "RESULT: the harness has a gap - broken truncation passed it"
    exit 1
fi
echo "RESULT: every testable mutant was caught"
