#!/usr/bin/env bash
#
# Launch Ialon in desktop mode.
#
# Usage:
#   ./run-desktop.sh          # run the game
#   ./run-desktop.sh 15       # run with the render-thread hitch profiler (threshold in ms)
#
# Runs the :desktop:run Gradle task (org.delaunois.ialon.DesktopLauncher) from the repo root, so
# ./save (worlds) and the native libs jME/Minie extract & load (libbulletjme.so, liblwjgl64.so,
# libopenal64.so) all resolve correctly.
#
set -euo pipefail

cd "$(dirname "$0")"

# Gradle needs a full JDK 17-21 (a JRE or JDK 22+ won't do). Prefer a Temurin 21/17 from ~/.jdks,
# else fall back to whatever JAVA_HOME points at. Override by exporting JAVA_HOME before running.
for d in "$HOME"/.jdks/temurin-21* "$HOME"/.jdks/temurin-17* "${JAVA_HOME:-}"; do
    if [ -n "$d" ] && [ -x "$d/bin/java" ]; then
        export JAVA_HOME="$d"
        break
    fi
done
echo "Using JAVA_HOME=${JAVA_HOME:-<system default>}"

# The IDE injects JAVA_TOOL_OPTIONS (import-metadata flag) that leaks noise into the build; drop it.
unset JAVA_TOOL_OPTIONS || true

args=(":desktop:run")
if [ "${1:-}" != "" ]; then
    args+=("-Phitch=$1")
    echo "Hitch profiler enabled (threshold ${1} ms) — watch the console for 'HITCH ...' lines."
fi

exec ./gradlew "${args[@]}"
