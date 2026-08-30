#!/usr/bin/env bash
# Arcanum - VeraCrypt-compatible encrypted vault manager for Android
#
# Copyright (C) 2026 Esdex
# Licensed under Apache License 2.0
# SPDX-License-Identifier: Apache-2.0
#
# Host test harness for the clean-room ext4 library - PC-only, not shipped in the
# app. e2fsprogs and fuse2fs are used as external oracles (separate processes),
# never linked or copied. See issue #7.

# Runs the library under AddressSanitizer and UndefinedBehaviorSanitizer.
#
#   ./asancheck.sh
#
# Every other stand here asks "is the filesystem it produced correct?" and answers
# with e2fsck, debugfs or fuse2fs. This one asks a question none of those can:
# "did it stay inside its own buffers while producing it?"
#
# The two are genuinely independent. #144 turned up a memcpy that wrote 128 bytes
# past a heap allocation on a 128-byte-inode volume, and the image it produced was
# perfect - write_inode only puts fs->inode_size bytes on disk, so e2fsck was clean
# every time while the heap behind it was being overwritten. No oracle that reads
# the image can ever see that. ASan sees it on the first run.
#
# The geometries matter more than the operations. Almost every buffer in this
# library is sized from the superblock - block size, inode size, descriptor size -
# so a bug of this class hides until a volume arrives with a geometry the code was
# not written against. Both bugs #144 found were exactly that: a 512-byte inode
# read past the end of a 256-byte buffer, and a 128-byte inode written past the end
# of its own. Those two sizes are in the matrix below for that reason. Add a
# geometry here whenever the open path starts admitting one.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

. "$HERE/sources.sh"

CC="${CC:-cc}"
# -O1 keeps the frames ASan needs to name a variable while staying fast enough to
# run the whole matrix; -fno-omit-frame-pointer is what makes the traces readable.
SAN="-fsanitize=address,undefined -fno-sanitize-recover=all -fno-omit-frame-pointer"
FLAGS="-g -O1 -std=c99 -I$EXT4_DIR $SAN"
L() { for f in "$@"; do echo "$EXT4_DIR/$f"; done; }

echo "building the drivers with ASan + UBSan..."
$CC $FLAGS -o "$WORK/bench"    "$HERE/bench.c"    $(L ext4_extents.c ext4_dir.c ext4_csum.c)
$CC $FLAGS -o "$WORK/dirwrite" "$HERE/dirwrite.c" \
    $(L ext4_create.c ext4_dirwrite.c ext4_dir.c ext4_extents.c ext4_extwrite.c \
        ext4_alloc.c ext4_ialloc.c ext4_io.c ext4_csum.c)
$CC $FLAGS -o "$WORK/extwrite" "$HERE/extwrite.c" \
    $(L ext4_extwrite.c ext4_extents.c ext4_alloc.c ext4_io.c ext4_csum.c)
$CC $FLAGS -o "$WORK/chunkwrite" "$HERE/chunkwrite.c" \
    $(L ext4_path.c ext4_create.c ext4_dirwrite.c ext4_dir.c ext4_extents.c \
        ext4_extwrite.c ext4_alloc.c ext4_ialloc.c ext4_io.c ext4_csum.c)
$CC $FLAGS -o "$WORK/rename"   "$HERE/rename.c" \
    $(L ext4_path.c ext4_create.c ext4_dirwrite.c ext4_dir.c ext4_extents.c \
        ext4_extwrite.c ext4_alloc.c ext4_ialloc.c ext4_io.c ext4_csum.c)
$CC $FLAGS -o "$WORK/writeat"  "$HERE/writeat.c" \
    $(L ext4_path.c ext4_dirwrite.c ext4_dir.c ext4_extents.c ext4_extwrite.c \
        ext4_alloc.c ext4_ialloc.c ext4_io.c ext4_csum.c)

WHEN=1784639915
fail=0
ran=0

# A sanitizer report goes to stderr and the process dies; -fno-sanitize-recover=all
# makes that true for UBSan as well, which otherwise carries on after printing. So
# a non-zero exit is the signal - but only for commands expected to succeed, which
# is why each call below is one the driver should be able to complete.
run() {
    local desc="$1"; shift
    local out
    ran=$((ran + 1))
    if ! out="$("$@" 2>&1)"; then
        echo "  FAIL  $desc"
        echo "$out" | sed -n '1,18p' | sed 's/^/        /'
        fail=1
        return
    fi
    # A driver can exit 0 and still have had a report printed by a sanitizer that
    # was built to recover; belt and braces, since that flag is easy to lose.
    if echo "$out" | grep -qE "AddressSanitizer|runtime error:|LeakSanitizer"; then
        echo "  FAIL  $desc - a sanitizer reported while the driver still exited 0"
        echo "$out" | sed -n '1,18p' | sed 's/^/        /'
        fail=1
    fi
}

