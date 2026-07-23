#!/usr/bin/env bash
# Measures pathcheck.py, not the resolver.
#
#   ./mutants-path.sh
#
# The resolver is a loop, and its bugs are the quiet kind: landing one component
# short or long, confusing "absent" with "not a directory", skipping the check
# that an intermediate is a directory at all. None of those crash; they resolve to
# a plausible wrong inode, which is why the oracle is debugfs walking the same tree
# rather than our own agreement with ourselves.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

. "$HERE/sources.sh"
mutant_stage "$HERE" "$WORK" pathresolve.c

fail=0

try() {
    local desc="$1" expr="$2" expect_miss="${3:-}"
    mutant_reset "$HERE" "$WORK"
    sed -i "$expr" "$WORK/ext4_path.c"
    if ! mutant_changed "$HERE" "$WORK"; then
        echo "  SKIP  $desc - the pattern did not match, so nothing was mutated"
        fail=1; return
    fi
    if ! mutant_build "$WORK" pathresolve.c pr; then
        echo "  SKIP  $desc - mutant did not build"
        fail=1; return
    fi
    if "$HERE/pathcheck.py" --mkfs "$HERE/mkfs" --dirwrite "$HERE/dirwrite" \
                            --pathresolve "$WORK/pr" >/dev/null 2>&1; then
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

echo "path resolution mutation tests (each should read caught, or untestable with a reason):"

# ── how far the walk goes ────────────────────────────────────────────────────

try "the walk stops one component short" \
    's@    uint32_t resolve = (total >= (uint32_t)stop_short) ? total - (uint32_t)stop_short : 0;@    uint32_t resolve = (total > (uint32_t)stop_short) ? total - (uint32_t)stop_short - 1 : 0;@'

try "resolve_parent walks the final component too" \
    's@    int rc = walk(r, path, 1, &parent, &tail);@    int rc = walk(r, path, 0, \&parent, \&tail);@'

try "resolve_path stops before the last component" \
    's@    int rc = walk(r, path, 0, &ino, &tail);@    int rc = walk(r, path, 1, \&ino, \&tail);@'

# ── starting point and stepping ──────────────────────────────────────────────

try "the walk starts from the wrong inode" \
    's@    uint32_t ino = EXT4_ROOT_INO;@    uint32_t ino = EXT4_ROOT_INO + 1;@'

try "the resolved child is dropped, so every step stays at the root" \
    's@        ino = child;@@'

# ── absent versus not-a-directory ────────────────────────────────────────────

try "an intermediate is not checked to be a directory" \
    's@        if (!inode_is_dir(r, ino, &err))@        if (0 \&\& !inode_is_dir(r, ino, \&err))@'

try "a missing name reported as not-a-directory rather than absent" \
    's@    case EXT4_DIRW_ERR_ABSENT: return EXT4_PATH_ENOENT;@    case EXT4_DIRW_ERR_ABSENT: return EXT4_PATH_ENOTDIR;@'

try "is_dir tells a file from a directory backwards" \
    's@    return (rd16(inode + INODE_MODE_OFF) & EXT4_S_IFMT) == EXT4_S_IFDIR;@    return (rd16(inode + INODE_MODE_OFF) \& EXT4_S_IFMT) != EXT4_S_IFDIR;@'

# ── the parent's final component ─────────────────────────────────────────────

try "the parent directory is not required to be a directory" \
    's@    if (!inode_is_dir(r, parent, &err))@    if (0 \&\& !inode_is_dir(r, parent, \&err))@'

try "the root is accepted as a parent, with an empty final name" \
    's@    if (have == 0) return EXT4_PATH_EINVAL;@    if (have == 0) return EXT4_PATH_OK;@'

echo
if [ "$fail" -ne 0 ]; then
    echo "RESULT: the harness has a gap - a broken resolver passed it"
    exit 1
fi
echo "RESULT: every testable mutant was caught"
