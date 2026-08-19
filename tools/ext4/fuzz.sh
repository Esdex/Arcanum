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

# Coverage-guided fuzzing of the driver over attacker-chosen images (#147).
#
#   ./fuzz.sh                  build, replay the seeds and the known cases (fast)
#   ./fuzz.sh --fuzz 600       then fuzz for 600 seconds
#   ./fuzz.sh --fuzz 600 --corpus DIR    keep and grow a corpus between campaigns
#
# Without --fuzz this is an ordinary stand: it proves the target still builds, that
# every seed image passes, and that the inputs which once hung the driver still do
# not. That is the part worth running with everything else. A campaign is a
# separate, longer thing you start on purpose.
#
# Nothing binary is checked in. The seeds are built by mke2fs here, and the
# regression cases are made by patching one superblock field of a seed - which also
# keeps them readable: "inodes_per_group says 956366847" is a description, an 800 KB
# blob found in a crash directory is not.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
. "$HERE/sources.sh"

CLANG="${CLANG:-clang}"
SECONDS_TO_FUZZ=0
CORPUS_DIR=""
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

while [ $# -gt 0 ]; do
    case "$1" in
        --fuzz)   SECONDS_TO_FUZZ="$2"; shift 2 ;;
        --corpus) CORPUS_DIR="$2"; shift 2 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

if ! "$CLANG" --version >/dev/null 2>&1; then
    echo "clang not found - set CLANG, or install it; gcc has no libFuzzer" >&2
    exit 2
fi

# ── build ────────────────────────────────────────────────────────────────────
# ASan and UBSan alongside the fuzzer, because a fuzzer without a sanitizer only
# finds the crashes that would have crashed anyway - and the whole reason for this
# is the class that does not (see #146).
SRC=""
for f in $EXT4_SOURCES; do SRC="$SRC $EXT4_DIR/$f"; done
echo "building the fuzz target..."
$CLANG -g -O1 -std=c99 -I"$EXT4_DIR" \
       -fsanitize=fuzzer,address,undefined -fno-sanitize-recover=all \
       -o "$WORK/fuzz_ext4" "$HERE/fuzz_ext4.c" $SRC

# ── seeds ────────────────────────────────────────────────────────────────────
# Small on purpose: a fuzz run's value is executions per second, and these are
# copied twice per execution. Varied on purpose too - block size, inode size,
# metadata_csum, a directory tree, and an index built by e2fsck -fyD - because a
# mutator reaches the code behind a feature far sooner from a seed that has it.
SEEDS="$WORK/seeds"
mkdir -p "$SEEDS"
seed() {
    local name="$1" size="$2" blocks="$3"; shift 3
    truncate -s "$size" "$SEEDS/$name"
    if ! mke2fs -q -F -t ext4 -O ^has_journal "$@" "$SEEDS/$name" "$blocks" >/dev/null 2>&1; then
        echo "  mke2fs would not build seed $name" >&2
        rm -f "$SEEDS/$name"
    fi
}
echo "building seed images..."
seed a_1m_1k.img     1M 1024 -b 1024 -I 256
seed b_2m_1k.img     2M 2048 -b 1024 -I 256
seed c_2m_nocsum.img 2M 2048 -b 1024 -I 256 -O ^metadata_csum
seed d_2m_i128.img   2M 2048 -b 1024 -I 128
seed e_4m_4k.img     4M 1024 -b 4096 -I 256

TREE="$WORK/tree"
mkdir -p "$TREE/sub" "$TREE/many"
for i in 1 2 3 4 5; do echo "content $i" > "$TREE/f$i.txt"; done
echo nested > "$TREE/sub/nested.bin"
for i in $(seq 1 300); do echo x > "$TREE/many/f_$i.dat"; done
seed f_tree.img 4M 4096 -b 1024 -I 256 -N 2000 -d "$TREE"
cp "$SEEDS/f_tree.img" "$SEEDS/g_htree.img" 2>/dev/null || true
e2fsck -fyD "$SEEDS/g_htree.img" >/dev/null 2>&1 || true

if [ -z "$(ls -A "$SEEDS")" ]; then
    echo "no seed image could be built - mke2fs is required" >&2
    exit 2
fi

# ── the seeds must pass ──────────────────────────────────────────────────────
echo "replaying the seeds..."
if ! "$WORK/fuzz_ext4" -runs=0 "$SEEDS" >"$WORK/seedlog" 2>&1; then
    echo "FAIL: a valid image does not survive the target"
    tail -30 "$WORK/seedlog"
    exit 1
fi

