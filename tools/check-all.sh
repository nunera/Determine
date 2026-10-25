#!/usr/bin/env bash
# One command that runs every automated check in the repo. Stops at the first failure and
# prints a summary. Never flashes or touches hardware (firmware is build-only).
#
#   tools/check-all.sh
#
# Steps (in order):
#   1. protocol python tests          python3 -m unittest discover -s protocol/python
#   2. simulator python tests         python3 -m unittest discover -s tools/sim/tests
#   3. firmware native tests          pio test -e native
#   4. firmware build (no upload)     pio run -e xiao_esp32c3
#   5. Kotlin build + unit tests      ./gradlew build
#   6. Swift engine checks            swift run DetermineEngineChecks
#   7. Kotlin e2e (all scenarios)     tools/sim/e2e.py --build
#   8. Swift e2e (all scenarios)      tools/sim/e2e.py --phone-cmd <release DeterminePhone, copied out of .build>
#
# Env: CHECK_OUT_DIR  where e2e logs go (default: a fresh /tmp/determine-check-all.XXXXXX).
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${CHECK_OUT_DIR:-$(mktemp -d /tmp/determine-check-all.XXXXXX)}"
mkdir -p "$OUT"
SWIFT_BIN="$OUT/DeterminePhone"

PASSED=()
CURRENT=""
START=$SECONDS

summary() {
  local rc=$?
  echo
  echo "================ check-all summary ================"
  for s in "${PASSED[@]+"${PASSED[@]}"}"; do echo "  PASS  $s"; done
  if [ $rc -ne 0 ]; then
    echo "  FAIL  ${CURRENT:-<setup>}"
    echo "result: FAILED after $((SECONDS - START)) s (logs: $OUT)"
  else
    echo "result: all ${#PASSED[@]} steps passed in $((SECONDS - START)) s (logs: $OUT)"
  fi
  exit $rc
}
trap summary EXIT

step() {
  CURRENT="$1"
  shift
  echo
  echo "=== $CURRENT"
  local t=$SECONDS
  "$@"
  PASSED+=("$CURRENT ($((SECONDS - t)) s)")
  CURRENT=""
}

in_dir() { (cd "$1" && shift && "$@"); }

fw()     { in_dir "$REPO/firmware/cue-board" nix shell nixpkgs#platformio nixpkgs#gcc -c "$@"; }
kotlin() { in_dir "$REPO/phone" nix shell nixpkgs#jdk17 nixpkgs#gradle -c "$@"; }
swift()  { in_dir "$REPO/ios" nix-shell -p swift swiftpm --run "$*"; }

build_swift_phone() {
  # The xtool container shares ios/.build, so copy the binary out before running e2e.
  swift 'swift build -c release --product DeterminePhone'
  local bin
  bin="$(swift 'swift build -c release --product DeterminePhone --show-bin-path')/DeterminePhone"
  cp "$bin" "$SWIFT_BIN"
}

step "protocol python tests"       python3 -m unittest discover -s "$REPO/protocol/python"
step "simulator python tests"      python3 -m unittest discover -s "$REPO/tools/sim/tests"
step "firmware native tests"       fw pio test -e native
step "firmware build (no upload)"  fw pio run -e xiao_esp32c3
step "Kotlin gradle build"         kotlin ./gradlew build
step "Swift DetermineEngineChecks" swift 'swift run DetermineEngineChecks'
# The installed Kotlin cli is a JVM launcher script: give it a JDK on PATH.
step "Kotlin e2e"                  nix shell nixpkgs#jdk17 -c python3 "$REPO/tools/sim/e2e.py" --build --out-dir "$OUT/e2e-kotlin"
step "build DeterminePhone (release)" build_swift_phone
step "Swift e2e"                   python3 "$REPO/tools/sim/e2e.py" \
  --phone-cmd "$SWIFT_BIN --port {phone_port} --duration {duration}" --out-dir "$OUT/e2e-swift"
