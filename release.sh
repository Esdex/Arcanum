#!/usr/bin/env bash
# Usage: ./release.sh <versionName> <versionCode>
# Example: ./release.sh 1.1.2 4
#
# Prerequisites:
#   - versionName and versionCode already updated and committed in app/build.gradle.kts
#   - Working tree must be clean
#   - gh CLI authenticated, git GPG signing configured

set -euo pipefail

VERSION="${1:?Usage: ./release.sh <versionName> <versionCode>}"
VERSION_CODE="${2:?Usage: ./release.sh <versionName> <versionCode>}"
TAG="v${VERSION}"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
log()  { echo -e "${GREEN}▶${NC} $1"; }
warn() { echo -e "${YELLOW}⚠${NC} $1"; }
die()  { echo -e "${RED}✗ $1${NC}"; exit 1; }

# ── 1. Sanity checks ─────────────────────────────────────────────────────────

log "Checking working tree..."
if ! git diff --quiet || ! git diff --cached --quiet; then
    die "Uncommitted changes detected. Commit or stash them first."
fi

log "Verifying version in build.gradle.kts..."
GRADLE_VERSION=$(grep 'versionName = "' app/build.gradle.kts | grep -o '"[^"]*"' | tr -d '"')
GRADLE_CODE=$(grep 'versionCode = ' app/build.gradle.kts | grep -o '[0-9]*' | head -1)
[[ "$GRADLE_VERSION" != "$VERSION" ]]     && die "build.gradle.kts has versionName=$GRADLE_VERSION, expected $VERSION. Update it first."
[[ "$GRADLE_CODE"    != "$VERSION_CODE" ]] && die "build.gradle.kts has versionCode=$GRADLE_CODE, expected $VERSION_CODE. Update it first."

if git rev-parse "$TAG" &>/dev/null; then
    die "Tag $TAG already exists."
fi

# ── 2. Tag ───────────────────────────────────────────────────────────────────

log "Creating signed tag $TAG..."
git tag -s "$TAG" -m "Release $VERSION"

log "Pushing tag..."
git push origin "$TAG"

COMMIT=$(git rev-parse "${TAG}^{}")
log "Tag $TAG → $COMMIT"

# ── 3. Build APK from tag ────────────────────────────────────────────────────

log "Checking out $TAG for clean build..."
git checkout "$TAG"

log "Building release APK..."
./gradlew assembleFdroidRelease

APK_PATH=$(find app/build/outputs/apk/fdroid/release -name "*.apk" | head -1)
[[ -z "$APK_PATH" ]] && { git checkout main; die "APK not found after build."; }
log "APK: $APK_PATH"

log "Returning to main..."
git checkout main

# ── 4. Update F-Droid metadata (keep last 3 builds) ─────────────────────────

log "Updating metadata/zip.arcanum.yml..."
python3 - "$COMMIT" "$VERSION" "$VERSION_CODE" <<'PYEOF'
import re, sys

commit, version, version_code = sys.argv[1], sys.argv[2], sys.argv[3]

with open('metadata/zip.arcanum.yml', 'r') as f:
    content = f.read()

# Extract the Builds block
builds_match = re.search(r'(Builds:\n)(.*?)(\nAllowedAPKSigningKeys)', content, re.DOTALL)
if not builds_match:
    print("ERROR: Could not find Builds block in metadata", file=sys.stderr)
    sys.exit(1)

builds_block = builds_match.group(2)

# Split into individual entries
entries = re.findall(r'  - versionName:.*?(?=\n  - versionName:|\Z)', builds_block, re.DOTALL)

new_entry = (
    f"  - versionName: {version}\n"
    f"    versionCode: {version_code}\n"
    f"    commit: {commit}\n"
    f"    subdir: app\n"
    f"    gradle:\n"
    f"      - fdroid\n"
)

# Keep last 2 existing entries + new one = 3 total
entries_to_keep = entries[-2:] if len(entries) >= 2 else entries
new_builds = ''.join(entries_to_keep).rstrip('\n') + '\n\n' + new_entry

content = re.sub(
    r'(Builds:\n)(.*?)(\nAllowedAPKSigningKeys)',
    lambda m: m.group(1) + new_builds + m.group(3),
    content,
    flags=re.DOTALL
)

content = re.sub(r'CurrentVersion: .*',     f'CurrentVersion: {version}',      content)
content = re.sub(r'CurrentVersionCode: .*', f'CurrentVersionCode: {version_code}', content)

with open('metadata/zip.arcanum.yml', 'w') as f:
    f.write(content)

print(f"metadata updated — 3 builds kept, CurrentVersion={version}")
PYEOF

# ── 5. Commit and push metadata ──────────────────────────────────────────────

log "Committing metadata..."
git add metadata/zip.arcanum.yml
git commit -S -m "Update F-Droid metadata: add v${VERSION} build entry"
git push

# ── 6. Create GitHub Release (draft — add notes manually before publishing) ──

log "Creating GitHub Release draft for $TAG..."
gh release create "$TAG" "$APK_PATH" \
    --title "Arcanum $VERSION" \
    --draft \
    --notes "_(add release notes here before publishing)_"

echo ""
echo -e "${GREEN}✓ Release $VERSION prepared successfully!${NC}"
echo ""
echo "  Next steps:"
echo "  1. Open the draft release and add release notes:"
echo "     https://github.com/Esdex/Arcanum/releases/tag/$TAG"
echo "  2. Publish the release when ready."