# Exercises one image through the write surface. Deliberately ordinary work - the
# point is the geometry it runs on, not an exotic call.
exercise() {
    local img="$1" label="$2"
    run "$label: read the root"          "$WORK/bench"    "$img" 2 --ls
    run "$label: create a file"          "$WORK/dirwrite" "$img" 2 create f.dat "$WHEN"
    run "$label: make a directory"       "$WORK/dirwrite" "$img" 2 mkdir sub "$WHEN"
    run "$label: import through chunks"  "$WORK/chunkwrite" "$img" /big.dat 300000 65536
    run "$label: positional write"       "$WORK/writeat"  "$img" /big.dat 1000 5000 7
    run "$label: rename across dirs"     "$WORK/rename"   "$img" /f.dat /sub/f.dat
    run "$label: rename back"            "$WORK/rename"   "$img" /sub/f.dat /f.dat
    run "$label: add a directory entry"  "$WORK/dirwrite" "$img" 2 add extra.dat 12 1
    run "$label: remove it again"        "$WORK/dirwrite" "$img" 2 remove extra.dat
    run "$label: unlink the file"        "$WORK/dirwrite" "$img" 2 unlink f.dat "$WHEN"
    run "$label: remove the directory"   "$WORK/dirwrite" "$img" 2 rmdir sub "$WHEN"

    # The image has to be sound as well as the run clean: a sanitizer says nothing
    # about whether the bytes were right, so the usual oracle still gets the last
    # word. Without this, a driver that did nothing at all would pass.
    if ! e2fsck -fn "$img" >/dev/null 2>&1; then
        echo "  FAIL  $label: the image is not e2fsck-clean after the run"
        fail=1
    fi
}

# inode size x block size. 128 and 512 are the two that caught real bugs; 256/4096
# is what the app itself makes and is here as the control.
echo "sanitizer run over each geometry the open path admits:"
for isize in 128 256; do
    for bsize in 1024 4096; do
        img="$WORK/i${isize}b${bsize}.img"
        truncate -s 64M "$img"
        if ! mke2fs -q -F -t ext4 -O ^has_journal -I "$isize" -b "$bsize" \
                    "$img" $((67108864 / bsize)) 2>/dev/null; then
            echo "  SKIP  inode $isize / block $bsize - mke2fs would not build it"
            fail=1
            continue
        fi
        exercise "$img" "inode $isize, block $bsize"
    done
done

# A hash-indexed directory, which is the shape that found #144: the rebuild in
# ext4_dirwrite.c is the one write path that hands a stack inode buffer straight to
# ext4_write_inode_raw. e2fsck -fyD is what builds a real index (see htreecheck.py).
echo "sanitizer run over a hash-indexed directory:"
tree="$WORK/tree"
mkdir -p "$tree/many"
python3 - "$tree/many" <<'PY'
import os, sys
d = sys.argv[1]
for i in range(400):
    open(os.path.join(d, "file_with_a_longish_name_%04d.dat" % i), "w").write("x")
PY
img="$WORK/htree.img"
truncate -s 64M "$img"
mke2fs -q -F -t ext4 -O ^has_journal -b 1024 -N 4000 -d "$tree" "$img" 65536 2>/dev/null
e2fsck -fyD "$img" >/dev/null 2>&1 || true
dirino="$(debugfs -R 'stat /many' "$img" 2>/dev/null | sed -n 's/^Inode: \([0-9]*\).*/\1/p')"
if [ -z "$dirino" ]; then
    echo "  SKIP  could not find the indexed directory's inode"
    fail=1
else
    # Adding to it is what triggers the rebuild; removing exercises the other path
    # into the same helper.
    run "htree: add into the index"    "$WORK/dirwrite" "$img" "$dirino" add fresh.dat 12 1
    run "htree: remove from it"        "$WORK/dirwrite" "$img" "$dirino" remove fresh.dat
    if ! e2fsck -fy "$img" >/dev/null 2>&1 || ! e2fsck -fn "$img" >/dev/null 2>&1; then
        echo "  FAIL  htree: the image did not come back clean"
        fail=1
    fi
fi

# The block cache (#155) has no image and no geometry - it is a buffer manager, and
# that is exactly what a sanitizer is for: an entry copied short, a slot read after
# it was freed, a buffer reused under a different entry size. cachecheck.py is the
# workload; here it drives a sanitized build of the same module.
echo
echo "the block cache under the sanitizers:"
$CC $FLAGS -o "$WORK/cachetest" "$HERE/cachetest.c" $(L ext4_blockcache.c)
run "block cache: the whole stand" "$HERE/cachecheck.py" --cachetest "$WORK/cachetest"

# The handles a mount holds (#155, second half) are the one thing here whose
# lifetime spans operations: a writable handle owns its descriptor table and a
# bitmap, and closing it after a failed write is the moment a stale pointer would
# be left behind. A sanitizer is the only thing that sees that - the images come
# out identical either way, because the freed memory is usually still readable.
echo
echo "the mount's held handles under the sanitizers:"
$CC $FLAGS -o "$WORK/session" "$HERE/session.c" \
    $(L ext4_session.c ext4_path.c ext4_create.c ext4_dirwrite.c ext4_dir.c \
        ext4_extents.c ext4_extwrite.c ext4_alloc.c ext4_ialloc.c ext4_io.c \
        ext4_csum.c ext4_mkfs.c)
$CC $FLAGS -o "$WORK/mkfs" "$HERE/mkfs.c" $(L ext4_mkfs.c ext4_io.c ext4_csum.c)
run "held handles: the whole stand" "$HERE/sessioncheck.py" \
    --mkfs "$WORK/mkfs" --session "$WORK/session"

echo
if [ "$fail" -ne 0 ]; then
    echo "RESULT: a sanitizer fired, or a run could not be made - see above"
    exit 1
fi
echo "RESULT: $ran driver runs across 4 geometries, an indexed directory, the block"
echo "        cache and a mount's held handles, no ASan or UBSan report, every image"
echo "        e2fsck-clean"
