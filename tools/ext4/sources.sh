# The one place that knows what this directory is made of.
#
# Sourced by every mutants-*.sh. Each of those used to carry its own list of
# files to copy and compile, and twice a module grew to serve a second caller and
# left one of those lists stale: first when truncation moved into the file the
# extent writer already had, then when alloc.c gained the inode commands. Neither
# failed loudly. Every mutant simply stopped linking and the suite reported SKIP,
# which reads as "the pattern did not match" rather than "this is not built any
# more", and both were only found by running everything.
#
# So the lists live here once. Adding a module means adding it here, and every
# suite picks it up.

EXT4_HEADERS="ext4_csum.h ext4_extents.h ext4_alloc.h ext4_extwrite.h ext4_dir.h"
EXT4_SOURCES="ext4_csum.c ext4_extents.c ext4_alloc.c ext4_ialloc.c ext4_extwrite.c ext4_dir.c"

# mutant_stage <here> <work> <driver.c>
#   Puts everything needed to build <driver.c> into <work>.
mutant_stage() {
    local here="$1" work="$2" driver="$3" f
    for f in $EXT4_HEADERS $EXT4_SOURCES "$driver"; do
        cp "$here/$f" "$work/" || return 1
    done
}

# mutant_reset <here> <work>
#   Restores every source to its pristine copy, so one mutant cannot inherit the
#   previous one's edit.
mutant_reset() {
    local here="$1" work="$2" f
    for f in $EXT4_SOURCES; do
        cp "$here/$f" "$work/" || return 1
    done
}

# mutant_changed <here> <work>
#   True when the staged sources differ from the originals, i.e. a sed actually
#   matched something. A mutant that changed nothing tests nothing.
mutant_changed() {
    local here="$1" work="$2" f
    for f in $EXT4_SOURCES; do
        cmp -s "$here/$f" "$work/$f" || return 0
    done
    return 1
}

# mutant_build <work> <driver.c> <out>
#   Links the driver against every source, mutated or not. Unused objects cost
#   nothing and this way no suite carries a list that can go stale.
mutant_build() {
    local work="$1" driver="$2" out="$3"
    (cd "$work" && cc -O2 -std=c99 -o "$out" "$driver" $EXT4_SOURCES 2>/dev/null)
}
