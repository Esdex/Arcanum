#!/usr/bin/env bash
# Measures writeatcheck.py against ext4_write_at (ext4_extwrite.c).
#
#   ./mutants-writeat.sh
#
# ext4_write_at is built from the append and set-size paths, which have their own
# suites; the one thing it adds is the arithmetic that routes each byte - the
# in-block offset, the split between overwriting and appending, the exact length,
# the refusal of a hole. Every mutant here corrupts exactly one and the byte-exact
# read-back (or the refusal check) has to notice.
#
# The first mutant IS the classic splice bug - bytes written at the block's start
# instead of the offset within it - the same shape as the chunked-write device bug.
#
# mkfs is not rebuilt: it lays down the input and shares none of the arithmetic
# these mutants touch.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

. "$HERE/sources.sh"
mutant_stage "$HERE" "$WORK" writeat.c

fail=0

try() {
    local desc="$1" expr="$2"
    mutant_reset "$HERE" "$WORK"
    sed -i "$expr" "$WORK/ext4_extwrite.c"
    if ! mutant_changed "$HERE" "$WORK"; then
        echo "  SKIP  $desc - the pattern did not match, so nothing was mutated"
        fail=1; return
    fi
    if ! (cd "$WORK" && cc -O2 -std=c99 -o wa writeat.c $EXT4_SOURCES 2>/dev/null); then
        echo "  SKIP  $desc - mutant did not build"
        fail=1; return
    fi
    if "$HERE/writeatcheck.py" --mkfs "$HERE/mkfs" --writeat "$WORK/wa" >/dev/null 2>&1; then
        echo "  MISS  $desc - the harness did not catch it"; fail=1
    else
        echo "  caught: $desc"
    fi
}

echo "positional-write mutation tests (each should read caught):"

# The classic splice bug: the byte slice written at the block's start rather than at
# its offset within the block. \* is escaped so sed reads it as a literal multiply.
try "the overwrite ignores the in-block offset (bytes at the block's start)" \
    's@phys \* (uint64_t)bs + (from - blk0)@phys * (uint64_t)bs@'

try "the overwrite reads its source bytes from the wrong place" \
    's@data + (from - offset)@data + (from - blk0)@'

try "a write starting past the end is no longer refused (a silent hole)" \
    's@if (offset > i_size) {@if (0) {@'

try "the exact length is never set after growing the file" \
    's@if (end > i_size) {@if (0) {@'

try "the overwrite runs one block past what the file has" \
    's@uint32_t hi = last < mapped - 1 ? last : mapped - 1;@uint32_t hi = last < mapped - 1 ? last : mapped;@'

try "one fewer block is appended than the write needs" \
    's@uint32_t count = last - mapped + 1;@uint32_t count = last - mapped;@'

try "a write into a hole inside the file is no longer refused" \
    's@if (phys == 0 || uninit) {@if (0) {@'

echo
if [ "$fail" -ne 0 ]; then
    echo "RESULT: the harness has a gap - a broken positional write passed it"
    exit 1
fi
echo "RESULT: every mutant was caught"