# ── inputs that were once bugs ───────────────────────────────────────────────
# One entry per fixed finding. Each patches a single superblock field of a real
# image, so what it is testing is legible and it costs nothing to keep.
CASES="$WORK/cases"
mkdir -p "$CASES"
python3 - "$SEEDS/b_2m_1k.img" "$CASES" <<'PY'
import struct, sys, shutil, os
src, out = sys.argv[1], sys.argv[2]

def case(name, off, value, width=4):
    p = os.path.join(out, name)
    shutil.copy(src, p)
    with open(p, "r+b") as f:
        f.seek(1024 + off)
        f.write(struct.pack("<I" if width == 4 else "<H", value))

# #147, the value a two-minute campaign produced. A group's inode bitmap is one
# block, so with 1 KiB blocks 8192 is the ceiling; the campaign's image made every
# ext4_free_inode allocate, read and checksum 114 MB and never come back.
#
# It guards the bound as a budget rather than as a hang: with every
# inodes_per_group clause removed from both opens this case takes about 36 seconds
# against the 20 allowed below, so it fails - measured. Checking it by hand with a
# longer timeout reports "completes", which is how it briefly got written off as
# proving nothing. The value is also odd, so the multiple-of-eight clause turns it
# away even before the ceiling does.
case("inodes_per_group_huge.img", 0x28, 956366847)
# The same shape through the other bitmap: blocks_per_group drives fs->bitmap,
# which is allocated once per open and checksummed on every allocation. This one is
# the proven half of the pair: with the bound removed it hangs the replay.
case("blocks_per_group_huge.img", 0x20, 0xFFFFFFF8)
PY

# #147 again, from the campaign that followed. A directory is walked a block at a
# time up to its i_size, and a hole inside it reads as zeroes rather than ending
# the walk - so i_size alone decided how long the walk ran. This is the one that
# mattered: ext4_dir_iterate is what every listing, every lookup and every path
# resolution goes through. debugfs writes the field, which keeps the case legible.
cp "$SEEDS/b_2m_1k.img" "$CASES/dir_size_huge.img"
debugfs -w -R "sif <2> size 1152921504606846976" "$CASES/dir_size_huge.img" >/dev/null 2>&1

echo "replaying the inputs that were once bugs..."
for c in "$CASES"/*.img; do
    # 20 seconds is far more than the microseconds a refusal takes, and far less
    # than the minutes the unbounded version needed.
    if ! timeout 20 "$WORK/fuzz_ext4" -runs=1 "$c" >"$WORK/caselog" 2>&1; then
        echo "FAIL: $(basename "$c") hangs or crashes the driver again"
        tail -25 "$WORK/caselog"
        exit 1
    fi
done

if [ "$SECONDS_TO_FUZZ" -eq 0 ]; then
    echo
    echo "RESULT: the target builds, every seed passes, and $(ls "$CASES" | wc -l) "\
"input(s) that once hung the driver are refused promptly"
    echo "        (pass --fuzz <seconds> to actually go looking)"
    exit 0
fi

# ── campaign ─────────────────────────────────────────────────────────────────
# A corpus given with --corpus is grown in place, so successive campaigns start
# from what previous ones learned instead of from the seeds each time.
CORPUS="${CORPUS_DIR:-$WORK/corpus}"
mkdir -p "$CORPUS"
cp -n "$SEEDS"/* "$CORPUS"/ 2>/dev/null || true
ART="${CORPUS_DIR:+$CORPUS_DIR/../artifacts}"
ART="${ART:-$WORK/artifacts}"
mkdir -p "$ART"

echo "fuzzing for ${SECONDS_TO_FUZZ}s (corpus: $CORPUS)"
set +e
"$WORK/fuzz_ext4" -max_total_time="$SECONDS_TO_FUZZ" -timeout=20 -rss_limit_mb=4096 \
                  -max_len=4194304 -print_final_stats=1 \
                  -artifact_prefix="$ART/" "$CORPUS" 2>&1 | tail -25
rc=${PIPESTATUS[0]}
set -e

found=$(ls -A "$ART" 2>/dev/null | wc -l)
echo
if [ "$rc" -ne 0 ] || [ "$found" -ne 0 ]; then
    echo "RESULT: the campaign found something - $found artifact(s) under $ART"
    echo "        reproduce with: $WORK/fuzz_ext4 <artifact>   (this WORK dir is temporary;"
    echo "        rebuild with ./fuzz.sh and keep the artifact if you need it again)"
    exit 1
fi
echo "RESULT: ${SECONDS_TO_FUZZ}s of fuzzing found nothing"
