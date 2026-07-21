#!/usr/bin/env bash
# Measures fsckcheck.py, not the allocator. 40/40 green means nothing until a
# deliberately broken allocator is shown to fail, and the write path is where that
# matters most: a wrong reader returns a wrong number, but a wrong writer can
# leave every structure well-formed and individually checksummed with no
# agreement between them.
#
#   ./mutants-alloc.sh <cases-dir>
#
# Only ext4_alloc.c is mutated. fsmeta stays built from pristine sources on
# purpose - it is the independent checksum oracle, and a mutant that broke both it
# and the allocator together would agree with itself and be reported as a pass.

set -euo pipefail

CASES="${1:?usage: ./mutants-alloc.sh <cases-dir>}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

cp "$HERE/ext4_alloc.h" "$HERE/ext4_csum.h" "$HERE/ext4_csum.c" "$HERE/alloc.c" "$WORK/"

fail=0
CHECK_EXTRA=()          # extra fsckcheck.py arguments for the mutants below

# try <description> <sed-script> [expect-miss-reason]
#
# A third argument marks a mutant the corpus provably cannot catch, with the
# reason. Those are reported but do not fail the run - hiding them would be worse,
# since an unreachable path is exactly the kind of thing that goes wrong later.
try() {
    # Delimiter is @: both / and | appear in the C being matched.
    local desc="$1" expr="$2" expect_miss="${3:-}"
    cp "$HERE/ext4_alloc.c" "$WORK/m.c"
    sed -i "$expr" "$WORK/m.c"
    if cmp -s "$HERE/ext4_alloc.c" "$WORK/m.c"; then
        echo "  SKIP  $desc - the pattern did not match, so nothing was mutated"
        fail=1
        return
    fi
    if ! (cd "$WORK" && cc -O2 -std=c99 -o am alloc.c m.c ext4_csum.c 2>/dev/null); then
        echo "  SKIP  $desc - mutant did not build"
        fail=1
        return
    fi
    if "$HERE/fsckcheck.py" --cases "$CASES" --alloc "$WORK/am" \
                            --fsmeta "$HERE/fsmeta" \
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

echo "allocator mutation tests (each should read caught, or untestable with a reason):"

# The five updates, each omitted in turn. e2fsck reports a different complaint for
# every one of these, which is what makes it usable as an oracle here at all.

try "superblock free count never decremented" \
    's@sb_set_free_blocks(fs, ext4_sb_free_blocks(fs) - 1);@;@'

try "group free count never decremented" \
    's@group_set_free_blocks(fs, d, group_free_blocks(fs, d) - 1);@;@'

try "bitmap checksum not recomputed after the write" \
    's@store_bitmap_csum(fs, d);@;@'

try "descriptor checksum not recomputed" \
    's@store_desc_csum(fs, g, d);@;@'

try "superblock checksum not recomputed on flush" \
    's@wr32(fs->sb + EXT4_SB_CSUM_OFF, ext4_superblock_csum(fs->sb));@;@'

# Ordering. Each structure ends up individually well-formed and the checksums are
# all present - they just cover the wrong bytes. This is the class fsck exists for.

try "descriptor checksum computed before the free count is updated" \
    's@group_set_free_blocks(fs, d, group_free_blocks(fs, d) - 1);@store_desc_csum(fs, g, d); group_set_free_blocks(fs, d, group_free_blocks(fs, d) - 1);@; s@store_desc_csum(fs, g, d);.*4.*@@'

# Accounting that stays self-consistent but does not match the bitmap.

try "group free count decremented twice per block" \
    's@group_set_free_blocks(fs, d, group_free_blocks(fs, d) - 1);@group_set_free_blocks(fs, d, group_free_blocks(fs, d) - 2);@'

try "already-set bits handed out again" \
    's@if (fs->bitmap.bit >> 3. & (1u << (bit & 7))) continue;@@'

try "high half of the bitmap checksum dropped" \
    's@if (is_64bit(fs)) wr16(d + EXT4_GD_BBITMAP_CSUM_HI_OFF, (uint16_t)(c >> 16));@@'

# The free path, reached only by the round-trip leg of the harness.

try "free does not clear the bit" \
    's@fs->bitmap.bit >> 3. &= (uint8_t)~(1u << (bit & 7));@@'

try "free does not give the block back to the group count" \
    's@group_set_free_blocks(fs, d, group_free_blocks(fs, d) + 1);@;@'

# Filling the image. Taking nine blocks never gets past the first group with room
# in it, so the rule about BLOCK_UNINIT groups is not exercised at all until
# everything in front of them has been taken. A few images are enough - filling is
# the slow part of the suite, and the rule does not vary per image.

CHECK_EXTRA=(--fill --limit 4)

try "BLOCK_UNINIT groups allocated from anyway" \
    's@if (rd16(d + EXT4_GD_FLAGS_OFF) & EXT4_BG_BLOCK_UNINIT) return ALLOC_NONE;@@'

try "allocation stops one group early" \
    's@for (uint32_t g = 0; g < fs->groups; g++) {@for (uint32_t g = 0; g + 1 < fs->groups; g++) {@'

CHECK_EXTRA=()

# Paths the corpus cannot reach. Reported rather than hidden.

try "bit search not clamped to the group's real block count" \
    's@uint32_t limit = group_block_count(fs, g);@uint32_t limit = fs->blocks_per_group;@' \
    "The padding bits covering blocks past the end of a final partial group are
              already set to 1 by mke2fs, so find-first-zero steps over them whether or not
              it is told the real limit. Filling the image does not reach it either, since
              the scan still finds those bits set. The clamp only bites on a filesystem
              whose padding was never written - one we format ourselves, later. Held up by
              review alone."

try "high half of the 64-bit group free count dropped" \
    's@if (is_64bit(fs)) wr16(d + EXT4_GD_FREE_BLOCKS_HI_OFF, (uint16_t)(v >> 16));@@' \
    "bg_free_blocks_count cannot exceed blocks_per_group, which is 8 * block_size and so
              at most 32768 anywhere in this corpus. The high half is therefore always zero
              and writing it is a no-op. It only becomes reachable at a 64 KiB block size,
              which needs a page size no phone has."

echo
if [ "$fail" -ne 0 ]; then
    echo "RESULT: the harness has a gap - a broken allocator passed it"
    exit 1
fi
echo "RESULT: every testable mutant was caught"
