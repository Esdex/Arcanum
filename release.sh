#!/usr/bin/env bash
# Usage: ./release.sh <versionName> <versionCode>
# Example: ./release.sh 1.1.2 4
#
# Prerequisites:
#   - versionName and versionCode already updated and committed in app/build.gradle.kts
#   - A matching version block exists in app/src/main/assets/whatsnew.json (source of
#     truth for the What's New screen, the F-Droid changelog, and GitHub release notes)
#   - Working tree must be clean
#   - gh CLI and jq installed; gh authenticated; git GPG signing configured

set -euo pipefail

VERSION="${1:?Usage: ./release.sh <versionName> <versionCode>}"
VERSION_CODE="${2:?Usage: ./release.sh <versionName> <versionCode>}"
TAG="v${VERSION}"

WHATSNEW_JSON="app/src/main/assets/whatsnew.json"
CHANGELOG_FILE="fastlane/metadata/android/en-US/changelogs/${VERSION_CODE}.txt"
FDROID_MAX_BYTES=500

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
log()  { echo -e "${GREEN}▶${NC} $1"; }
warn() { echo -e "${YELLOW}⚠${NC} $1"; }
die()  { echo -e "${RED}✗ $1${NC}"; exit 1; }

# ── 1. Sanity checks ─────────────────────────────────────────────────────────

command -v jq >/dev/null || die "jq is required but not installed."
[[ -f "$WHATSNEW_JSON" ]] || die "$WHATSNEW_JSON not found."

log "Verifying version in build.gradle.kts..."
GRADLE_VERSION=$(grep 'versionName = "' app/build.gradle.kts | grep -o '"[^"]*"' | tr -d '"')
GRADLE_CODE=$(grep 'versionCode = ' app/build.gradle.kts | grep -o '[0-9]*' | head -1)
[[ "$GRADLE_VERSION" != "$VERSION" ]]     && die "build.gradle.kts has versionName=$GRADLE_VERSION, expected $VERSION. Update it first."
[[ "$GRADLE_CODE"    != "$VERSION_CODE" ]] && die "build.gradle.kts has versionCode=$GRADLE_CODE, expected $VERSION_CODE. Update it first."

log "Validating whatsnew.json..."
jq empty "$WHATSNEW_JSON" || die "$WHATSNEW_JSON is not valid JSON."
BLOCK_COUNT=$(jq --argjson vc "$VERSION_CODE" '[.versions[] | select(.versionCode == $vc)] | length' "$WHATSNEW_JSON")
[[ "$BLOCK_COUNT" -eq 0 ]] && die "No version block with versionCode=$VERSION_CODE in $WHATSNEW_JSON. Add it first."
[[ "$BLOCK_COUNT" -gt 1 ]] && die "Duplicate version blocks with versionCode=$VERSION_CODE in $WHATSNEW_JSON."

# ── 2. Generate F-Droid changelog from JSON ──────────────────────────────────

log "Generating $CHANGELOG_FILE..."
jq -r --argjson vc "$VERSION_CODE" '
    .versions[] | select(.versionCode == $vc) | .entries[]
    | select(.fdroid != false)
    | "- \((.type[0:1] | ascii_upcase) + .type[1:]): \(.title)"
' "$WHATSNEW_JSON" > "$CHANGELOG_FILE"

BYTES=$(wc -c < "$CHANGELOG_FILE")
[[ "$BYTES" -gt "$FDROID_MAX_BYTES" ]] && \
    die "F-Droid changelog is $BYTES bytes (limit $FDROID_MAX_BYTES). Shorten titles in $WHATSNEW_JSON."
log "F-Droid changelog: $BYTES/$FDROID_MAX_BYTES bytes."

# The changelog must land in the tagged commit (F-Droid reads it from the tag),
# so require it committed before we tag. A regenerated-but-uncommitted changelog
# (or any other change) fails the clean-tree check below.
log "Checking working tree..."
if ! git diff --quiet || ! git diff --cached --quiet; then
    warn "Working tree is dirty."
    warn "If $CHANGELOG_FILE just changed, review and commit it, then re-run:"
    git --no-pager diff --stat
    die "Uncommitted changes detected. Commit or stash them first."
fi

if git rev-parse "$TAG" &>/dev/null; then
    warn "Tag $TAG already exists — skipping tag creation."
fi

# ── 3. Generate GitHub release notes from JSON ───────────────────────────────

GH_NOTES_FILE=$(mktemp)
trap 'rm -f "$GH_NOTES_FILE"' EXIT

emit_section() {
    local type="$1" heading="$2" body
    body=$(jq -r --argjson vc "$VERSION_CODE" --arg t "$type" '
        .versions[] | select(.versionCode == $vc) | .entries[]
        | select(.type == $t)
        | "- **\(.title)**" + (if .description then " — \(.description)" else "" end)
    ' "$WHATSNEW_JSON")
    [[ -n "$body" ]] && printf '### %s\n%s\n\n' "$heading" "$body"
}
{
    emit_section new         "New"
    emit_section improvement "Improvements"
    emit_section security    "Security"
    emit_section fix         "Fixes"
} > "$GH_NOTES_FILE"

# ── 4. Tag ───────────────────────────────────────────────────────────────────

if ! git rev-parse "$TAG" &>/dev/null; then
    log "Creating signed tag $TAG..."
    git tag -s "$TAG" -m "Release $VERSION"

    log "Pushing tag..."
    git push origin "$TAG"
else
    log "Using existing tag $TAG."
fi

COMMIT=$(git rev-parse "${TAG}^{}")
log "Tag $TAG → $COMMIT"

# ── 5. Build APK from tag ────────────────────────────────────────────────────

log "Checking out $TAG for clean build..."
git checkout "$TAG"

log "Building release APK..."
./gradlew assembleFdroidRelease

APK_PATH=$(find app/build/outputs/apk/fdroid/release -name "*.apk" | head -1)
[[ -z "$APK_PATH" ]] && { git checkout main; die "APK not found after build."; }
log "APK: $APK_PATH"

log "Returning to main..."
git checkout main

# ── 6. Create GitHub Release (draft — review notes before publishing) ────────

log "Creating GitHub Release draft for $TAG..."
gh release create "$TAG" "$APK_PATH" \
    --title "Arcanum $VERSION" \
    --draft \
    --notes-file "$GH_NOTES_FILE"

echo ""
echo -e "${GREEN}✓ Release $VERSION prepared successfully!${NC}"
echo ""
echo "  Next steps:"
echo "  1. Review the draft release notes (generated from whatsnew.json):"
echo "     https://github.com/Esdex/Arcanum/releases/tag/$TAG"
echo "  2. Publish the release when ready."
