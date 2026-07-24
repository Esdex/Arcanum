#!/usr/bin/env bash
# Builds the six host tools. The library sources live in the app's cpp tree now
# (see sources.sh / EXT4_DIR); the drivers live here. One command so no scattered
# cc line goes stale when a module is added.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/sources.sh"
CC="${CC:-cc}"
FLAGS="-O2 -Wall -Wextra -std=c99 -I$EXT4_DIR"
L() { for f in "$@"; do echo "$EXT4_DIR/$f"; done; }

$CC $FLAGS -o "$HERE/bench"    "$HERE/bench.c"    $(L ext4_extents.c ext4_dir.c ext4_csum.c)
$CC $FLAGS -o "$HERE/fsmeta"   "$HERE/fsmeta.c"   $(L ext4_csum.c)
$CC $FLAGS -o "$HERE/alloc"    "$HERE/alloc.c"    $(L ext4_alloc.c ext4_ialloc.c ext4_io.c ext4_csum.c)
$CC $FLAGS -o "$HERE/extwrite" "$HERE/extwrite.c" $(L ext4_extwrite.c ext4_alloc.c ext4_io.c ext4_csum.c)
$CC $FLAGS -o "$HERE/dirwrite" "$HERE/dirwrite.c" \
    $(L ext4_create.c ext4_dirwrite.c ext4_dir.c ext4_extents.c ext4_extwrite.c ext4_alloc.c ext4_ialloc.c ext4_io.c ext4_csum.c)
$CC $FLAGS -o "$HERE/mkfs"     "$HERE/mkfs.c"     $(L ext4_mkfs.c ext4_io.c ext4_csum.c)
$CC $FLAGS -o "$HERE/pathresolve" "$HERE/pathresolve.c" \
    $(L ext4_path.c ext4_dirwrite.c ext4_dir.c ext4_extents.c ext4_extwrite.c ext4_alloc.c ext4_ialloc.c ext4_io.c ext4_csum.c)
$CC $FLAGS -o "$HERE/chunkwrite" "$HERE/chunkwrite.c" \
    $(L ext4_path.c ext4_create.c ext4_dirwrite.c ext4_dir.c ext4_extents.c ext4_extwrite.c ext4_alloc.c ext4_ialloc.c ext4_io.c ext4_csum.c)
$CC $FLAGS -o "$HERE/rename"   "$HERE/rename.c" \
    $(L ext4_path.c ext4_create.c ext4_dirwrite.c ext4_dir.c ext4_extents.c ext4_extwrite.c ext4_alloc.c ext4_ialloc.c ext4_io.c ext4_csum.c)
echo "built: bench fsmeta alloc extwrite dirwrite mkfs pathresolve chunkwrite rename"
