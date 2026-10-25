#!/usr/bin/env bash
# Builds the unsigned IPA with Siri / Shortcuts support (App Intents) on Linux.
#
# Siri and Shortcuts find an app's intents through an index inside the app, `Metadata.appintents/`, that Xcode
# writes with Apple's closed-source `appintentsmetadataprocessor`. xtool does not run it, so without this script
# the intents in `Sources/DetermineApp/VoiceControl.swift` compile but Siri never sees them, with no error.
# The generator is cyberneel/xtool-on-linux (MIT), pinned below; it turns the compiler's `.swiftconstvalues` into
# the index and is checked there against Apple's own output. The format is undocumented and can change in any iOS
# release. Verified on a device only by the user (see ios/README.md, Real device tests).
#
#   ios/scripts/add-siri-metadata.sh
#
# Env:
#   SIRI_EXTRA_BUNDLE_IDS  space-separated bundle ids the sideloading tool may rewrite this app to
#                          (free Apple accounts usually append a suffix); each also gets its own mapping
#   SIRI_GEN_DIR           use an existing checkout of the generator instead of cloning the pinned commit
set -euo pipefail

GEN_COMMIT=79173f6ad9e952bf5a91c220488db6e56de7f3b9
GEN_DIR=${SIRI_GEN_DIR:-$HOME/.cache/xtool-on-linux}
IOS_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
REPO_DIR=$(cd "$IOS_DIR/.." && pwd)
MODULE=DetermineApp
BUNDLE_ID=$(sed -n 's/^bundleID: *//p' "$IOS_DIR/xtool.yml")
CV_REL=.build/out/Intermediates.noindex/Determine.build/Release-iphoneos/$MODULE-t.build/Objects-normal/arm64/$MODULE-primary.swiftconstvalues
IPA=$IOS_DIR/xtool/$MODULE.ipa

if [ -z "${SIRI_GEN_DIR:-}" ] && [ ! -d "$GEN_DIR/.git" ]; then
  git clone -q https://github.com/cyberneel/xtool-on-linux.git "$GEN_DIR"
fi
if [ -z "${SIRI_GEN_DIR:-}" ]; then
  git -C "$GEN_DIR" fetch -q origin "$GEN_COMMIT" 2>/dev/null || true
  git -C "$GEN_DIR" checkout -q "$GEN_COMMIT"
fi

# 1. Normal xtool release build, with the compiler also emitting .swiftconstvalues for the app target.
podman run --rm --userns=keep-id \
  -e HOME=/home/ubuntu -e APPIMAGE_EXTRACT_AND_RUN=1 -e TERM=dumb \
  -e DETERMINE_APPINTENTS_PROTOCOLS=/gen/appintents/appintents-protocols.json \
  -v "$HOME/.cache/determine-ios:/home/ubuntu" \
  -v "$REPO_DIR:/workspace" \
  -v "$GEN_DIR:/gen:ro" \
  -v "$HOME/.cache/determine-ios/xtool-1.22.2.AppImage:/usr/local/bin/xtool:ro" \
  -w /workspace/ios localhost/determine-ios-build:6.4.1 \
  script -qec 'xtool dev build --ipa --configuration release' /dev/null

[ -f "$IOS_DIR/$CV_REL" ] || { echo "no .swiftconstvalues emitted: $CV_REL" >&2; exit 1; }

# 2. Generate the index.
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
EXTRA=()
for id in ${SIRI_EXTRA_BUNDLE_IDS:-}; do EXTRA+=(--extra-bundle-id "$id"); done
python3 "$GEN_DIR/appintents/appintents-gen.py" --module "$MODULE" --bundle-id "$BUNDLE_ID" "${EXTRA[@]}" \
  --out "$OUT/Metadata.appintents" "$IOS_DIR/$CV_REL"

# 3. Add it to the IPA (it is still unsigned; the sideloading tool signs everything inside).
python3 - "$IPA" "$OUT/Metadata.appintents" "Payload/$MODULE.app/Metadata.appintents" <<'EOF'
import os, sys, zipfile
ipa, src, dest = sys.argv[1:4]
with zipfile.ZipFile(ipa, "a", zipfile.ZIP_DEFLATED) as z:
    names = set(z.namelist())
    for name in sorted(os.listdir(src)):
        arc = f"{dest}/{name}"
        if arc in names:
            raise SystemExit(f"{arc} already in the IPA; rebuild it first")
        z.write(os.path.join(src, name), arc)
print("added", dest, "to", ipa)
EOF
echo "IPA with Siri metadata: $IPA"
