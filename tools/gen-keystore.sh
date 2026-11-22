#!/usr/bin/env bash
# Regenerate the release signing key used by app/build.gradle.kts.
# Run once; keystore.properties is git-ignored on purpose.
#
# Usage: bash tools/gen-keystore.sh [password]
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STOREPASS="${1:-neuralml2025}"
ALIAS="neuralml"
KEYSTORE="$ROOT/release.keystore"
PROPS="$ROOT/keystore.properties"

if [ -f "$KEYSTORE" ]; then
  echo "release.keystore already exists — delete it first to regenerate."
  exit 0
fi

keytool -genkeypair -v \
  -keystore "$KEYSTORE" -alias "$ALIAS" \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass "$STOREPASS" -keypass "$STOREPASS" \
  -dname "CN=NeuralML Terminal, OU=Personal, O=NeuralML, L=Local, ST=Local, C=CN"

cat > "$PROPS" <<EOF
storeFile=release.keystore
storePassword=$STOREPASS
keyAlias=$ALIAS
keyPassword=$STOREPASS
EOF

echo "Wrote $KEYSTORE and $PROPS"
