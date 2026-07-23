#!/usr/bin/env bash
# Measures chunkcheck.py, not the chunked write.
#
#   ./mutants-chunk.sh
#
# chunkwrite reproduces the JNI write path, and the bug this suite exists for was
# subtle: the file was written whole and every byte was there, just some in the
# wrong place - a chunk indexed by its file-absolute block instead of its offset
# within the chunk. A harness that only checked the length, or read the file back
# with the same wrong assumption, would have passed it. So the mutants here move
# bytes to the wrong offset without changing how many there are, and chunkcheck
# has to notice - which it can only do because it compares every byte to a
# position-dependent pattern through another driver.
#
# The first mutant IS the device bug. It must be caught.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

. "$HERE/sources.sh"
mutant_stage "$HERE" "$WORK" chunkwrite.c

fail=0

try() {
    local desc="$1" expr="$2"
    mutant_reset "$HERE" "$WORK"
    cp "$HERE/chunkwrite.c" "$WORK/chunkwrite.c"
    sed -i "$expr" "$WORK/chunkwrite.c"
    if cmp -s "$HERE/chunkwrite.c" "$WORK/chunkwrite.c"; then
        echo "  SKIP  $desc - the pattern did not match, so nothing was mutated"
        fail=1; return
    fi
    if ! (cd "$WORK" && cc -O2 -std=c99 -o cw chunkwrite.c $EXT4_SOURCES 2>/dev/null); then
        echo "  SKIP  $desc - mutant did not build"
        fail=1; return
    fi
    if "$HERE/chunkcheck.py" --mkfs "$HERE/mkfs" --chunkwrite "$WORK/cw" \
                             --bench "$HERE/bench" >/dev/null 2>&1; then
        echo "  MISS  $desc - the harness did not catch it"; fail=1
    else
        echo "  caught: $desc"
    fi
}

echo "chunked-write mutation tests (each should read caught):"

# The device bug itself: the fill indexes by the file-absolute block, so every
# chunk after the first reads the wrong slice of its data.
try "the fill ignores the chunk's base block (the original device bug)" \
    's@uint64_t chunk_off = (uint64_t)(logical - s->base_logical) \* s->bs;@uint64_t chunk_off = (uint64_t)logical * s->bs;@'

try "the chunk's base block computed as zero" \
    's@src_t s = { off, n, w.block_size, (uint32_t)(off / w.block_size) };@src_t s = { off, n, w.block_size, 0 };@'

try "the size set to the chunk length rather than the running total" \
    's@if (ext4_set_size(&w, ino, off + n) != EXTW_OK)@if (ext4_set_size(\&w, ino, n) != EXTW_OK)@'

try "the pattern written without its absolute offset" \
    's@buf\[k\] = rel < s->len ? pat(s->start + rel) : 0;@buf[k] = rel < s->len ? pat(rel) : 0;@'

echo
if [ "$fail" -ne 0 ]; then
    echo "RESULT: the harness has a gap - a misplaced-byte write passed it"
    exit 1
fi
echo "RESULT: every mutant was caught"
