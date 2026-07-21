#!/usr/bin/env bash
# Measures dircheck.py, not the directory reader.
#
#   ./mutants-dir.sh <cases-dir>
#
# A directory is the structure where a wrong walk is least likely to look wrong.
# Deleted names are still lying in the blocks - deletion grows the previous
# entry's rec_len over them rather than blanking them - so a reader that drifts
# off the rec_len chain does not crash or come back empty. It comes back with a
# list that reads perfectly well and contains files that no longer exist.

set -euo pipefail

CASES="${1:?usage: ./mutants-dir.sh <cases-dir>}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

. "$HERE/sources.sh"
mutant_stage "$HERE" "$WORK" bench.c

fail=0

try() {
    local desc="$1" expr="$2" expect_miss="${3:-}"
    mutant_reset "$HERE" "$WORK"
    sed -i "$expr" "$WORK/ext4_dir.c"
    if ! mutant_changed "$HERE" "$WORK"; then
        echo "  SKIP  $desc - the pattern did not match, so nothing was mutated"
        fail=1
        return
    fi
    if ! mutant_build "$WORK" bench.c bm; then
        echo "  SKIP  $desc - mutant did not build"
        fail=1
        return
    fi
    if "$HERE/dircheck.py" --cases "$CASES" --bench "$WORK/bm" >/dev/null 2>&1; then
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

echo "directory reader mutation tests (each should read caught, or untestable with a reason):"

# Following the chain. This is the whole of it.

try "walk advances by the entry's size instead of its rec_len" \
    's@        off += rec_len;@        off += DIRENT_HEADER + name_len;@'

try "deleted entries listed as if they were live" \
    's@        if (ino != 0 && name_len != 0 && ftype != EXT4_FT_DIR_CSUM) {@        if (name_len != 0) {@'

try "first entry of every block skipped" \
    's@    uint32_t off = 0;@    uint32_t off = rd16(blk + 4);@'

try "last block of the directory never walked" \
    's@    for (uint64_t off = 0; off < size; off += fs->block_size) {@    for (uint64_t off = 0; off + fs->block_size < size; off += fs->block_size) {@'

# The names themselves.

try "name length read from the file-type byte" \
    's@        uint8_t  name_len = blk\[off + 6\];@        uint8_t  name_len = blk[off + 7];@'

try "name one byte short" \
    's@            e.name\[name_len\] = .\\0.;@            e.name[name_len ? name_len - 1 : 0] = 0;@'

try "inode number read from the rec_len field" \
    's@        uint32_t ino      = rd32(blk + off);@        uint32_t ino      = rd16(blk + off + 4);@'

# The per-block checksum.

try "directory checksum covers the tail as well" \
    's@        uint32_t want = ext4_crc32c(seed, blk, fs->block_size - DIR_TAIL_SIZE);@        uint32_t want = ext4_crc32c(seed, blk, fs->block_size);@'

try "directory checksum seeded without the inode" \
    's@    uint32_t seed = ext4_inode_csum_seed(fs->csum_seed, ino, generation);@    uint32_t seed = fs->csum_seed;@'

# Guards the corpus cannot reach, reported rather than hidden.

try "the checksum tail recognised only by its file-type byte" \
    's@ && ftype != EXT4_FT_DIR_CSUM@@' \
    "The tail's name_len byte is zero, so the name_len test already excludes it and
              removing the file-type test changes nothing. The guard only earns its place
              against a tail whose name_len byte is not zero, which nothing here produces.
              Kept because it states what the tail is rather than relying on a second
              field's value. Held up by review alone."

try "blocks with no tail counted as verified" \
    's@            continue;@            { if (blocks_checked) (*blocks_checked)++; continue; }@' \
    "Every directory block in the corpus carries a tail, because mke2fs turns
              metadata_csum on by default, so the branch that skips a tail-less block is
              never taken and counting it differently changes nothing. It becomes reachable
              against a filesystem made without the feature, which the generator does not
              currently produce."

try "a rec_len of zero accepted" \
    's@        if (rec_len < DIRENT_HEADER || (rec_len \& 3) != 0) return EXT4_ERR_FORMAT;@@' \
    "Every directory in the corpus was built by mke2fs and contains no malformed
              rec_len, so the guard is never the thing that stops a walk. It exists for a
              directory an attacker supplies, where a zero would spin here forever and an
              unaligned one would step outside the block. Reachable only with a corpus of
              deliberately corrupt images, which this is not."

echo
if [ "$fail" -ne 0 ]; then
    echo "RESULT: the harness has a gap - a broken reader passed it"
    exit 1
fi
echo "RESULT: every testable mutant was caught"
