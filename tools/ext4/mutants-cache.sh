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

# Measures cachecheck.py against a broken block cache (#155).
#
#   ./mutants-cache.sh
#
# A cache fails in two directions and only one of them is loud. Serve a stale entry
# and a reader sees bytes that are no longer on the volume - silent corruption, the
# worst thing in this tree. Serve nothing and the volume is merely slow, which no
# safety check would ever notice. So the mutants below break it both ways, and the
# stand has to catch every one.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

. "$HERE/sources.sh"
mutant_stage "$HERE" "$WORK" cachetest.c

fail=0

try() {
    local desc="$1" expr="$2"
    mutant_reset "$HERE" "$WORK"
    sed -i "$expr" "$WORK/ext4_blockcache.c"
    if ! mutant_changed "$HERE" "$WORK"; then
        echo "  SKIP  $desc - the pattern did not match, so nothing was mutated"
        fail=1; return
    fi
    if ! mutant_build "$WORK" cachetest.c ct; then
        echo "  SKIP  $desc - mutant did not build"
        fail=1; return
    fi
    if "$HERE/cachecheck.py" --cachetest "$WORK/ct" >/dev/null 2>&1; then
        echo "  MISS  $desc - the stand did not catch it"; fail=1
    else
        echo "  caught: $desc"
    fi
}

echo "mutants of ext4_blockcache.c against cachecheck.py:"

# ── The regression that actually shipped ───────────────────────────────────
# The first version emptied the cache on ANY change of block size. It was safe, it
# passed a stand that spoke one size, and on a real volume it did nothing whatever:
# the device alternates a 1 KiB bootstrap read with 4 KiB blocks, so the cache was
# empty every time it was consulted. Device measurement caught it, not this file -
# which is exactly why it is the first mutant here now.
try "a size change of any kind empties the cache (the shipped bug)" \
    's/    if (len > c->len) adopt(c, len);/    if (len != c->len) adopt(c, len);/'

# ── Stale answers: what silently corrupts a volume ─────────────────────────
try "drop forgets nothing" \
    's/^void ext4_blockcache_drop(ext4_blockcache \*c, uint64_t off, uint32_t len) {/&\n    (void)c; (void)off; (void)len; return;/'

try "a write smaller than an entry does not invalidate what covers it" \
    's/^    ext4_blockcache_drop(c, containing(c, off), c->len);/    \/* mutant: the stale entry stays *\//'

try "a store does not overwrite an entry it already holds" \
    's/^        memcpy(s->data, src, c->len);/        \/* mutant: the older value stays *\//'

try "lookup ignores the offset and takes any filled slot" \
    's/if (c->slots\[i\].off == off \&\& c->slots\[i\].data)/if (c->slots[i].data)/'

try "an entry is copied in one byte short" \
    's/    memcpy(c->slots\[victim\].data, src, c->len);/    memcpy(c->slots[victim].data, src, c->len - 1);/'

try "a smaller read is stored anyway, so two sizes coexist" \
    's/^    if (len != c->len) return;/    \/* mutant: fall through and store it *\//'

# ── Useless answers: safe, and worth nothing ───────────────────────────────
try "nothing is ever stored" \
    's/^static void store(ext4_blockcache \*c, uint64_t off, const void \*src) {/&\n    (void)c; (void)off; (void)src; return;/'

try "eviction throws out the most recently used entry" \
    's/if (c->slots\[i\].stamp < c->slots\[victim\].stamp) victim = i;/if (c->slots[i].stamp > c->slots[victim].stamp) victim = i;/'

try "a read does not count as a use, so eviction is FIFO" \
    's/^    s->stamp = ++c->clock;$/    \/* mutant: a read no longer counts as a use *\//'

if [ "$fail" -eq 0 ]; then
    echo "RESULT: every mutant was caught"
else
    echo "RESULT: see above"
fi
exit $fail
