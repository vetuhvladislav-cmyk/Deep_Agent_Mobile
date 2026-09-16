#!/usr/bin/env bash
#
# Локальная сборка APK. На aarch64-хосте (телефон, Termux/proot) сначала
# выполните tools/setup-aarch64-build.sh — он готовит обёртку aapt2 через qemu,
# без которой build-tools не работает.
#
# Использование:
#   tools/build-local.sh              # тесты + debug APK
#   tools/build-local.sh assembleRelease
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

TOOLS_DIR="${DEEP_AGENT_TOOLS:-$HOME/android-tools}"
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/android-sdk}}"

export ANDROID_SDK_ROOT="$SDK_DIR"
export ANDROID_HOME="$SDK_DIR"

# JDK 17: на aarch64 путь отличается от x86_64.
if [ -z "${JAVA_HOME:-}" ]; then
  for candidate in /usr/lib/jvm/java-17-openjdk-arm64 /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/openjdk-17; do
    if [ -d "$candidate" ]; then export JAVA_HOME="$candidate"; break; fi
  done
fi

# Gradle: локальная установка предпочтительнее wrapper (не тянет дистрибутив).
GRADLE_BIN="${GRADLE_BIN:-}"
if [ -z "$GRADLE_BIN" ]; then
  if [ -x "$HOME/gradle-dist/gradle-8.13/bin/gradle" ]; then
    GRADLE_BIN="$HOME/gradle-dist/gradle-8.13/bin/gradle"
  else
    GRADLE_BIN="$ROOT_DIR/gradlew"
  fi
fi

AAPT2_OVERRIDE=()
if [ "$(uname -m)" = "aarch64" ] && [ -x "$TOOLS_DIR/bin/aapt2" ]; then
  AAPT2_OVERRIDE=("-Pandroid.aapt2FromMavenOverride=$TOOLS_DIR/bin/aapt2")
  echo "aarch64: aapt2 через qemu ($TOOLS_DIR/bin/aapt2)"
fi

TASKS=("$@")
if [ ${#TASKS[@]} -eq 0 ]; then
  TASKS=(testDebugUnitTest assembleDebug)
fi

echo "Gradle:  $GRADLE_BIN"
echo "SDK:     $SDK_DIR"
echo "JAVA_HOME: ${JAVA_HOME:-<не задан>}"
echo "Задачи:  ${TASKS[*]}"

"$GRADLE_BIN" --no-daemon --console=plain "${AAPT2_OVERRIDE[@]}" "${TASKS[@]}"

echo
echo "APK:"
find "$ROOT_DIR/app/build/outputs/apk" -name '*.apk' -printf '  %p (%s байт)\n' 2>/dev/null || true
