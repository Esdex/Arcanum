#!/usr/bin/env bash
# Measures the corpus, not the reader: breaks the reader on purpose and checks
# that check.py notices. A corpus that passes a broken reader is worth nothing,
# and that is not hypothetical - the first version of this suite missed a reader
# that ignored the uninitialised-extent bit entirely, because no generated image
# had one until fallocate was added.
#
#   ./mutants.sh /tmp/ext4cases

set -euo pipefail

CASES="${1:?usage: ./mutants.sh <cases-dir>}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

cp "$HERE/ext4_extents.h" "$HERE/ext4_csum.h" "$HERE/bench.c" "$WORK/"

fail=0

# try <description> <sed-expression> [expect-miss-reason]
#
# A third argument marks a mutant the corpus provably cannot catch, with the
# reason. Those are reported but do not fail the run - hiding them would be worse,
# since an unreachable path is exactly the kind of thing that goes wrong later.
try() {
    # Delimiter is @ rather than / or |, both of which appear in the C being
    # matched - a bitwise or in an expression silently ends the sed command.
    local desc="$1" expr="$2" expect_miss="${3:-}"
    cp "$HERE/ext4_extents.c" "$WORK/m.c"
    cp "$HERE/ext4_csum.c"    "$WORK/c.c"
    # Mutations target either file; whichever one matches is the mutated one.
    sed -i "$expr" "$WORK/m.c"
    sed -i "$expr" "$WORK/c.c"
    if cmp -s "$HERE/ext4_extents.c" "$WORK/m.c" && cmp -s "$HERE/ext4_csum.c" "$WORK/c.c"; then
        echo "  SKIP  $desc - the pattern did not match, so nothing was mutated"
        fail=1
        return
    fi
    if ! (cd "$WORK" && cc -O2 -std=c99 -o bm bench.c m.c c.c 2>/dev/null); then
        echo "  SKIP  $desc - mutant did not build"
        fail=1
        return
    fi
    if "$HERE/check.py" --cases "$CASES" --bench "$WORK/bm" >/dev/null 2>&1; then
        if [ -n "$expect_miss" ]; then
            echo "  untestable: $desc"
            echo "              $expect_miss"
        else
            echo "  MISS  $desc - corpus did not catch it"
            fail=1
        fi
    else
        if [ -n "$expect_miss" ]; then
            echo "  UNEXPECTED CATCH: $desc was marked untestable but the corpus caught it"
            fail=1
        else
            echo "  caught: $desc"
        fi
    fi
}

echo "mutation tests (each should read caught, or untestable with a reason):"

try "physical block halves swapped" \
    's@run->physical = (uint64_t)rd32(e + 8) | ((uint64_t)rd16(e + 6) << 32);@run->physical = (uint64_t)rd16(e + 6) | ((uint64_t)rd32(e + 8) << 32);@'

try "uninitialised length not adjusted" \
    's@run->length   = run->uninit ? (uint32_t)(len - EXT4_MAX_INIT_LEN) : len;@run->length   = len;@'

try "last entry of every node skipped" \
    's@for (uint16_t i = 0; i < eh.entries; i++, entry += 12) {@for (uint16_t i = 0; i + 1 < eh.entries; i++, entry += 12) {@'

try "index child block high bits dropped" \
    's@return (uint64_t)rd32(e + 4) | ((uint64_t)rd16(e + 8) << 32);@return (uint64_t)rd32(e + 4);@' \
    "block numbers only exceed 32 bits past 2^32 blocks, about 16 TiB at 4 KiB. No image this
              harness can make gets there, so the high word is always zero and dropping it changes
              nothing. The path stays unverified by test; it is held up by review alone."

try "entries read from the wrong header field" \
    's@out->entries = rd16(node + 2);@out->entries = rd16(node + 4);@'

# The ones below only bite on the content check - the extent map is identical
# either way, which is the whole reason content is verified separately.

try "preallocated extents copied out instead of zeroed" \
    's@    if (run->uninit) return 0;@@'

try "holes not zero-filled" \
    's@    memset(buf, 0, (size_t)length);@@'

try "read not clamped to file size" \
    's@    if (offset + length > size) length = size - offset;@@'

# Checksums. These pass every read test - a wrong checksum recipe reads data
# perfectly well, it just cannot write.

try "crc32c inverted at the ends, breaking chaining" \
    's@    while (len--) {@    crc = ~crc;\n    while (len--) {@'

# Single line on purpose: sed matches within a line, so a pattern spanning two
# lines silently mutates nothing and the suite reports SKIP rather than a pass.
try "checksum seed folds the generation twice, never the inode number" \
    's@    put_le32(le, ino);@    put_le32(le, generation);@'

try "checksum covers the whole block instead of stopping at the tail" \
    's@    return ext4_crc32c(inode_seed, block, off);@    return ext4_crc32c(inode_seed, block, block_size - 4);@'

try "inode checksum covers only the classic 128 bytes" \
    's@    int has_hi = inode_size > 128 \&\& ext4_inode_has_checksum_hi(inode);@    int has_hi = inode_size > 128 \&\& ext4_inode_has_checksum_hi(inode); inode_size = 128;@'

try "inode checksum does not blank i_checksum_lo" \
    's@    crc = ext4_crc32c(crc, inode, EXT4_INODE_CSUM_LO_OFF);@    crc = ext4_crc32c(crc, inode, EXT4_INODE_CSUM_LO_OFF + 2); if (0)@'

try "inode checksum ignores the high half" \
    's@    int has_hi = inode_size > 128 \&\& ext4_inode_has_checksum_hi(inode);@    int has_hi = 0;@'

echo
if [ "$fail" -ne 0 ]; then
    echo "RESULT: the corpus has a gap - a broken reader passed it"
    exit 1
fi
echo "RESULT: every testable mutant was caught"
