#!/usr/bin/env bash
# Fast Kotlin-only syntax/type check for the app sources.
#
# Compiles app/src/main/java with the cached Kotlin compiler against android.jar.
# This does NOT replace a full Gradle build (resources/manifest are not checked),
# it only reports Kotlin compile errors quickly. Calls are serialized with flock
# so several agents can run it on the same machine without fighting over memory.
#
# Usage: bash tools/ktcheck.sh
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-$HOME/android-sdk}"
ANDROID_JAR="$SDK/platforms/android-35/android.jar"
CACHE="$HOME/.gradle/caches/modules-2/files-2.1"

find_jar() { find "$1" -name "$2" 2>/dev/null | head -1; }

KOTLINC_JAR="$(find_jar "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable/2.0.21" 'kotlin-compiler-embeddable-*.jar')"
STDLIB_JAR="$(find_jar "$CACHE/org.jetbrains.kotlin/kotlin-stdlib/2.0.21" 'kotlin-stdlib-2.0.21.jar')"
SCRIPT_RUNTIME_JAR="$(find_jar "$CACHE/org.jetbrains.kotlin/kotlin-script-runtime/2.0.21" 'kotlin-script-runtime-*.jar')"
ANNOTATIONS_JAR="$(find_jar "$CACHE/org.jetbrains/annotations" 'annotations-*.jar')"
TROVE_JAR="$(find_jar "$CACHE/org.jetbrains.intellij.deps/trove4j" 'trove4j-*.jar')"
COROUTINES_JAR="$(find_jar "$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.9.0" 'kotlinx-coroutines-core-jvm-*.jar')"

if [ -z "$KOTLINC_JAR" ] || [ -z "$STDLIB_JAR" ]; then
  echo "ktcheck: cached Kotlin compiler not found; run a Gradle build once first." >&2
  exit 3
fi
if [ ! -f "$ANDROID_JAR" ]; then
  echo "ktcheck: android.jar not found at $ANDROID_JAR" >&2
  exit 3
fi

OUT="$(mktemp -d /tmp/ktcheck.XXXXXX)"
trap 'rm -rf "$OUT"' EXIT

SOURCES=()
while IFS= read -r f; do SOURCES+=("$f"); done < <(find "$ROOT/app/src/main/java" -name '*.kt' | sort)

if [ "${#SOURCES[@]}" -eq 0 ]; then
  echo "ktcheck: no Kotlin sources found"
  exit 0
fi

JVM_CP="$KOTLINC_JAR:$STDLIB_JAR"
[ -n "$ANNOTATIONS_JAR" ] && JVM_CP="$JVM_CP:$ANNOTATIONS_JAR"
[ -n "$TROVE_JAR" ] && JVM_CP="$JVM_CP:$TROVE_JAR"
[ -n "$COROUTINES_JAR" ] && JVM_CP="$JVM_CP:$COROUTINES_JAR"

COMPILE_CP="$ANDROID_JAR:$STDLIB_JAR"
[ -n "$ANNOTATIONS_JAR" ] && COMPILE_CP="$COMPILE_CP:$ANNOTATIONS_JAR"

exec flock /tmp/neuralml-ktcheck.lock \
  java -Xmx700m -Xss4m -cp "$JVM_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -jvm-target 17 -nowarn \
  -classpath "$COMPILE_CP" -d "$OUT" "${SOURCES[@]}"
