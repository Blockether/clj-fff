#!/usr/bin/env bash
# Build libfff_c and vendor it into resources/prebuilds/<os>-<arch>/.
# Usage: scripts/build-natives.sh [FFF_REF]  # default: resources/VERSION
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
ref="${1:-$(tr -d '[:space:]' < "$here/resources/VERSION")}" 
src="$(mktemp -d)/fff"
prebuilds="$here/resources/prebuilds"

echo ">> Cloning dmtrKovalenko/fff @ ${ref}"
git clone --depth 1 --branch "v${ref#v}" https://github.com/dmtrKovalenko/fff.git "$src" 2>/dev/null   || git clone --depth 1 --branch "$ref" https://github.com/dmtrKovalenko/fff.git "$src"

vendor() { mkdir -p "$prebuilds/$1"; cp "$2" "$prebuilds/$1/$3"; echo "   vendored $1/$3"; }

if [[ "$(uname -s)" == "Darwin" ]]; then
  echo ">> Building darwin-arm64 (native)"
  (cd "$src" && cargo build --release -p fff-c --target aarch64-apple-darwin)
  vendor darwin-arm64 "$src/target/aarch64-apple-darwin/release/libfff_c.dylib" libfff_c.dylib

  echo ">> Building darwin-x64 (cross target)"
  rustup target add x86_64-apple-darwin >/dev/null 2>&1 || true
  (cd "$src" && cargo build --release -p fff-c --target x86_64-apple-darwin)
  vendor darwin-x64 "$src/target/x86_64-apple-darwin/release/libfff_c.dylib" libfff_c.dylib
fi

build_linux() {
  echo ">> Building $1 via docker ($2)"
  docker run --rm --platform "$2" -v "$src:/work" -w /work -e CARGO_TARGET_DIR="/work/target-$1"     rust:1.91-bookworm bash -lc "cargo build --release -p fff-c"
  vendor "$1" "$src/target-$1/release/libfff_c.so" libfff_c.so
}

if command -v docker >/dev/null 2>&1; then
  build_linux linux-arm64 linux/arm64
  build_linux linux-x64 linux/amd64
else
  echo "!! docker not found — skipping Linux targets (use CI instead)"
fi

echo ">> Done. Vendored libraries:"
find "$prebuilds" -type f -print
