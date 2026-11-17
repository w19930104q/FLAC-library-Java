#!/usr/bin/env bash
# Builds the library and the self-contained test suite and runs all input-contract tests.
# Usage: JAVA_HOME=/path/to/jdk ./test/run-tests.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-$ROOT/tools/jdk-17.0.21+9}"
JAVAC="$JAVA_HOME/bin/javac"
JAVA="$JAVA_HOME/bin/java"

cd "$ROOT"
rm -rf build/classes build/test-classes
mkdir -p build/classes build/test-classes

echo "== Compiling main sources =="
find src -name '*.java' > build/main-sources.txt
"$JAVAC" -d build/classes @build/main-sources.txt

echo "== Compiling test sources =="
find test -name '*.java' > build/test-sources.txt
"$JAVAC" -cp build/classes -d build/test-classes @build/test-sources.txt

echo "== Running tests (assertions enabled) =="
"$JAVA" -ea -cp build/classes:build/test-classes io.nayuki.flac.decode.FlacInputContractRunner
