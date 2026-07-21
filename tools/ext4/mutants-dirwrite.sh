#!/usr/bin/env bash
# Measures dirwcheck.py, not the directory writer.
#
#   ./mutants-dirwrite.sh <cases-dir>
#
# Adding and removing an entry are edits to a linked list held in a fixed-size
# block, and both share one failure: a chain that no longer adds up to exactly the
# block. Overshoot walks into the checksum tail; undershoot leaves a hole no
# reader will ever visit, so the space is gone for good. Neither is a checksum
# failure - the block is rewritten and restamped either way.

set -euo pipefail

CASES="${1:?usage: ./mutants-dirwrite.sh <cases-dir>}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

. "$HERE/sources.sh"
mutant_stage "$HERE" "$WORK" dirwrite.c

fail=0

try() {
    local desc="$1" expr="$2" expect_miss="${3:-}"
    mutant_reset "$HERE" "$WORK"
    sed -i "$expr" "$WORK/ext4_dirwrite.c"
    if ! mutant_changed "$HERE" "$WORK"; then
        echo "  SKIP  $desc - the pattern did not match, so nothing was mutated"
        fail=1
        return
    fi
    if ! mutant_build "$WORK" dirwrite.c dw; then
        echo "  SKIP  $desc - mutant did not build"
        fail=1
        return
    fi
    if "$HERE/dirwcheck.py" --cases "$CASES" --bench "$HERE/bench" \
                            --dirwrite "$WORK/dw" >/dev/null 2>&1; then
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

echo "directory writer mutation tests (each should read caught, or untestable with a reason):"

# Splitting a gap. The chain has to still add up to the block afterwards.

try "split leaves the old entry claiming its original length" \
    's@                    wr16(buf + off + 4, (uint16_t)used);   /\* shrink to fit \*/@@'

try "new entry given the whole gap instead of what is left of it" \
    's@                    wr16(slot + 4, (uint16_t)(rec - used));@                    wr16(slot + 4, (uint16_t)rec);@'

try "entry sized without rounding up to four" \
    's@    return (DIRENT_HEADER + (uint32_t)name_len + 3u) \& ~3u;@    return DIRENT_HEADER + (uint32_t)name_len;@'

try "gap measured against the record rather than what the name needs" \
    's@            uint32_t used = (cur_ino == 0) ? 0 : entry_size(nlen);@            uint32_t used = 0;@'

try "a gap one record too small accepted" \
    's@            if (rec - used >= need) {@            if (rec - used + 4 >= need) {@'

# The entry that gets written.

try "name length stored without the name" \
    's@                memcpy(slot + DIRENT_HEADER, name, name_len);@@'

try "file type and name length written to each other.s field" \
    's@                slot\[6\] = name_len;@                slot[6] = file_type; slot[7] = name_len; if (0)@'

# Removing. The gap has to go back to the entry in front, exactly.

# Not included: blanking a removed entry instead of merging it into the one in
# front. That leaves a dead entry holding its own rec_len, so the chain still adds
# up and every reader steps over it - it is a different valid strategy, not a
# defect, and the harness rightly cannot tell the two apart.

try "removal of the first entry shortens the chain" \
    's@                    wr32(buf + off, 0);@                    wr32(buf + off, 0); wr16(buf + off + 4, DIRENT_HEADER);@' \
    "Only fires when the entry being removed is the first in its block, and the
              harness's entry never is - it is placed into whatever gap exists, which is
              never at offset zero because \".\" and the entries before it are live. Reaching
              it needs an entry deliberately placed first, which means emptying a block
              first. Held up by review alone." 

try "removal gives back one record too many" \
    's@(uint16_t)(rd16(buf + prev + 4) + rec)@(uint16_t)(rd16(buf + prev + 4) + rec + 4)@'

# Duplicates and the checksum.

try "duplicate names allowed" \
    's@                rc = EXT4_DIRW_ERR_EXISTS;@                rc = EXT4_DIRW_OK; if (0)@'

try "block written without restamping its checksum" \
    's@        wr32(tail + 8, ext4_crc32c(seed, buf, w->block_size - DIR_TAIL_SIZE));@@'

try "chain allowed to run into the checksum tail" \
    's@        return w->block_size - DIR_TAIL_SIZE;@        return w->block_size;@' \
    "The tail is only ever reached by a search that found no gap before it, and every
              directory mke2fs builds has one - even the densest in the corpus, case-014's
              root, which has no room for 24 bytes but does have the 12 a short name needs
              and spends it well before the tail. Reaching this needs a directory packed
              with no free gap at all, which nothing here produces. The guard is what stops
              a name being written over the checksum; held up by review alone."


echo
if [ "$fail" -ne 0 ]; then
    echo "RESULT: the harness has a gap - a broken writer passed it"
    exit 1
fi
echo "RESULT: every testable mutant was caught"
