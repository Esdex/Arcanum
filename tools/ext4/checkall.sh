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

# Runs every stand in this directory, and says so when it does not know one.
#
#   ./checkall.sh                 every stand
#   ./checkall.sh --mutants       the mutation suites as well (about 40 minutes)
#   ./checkall.sh --cases DIR     reuse a corpus instead of building one
#
# There was no such command until #147. "Run everything" was a loop typed out by
# hand each time, and every typing was a chance to leave one out - which is the
# same failure as a guard that rots, arriving by a different road. Three guards
# were found broken during that audit and not one of them was found by looking.
#
# The important part is the last block: every `*check.py` in this directory must
# appear in the table below. A stand added without a line here fails the run rather
# than being quietly skipped, because a stand nobody runs is worse than no stand -
# it reads as coverage.

set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"

CASES=""
WITH_MUTANTS=0
while [ $# -gt 0 ]; do
    case "$1" in
        --mutants) WITH_MUTANTS=1; shift ;;
        --cases)   CASES="$2"; shift 2 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

WORK="$(mktemp -d)"
KEEP_CASES=1
if [ -z "$CASES" ]; then
    CASES="$WORK/cases"
    KEEP_CASES=0
fi
cleanup() { [ "$KEEP_CASES" -eq 1 ] && rm -rf "$WORK" || rm -rf "$WORK"; }
trap cleanup EXIT

echo "building the tools..."
./build.sh >/dev/null || { echo "build failed"; exit 1; }

if [ ! -d "$CASES" ]; then
    echo "building the corpus (same seed every time, so the run is reproducible)..."
    ./genimages.py --out "$CASES" --count 40 --seed 77 >/dev/null || exit 1
fi

fail=0
run() {
    local label="$1"; shift
    printf "  %-22s " "$label"
    local start=$(date +%s)
    if "$@" >"$WORK/out" 2>&1; then
        printf "ok    %4ds\n" $(( $(date +%s) - start ))
    else
        printf "FAIL  %4ds\n" $(( $(date +%s) - start ))
        sed -n '1,6p' "$WORK/out" | sed 's/^/        /'
        fail=1
    fi
}

echo
echo "stands:"
# Corpus-driven.
run check.py         ./check.py --cases "$CASES"
run dircheck.py      ./dircheck.py --cases "$CASES"
run fsckcheck.py     ./fsckcheck.py --cases "$CASES"
run "fsckcheck --fill" ./fsckcheck.py --cases "$CASES" --fill
run "fsckcheck --ifill" ./fsckcheck.py --cases "$CASES" --ifill
run appendcheck.py   ./appendcheck.py --cases "$CASES"
run trunccheck.py    ./trunccheck.py --cases "$CASES" --mode roundtrip
run dirwcheck.py     ./dirwcheck.py --cases "$CASES" --grow
run createcheck.py   ./createcheck.py --cases "$CASES"
run mkdircheck.py    ./mkdircheck.py --cases "$CASES"
run sizecheck.py     ./sizecheck.py --cases "$CASES"
# Self-contained: each builds whatever images it needs.
run pathcheck.py     ./pathcheck.py
run chunkcheck.py    ./chunkcheck.py
run renamecheck.py   ./renamecheck.py
run writeatcheck.py  ./writeatcheck.py
run featurecheck.py  ./featurecheck.py
run interopcheck.py  ./interopcheck.py
run mkfscheck.py     ./mkfscheck.py
run fullcheck.py     ./fullcheck.py
run faultcheck.py    ./faultcheck.py
run htreecheck.py    ./htreecheck.py --images "$WORK/htimg"
run bigcheck.py      ./bigcheck.py --keep "$WORK/bigimgs"
run matrixcheck.py   ./matrixcheck.py
run objectcheck.py   ./objectcheck.py
run allocfailcheck.py ./allocfailcheck.py
run cachecheck.py    ./cachecheck.py
run wtimecheck.py    ./wtimecheck.py
run sessioncheck.py  ./sessioncheck.py
run desccheck.py     ./desccheck.py
run runcheck.py      ./runcheck.py
run asancheck.sh     ./asancheck.sh
run fuzz.sh          ./fuzz.sh

if [ "$WITH_MUTANTS" -eq 1 ]; then
    echo
    echo "mutation suites (this is the slow half):"
    for s in mutants*.sh; do
        printf "  %-22s " "$s"
        start=$(date +%s)
        if ./"$s" "$CASES" >"$WORK/m.out" 2>&1; then st=ok; else st=FAIL; fail=1; fi
        printf "%-5s %4ds  caught=%-3s untestable=%-2s SKIP=%-2s MISS=%-2s UNEXPECTED=%s\n" \
            "$st" $(( $(date +%s) - start )) \
            "$(grep -c 'caught:' "$WORK/m.out")" "$(grep -c 'untestable:' "$WORK/m.out")" \
            "$(grep -c SKIP "$WORK/m.out")" "$(grep -c 'MISS ' "$WORK/m.out")" \
            "$(grep -c UNEXPECTED "$WORK/m.out")"
    done
fi

# Anything this file does not know about. Deliberately last, so it is the thing
# left on screen when a stand has been added and not wired in.
echo
missing=""
for f in *check.py; do
    # The name ends the line for a stand that takes no arguments, so the match
    # cannot require a trailing space; and the dots are escaped because a bare `.`
    # would let check.py match checkXpy. An earlier version got both wrong and
    # would have reported almost every stand as missing.
    pat="\./$(printf '%s' "$f" | sed 's/\./\\./g')( |$)"
    grep -qE "$pat" "$HERE/checkall.sh" || missing="$missing $f"
done
if [ -n "$missing" ]; then
    echo "checkall.sh does not run:$missing"
    echo "  add a line for each, or it is coverage that exists and never runs"
    fail=1
fi

if [ "$fail" -ne 0 ]; then
    echo "RESULT: something failed - see above"
    exit 1
fi
echo "RESULT: every stand passed"
echo
echo "not covered here: the JVM unit tests. ErrorCodeSyncTest guards the ERR_* codes"
echo "across the JNI boundary, which nothing in this directory can see - it was red"
echo "for three weeks before anyone ran it. From the repo root: ./gradlew test"
