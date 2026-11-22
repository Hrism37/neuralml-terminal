#!/usr/bin/env bash
# Build the signed release APK, verify it, and publish the deliverables.
#
# Usage: bash tools/package.sh [output-dir]
# Default output dir: /storage/emulated/0/DSH/NeuralMLTerminal
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
if [ -z "${JAVA_HOME:-}" ] && [ -d /usr/lib/jvm/java-17-openjdk-arm64 ]; then
  export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64
fi
GRADLE="${GRADLE:-./gradlew}"
OUT_DIR="${1:-/storage/emulated/0/DSH/NeuralMLTerminal}"
VERSION="$(sed -n 's/.*versionName = "\(.*\)"/\1/p' "$ROOT/app/build.gradle.kts" | head -1)"
APK_NAME="NeuralML-Terminal-v${VERSION}-release.apk"

# Extra flags for ARM64 hosts where Google's x86_64 aapt2 must run under qemu.
AAPT2_FLAGS=""
if [ -x /opt/toolchain/aapt2 ] && [ "$(uname -m)" = "aarch64" ]; then
  AAPT2_FLAGS="-Pandroid.aapt2FromMavenOverride=/opt/toolchain/aapt2"
fi

cd "$ROOT"
echo "==> building release APK (version ${VERSION})"
"$GRADLE" :app:assembleRelease --console=plain $AAPT2_FLAGS

APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || { echo "release APK missing: $APK" >&2; exit 1; }

echo "==> verifying signature"
APKSIGNER="$(ls -1 "$ANDROID_HOME"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1)"; "$APKSIGNER" verify --print-certs "$APK" | head -4

mkdir -p "$OUT_DIR"
cp "$APK" "$OUT_DIR/$APK_NAME"

echo "==> packaging source archive"
SRC_ZIP="$OUT_DIR/NeuralMLTerminal-source.zip"
rm -f "$SRC_ZIP"
(
  cd "$ROOT"
  zip -q -r "$SRC_ZIP" . \
    -x "app/build/*" -x "build/*" -x ".gradle/*" -x ".kotlin/*" -x ".git/*" \
    -x "local.properties" -x "*.apk" -x "*.zip" -x ".idea/*" -x "*.iml"
)
cp "$ROOT/README.md" "$OUT_DIR/README.md"

echo "==> done"
ls -la "$OUT_DIR"
echo
echo "APK:    $OUT_DIR/$APK_NAME"
echo "SOURCE: $SRC_ZIP"
