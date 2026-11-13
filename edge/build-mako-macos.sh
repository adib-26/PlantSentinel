#!/usr/bin/env bash
# Builds Mako Server on macOS from the official BAS sources (Real Time Logic publishes no macOS binary).
# BAS offers GPLv2 and commercial licensing options. This script downloads those sources into a local cache
# and does not vendor them. Review the BAS licence before distributing a combined package.
#
# Upstream's LinuxBuild.sh fails with current Apple clang: BAS.c defines a static function `getmode`, which clashes
# with getmode() declared in the macOS SDK's <unistd.h>. This script runs the upstream build, renames that one
# internal symbol, and finishes the build. Nothing is installed system-wide.
#
#   ./edge/build-mako-macos.sh [build-dir]        # default: ~/.cache/bas-intelligence/mako
#   MAKO_DIR=<build-dir>/BAS ./edge/test-edge-outage.sh
set -euo pipefail
[[ "$(uname -s)" == "Darwin" ]] || { echo "This script is for macOS; on Linux use upstream LinuxBuild.sh"; exit 1; }
xcode-select -p >/dev/null 2>&1 || { echo "Install the Xcode Command Line Tools first: xcode-select --install"; exit 1; }
DIR="${1:-$HOME/.cache/bas-intelligence/mako}"
mkdir -p "$DIR"; cd "$DIR"

if [[ -x BAS/mako && -f BAS/mako.zip ]]; then
  echo "Already built: $DIR/BAS/mako"; exit 0
fi

if [[ ! -f BAS/src/BAS.c ]]; then
  echo "Downloading sources with the official build script (the first compile attempt is expected to fail on macOS)…"
  curl -fsSL https://raw.githubusercontent.com/RealTimeLogic/BAS/main/LinuxBuild.sh -o LinuxBuild.sh
  bash LinuxBuild.sh < /dev/null > build-upstream.log 2>&1 || true
fi
[[ -f BAS/src/BAS.c ]] || { echo "Sources were not downloaded; see $DIR/build-upstream.log"; exit 1; }

cd BAS
if grep -qE '\bgetmode\b' src/BAS.c; then
  echo "Applying macOS fix: renaming the internal getmode() in src/BAS.c to bas_getmode()"
  perl -pi -e 's/\bgetmode\b/bas_getmode/g' src/BAS.c
fi
echo "Compiling (a few minutes)…"
export MinifyMakoZip="${MinifyMakoZip:-no}"
make -f mako.mk > ../build-mako.log 2>&1 || { echo "Build failed; see $DIR/build-mako.log"; tail -20 ../build-mako.log; exit 1; }
./mako -? >/dev/null 2>&1 || true
ls -la mako mako.zip
echo "Built $DIR/BAS/mako. Run: MAKO_DIR=$DIR/BAS ./edge/test-edge-outage.sh"
