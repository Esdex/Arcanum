#!/usr/bin/env bash
# Measures mkdircheck.py, not mkdir.
#
#   ./mutants-mkdir.sh <cases-dir>
#
# Almost everything a directory is made of has its own suite already: the inode
# allocator, the extent writer, the entry writer. What is new here is the three
# things a directory needs that a file does not - its own first block, a link
# added to its parent, and bg_used_dirs_count - and none of the three changes a
# listing. A mutant dropping any of them produces a filesystem that lists
# correctly and is wrong, which is the whole reason e2fsck is the oracle rather
# than our own reader.

set -euo pipefail

CASES="${1:?usage: ./mutants-mkdir.sh <cases-dir>}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

. "$HERE/sources.sh"
mutant_stage "$HERE" "$WORK" dirwrite.c

fail=0

# try <desc> <sed-expr> [file] [why-untestable]
try() {
    local desc="$1" expr="$2" file="${3:-ext4_create.c}" expect_miss="${4:-}"
    mutant_reset "$HERE" "$WORK"
    sed -i "$expr" "$WORK/$file"
    if ! mutant_changed "$HERE" "$WORK"; then
        echo "  SKIP  $desc - the pattern did not match, so nothing was mutated"
        fail=1; return
    fi
    if ! mutant_build "$WORK" dirwrite.c dw; then
        echo "  SKIP  $desc - mutant did not build"
        fail=1; return
    fi
    if "$HERE/mkdircheck.py" --cases "$CASES" --limit 4 --bench "$HERE/bench" \
                             --dirwrite "$WORK/dw" --mkfs "$HERE/mkfs" \
                             >/dev/null 2>&1; then
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

echo "mkdir and rmdir mutation tests (each should read caught, or untestable with a reason):"

# ── the three things a file has no equivalent of ─────────────────────────────

try "the parent gains no link for the new .." \
    's@    if (ext4_inode_adjust_links(w, dir_ino, 1) != EXTW_OK) return EXT4_DIRW_ERR_IO;@@'

try "the parent keeps the link when the directory goes" \
    's@    if (ext4_inode_adjust_links(w, dir_ino, -1) != EXTW_OK) return EXT4_DIRW_ERR_IO;@@'

try "bg_used_dirs_count not raised for a new directory" \
    's@    if (ext4_adjust_used_dirs(w, (uint32_t)ino, 1)) return EXT4_DIRW_ERR_IO;@@'

try "bg_used_dirs_count not lowered when one is removed" \
    's@    if (ext4_adjust_used_dirs(w, ino, -1)) return EXT4_DIRW_ERR_IO;@@'

try "the directory counter moved without restamping the descriptor" \
    '/^int ext4_adjust_used_dirs/,/^}/ s@    store_desc_csum(fs, g, d);@@' \
    ext4_ialloc.c

# ── the new directory's own first block ──────────────────────────────────────

try "the first block allocated but never formatted" \
    's@    uint32_t limit = c->block_size - DIR_TAIL_SIZE;@    uint32_t limit = c->block_size - DIR_TAIL_SIZE; return 0;@'

try "no . entry in the new directory" \
    's@    wr32(buf, c->ino);@    wr32(buf, 0);@'

try "the new .. points at the directory itself" \
    's@    wr32(up, c->parent);@    wr32(up, c->ino);@'

try ".. does not stretch to the end of the block" \
    's@    wr16(up + 4, (uint16_t)(limit - 12));@    wr16(up + 4, 12);@'

try "the new block left without its checksum tail" \
    's@    ext4_dir_stamp_tail(buf, c->block_size, c->seed);@@'

# ext4_dir_stamp_tail is shared with directory growth, where the wrong-length
# checksum is masked because the next write restamps the block. mkdir stamps a
# fresh block nothing rewrites, so here it is observable - this is the suite that
# owns that coverage. Lives in ext4_dirwrite.c, hence the file argument.
try "the new block's checksum covers the tail as well" \
    's@    wr32(tail + 8, ext4_crc32c(seed, block, block_size - DIR_TAIL_SIZE));@    wr32(tail + 8, ext4_crc32c(seed, block, block_size));@' \
    ext4_dirwrite.c

try "a new directory given one link instead of two" \
    's@               2, when);@               1, when);@'

try "the new inode not marked as a directory" \
    's@(uint16_t)(EXT4_S_IFDIR | (mode \& 0x0FFF)),@(uint16_t)(EXT4_S_IFREG | (mode \& 0x0FFF)),@'

try "the entry in the parent typed as a regular file" \
    's@EXT4_FT_DIR, name);@EXT4_FT_REG_FILE, name);@'

# ── what removing has to refuse ──────────────────────────────────────────────
# Both of these do their damage before anything can notice: the name is gone and
# the blocks are back on the free list by the time the inconsistency exists.

try "a populated directory removed anyway" \
    's@    if (nonempty) {@    if (0) {@'

try "rmdir does not check that the name is a directory" \
    's@    if ((rd16(inode + INODE_MODE_OFF) \& EXT4_S_IFMT) != EXT4_S_IFDIR) {@    if (0) {@'

# ── removing the rest of the way ─────────────────────────────────────────────

try "the removed directory's inode not handed back" \
    '/^int ext4_rmdir/,/^}/ s@    if (ext4_free_inode(w, ino)) return EXT4_DIRW_ERR_IO;@@'

try "the removed directory left without a deletion time" \
    '/^int ext4_rmdir/,/^}/ s@    wr32(dead + INODE_DTIME_OFF, when);@@'

try "the directory counter allowed to wrap below zero" \
    's@    if (delta < 0 \&\& v == 0) return -1;@@' \
    ext4_ialloc.c \
    "Reaching it needs bg_used_dirs_count to already be zero in a group that still
              holds a directory, which is a filesystem this cannot produce without first
              breaking the counter some other way. The guard is what stops one missed
              increment from turning into a count of 65535 directories, so it is stated
              rather than dropped."

echo
if [ "$fail" -ne 0 ]; then
    echo "RESULT: the harness has a gap - a broken mkdir passed it"
    exit 1
fi
echo "RESULT: every testable mutant was caught"
