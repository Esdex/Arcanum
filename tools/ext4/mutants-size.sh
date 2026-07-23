#!/usr/bin/env bash
# Measures sizecheck.py, not ext4_set_size.
#
#   ./mutants-size.sh <cases-dir>
#
# set_size is a small function - it validates a byte length against the file's
# last block and stamps i_size - but every part of it is a place to be off by one.
# The range check has two boundaries, each measured against what e2fsck accepts;
# a mutant that widens either lets through a size e2fsck rejects or that reaches
# past the blocks the file has. And the length itself has to be stamped exactly:
# a size rounded to a block would pass a reader that stops at a block boundary and
# fail everyone else, which is the disagreement the interop rung exists to catch.

set -euo pipefail

CASES="${1:?usage: ./mutants-size.sh <cases-dir>}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

. "$HERE/sources.sh"
mutant_stage "$HERE" "$WORK" extwrite.c

fail=0

try() {
    local desc="$1" expr="$2" expect_miss="${3:-}"
    mutant_reset "$HERE" "$WORK"
    sed -i "$expr" "$WORK/ext4_extwrite.c"
    if ! mutant_changed "$HERE" "$WORK"; then
        echo "  SKIP  $desc - the pattern did not match, so nothing was mutated"
        fail=1; return
    fi
    if ! mutant_build "$WORK" extwrite.c ew; then
        echo "  SKIP  $desc - mutant did not build"
        fail=1; return
    fi
    if "$HERE/sizecheck.py" --cases "$CASES" --limit 4 --bench "$HERE/bench" \
                            --dirwrite "$HERE/dirwrite" --extwrite "$WORK/ew" \
                            --mkfs "$HERE/mkfs" >/dev/null 2>&1; then
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

echo "set_size mutation tests (each should read caught, or untestable with a reason):"

# ── the length itself ────────────────────────────────────────────────────────

try "the size rounded up to a whole block" \
    's@    inode_size_set(inode, size);@    inode_size_set(inode, (uint64_t)end_logical * fs->block_size);@'

try "the size left as the block-aligned value append set" \
    's@    inode_size_set(inode, size);@@'

try "the size stamped one byte short" \
    's@    inode_size_set(inode, size);@    inode_size_set(inode, size - 1);@'

# ── the range check, against e2fsck's own boundary ───────────────────────────

try "no lower bound, so a size below the last block is allowed" \
    's@    if (size < lo || size > hi) {@    if (size > hi) {@'

try "no upper bound, so a size past the mapped blocks is allowed" \
    's@    if (size < lo || size > hi) {@    if (size < lo) {@'

try "the range check dropped entirely" \
    's@    if (size < lo || size > hi) {@    if (0) {@'

try "the floor put one block too low" \
    's@                  : (uint64_t)(end_logical - 1) \* fs->block_size;@                  : (uint64_t)(end_logical - 2) * fs->block_size;@'

try "the ceiling put one block too high" \
    's@    uint64_t hi = (uint64_t)end_logical \* fs->block_size;@    uint64_t hi = (uint64_t)(end_logical + 1) * fs->block_size;@'

# ── the logical end the bounds are built on ──────────────────────────────────

try "the file's end read from the block field, ignoring the extent length" \
    's@    \*end_out = rd32(last + EE_BLOCK_OFF) + len;@    *end_out = rd32(last + EE_BLOCK_OFF);@'

echo
if [ "$fail" -ne 0 ]; then
    echo "RESULT: the harness has a gap - a broken set_size passed it"
    exit 1
fi
echo "RESULT: every testable mutant was caught"
