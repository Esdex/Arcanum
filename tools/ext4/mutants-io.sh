#!/usr/bin/env bash
# Measures the whole write-path harness against a broken read-modify-write layer.
#
#   ./mutants-io.sh <cases-dir>
#
# ext4_io.c is what turns a sub-block update - an inode, a bitmap, the superblock -
# into whole-block operations the device can actually perform. Its failure is the
# quiet kind: a splice at the wrong offset, or a partial write that does not read
# its block first and so wipes the bytes around what it meant to change. Every
# suite that writes a structure smaller than a block runs through this, so the
# existing harness is the test; this just proves a broken version fails it.
#
# fsckcheck.py is the driver because it is the shortest write path that touches
# all three sub-block structures: bitmap, descriptor, superblock.

set -euo pipefail

CASES="${1:?usage: ./mutants-io.sh <cases-dir>}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

. "$HERE/sources.sh"
mutant_stage "$HERE" "$WORK" alloc.c
mutant_stage "$HERE" "$WORK" extwrite.c

fail=0

try() {
    local desc="$1" expr="$2" expect_miss="${3:-}"
    mutant_reset "$HERE" "$WORK"
    sed -i "$expr" "$WORK/ext4_io.c"
    if ! mutant_changed "$HERE" "$WORK"; then
        echo "  SKIP  $desc - the pattern did not match, so nothing was mutated"
        fail=1; return
    fi
    if ! mutant_build "$WORK" alloc.c am || ! mutant_build "$WORK" extwrite.c ew; then
        echo "  SKIP  $desc - mutant did not build"
        fail=1; return
    fi
    # fsckcheck writes the block-aligned structures (bitmap, descriptor) and the
    # superblock at its byte-1024 offset; appendcheck reads and writes inodes,
    # which sit at a non-zero offset inside their block and so are the only thing
    # that exercises a splice offset on the read side. Both are needed - the
    # offset-read bug is invisible to fsckcheck alone.
    if "$HERE/fsckcheck.py" --cases "$CASES" --alloc "$WORK/am" \
                            --fsmeta "$HERE/fsmeta" >/dev/null 2>&1 \
       && "$HERE/appendcheck.py" --cases "$CASES" --extwrite "$WORK/ew" \
                            --bench "$HERE/bench" --fsmeta "$HERE/fsmeta" \
                            --limit 8 >/dev/null 2>&1; then
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

echo "block-IO mutation tests (each should read caught, or untestable with a reason):"

# The read-modify-write itself.

# "partial write skips its read" is the same failure as taking the whole-block
# fast path for a partial write, which is the last mutant below and is caught.

try "splice offset ignored, changed bytes land at the block start" \
    's@            memcpy(block + inpart, p, chunk);@            memcpy(block, p, chunk);@'

try "read splices from the block start instead of the offset" \
    's@            memcpy(p, block + inpart, chunk);@            memcpy(p, block, chunk);@'

try "chunk not clamped to the block, so a range overruns" \
    's@        if (chunk > len) chunk = (uint32_t)len;@@'

try "the in-block offset computed against the wrong block" \
    's@        uint32_t inpart = (uint32_t)(off % io->block_size);@        uint32_t inpart = 0;@'

try "whole-block fast path taken for partial writes too" \
    's@        int whole = (inpart == 0 && chunk == io->block_size);@        int whole = 1;@'

echo
if [ "$fail" -ne 0 ]; then
    echo "RESULT: the harness has a gap - a broken IO layer passed it"
    exit 1
fi
echo "RESULT: every testable mutant was caught"
