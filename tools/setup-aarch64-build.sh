#!/usr/bin/env bash
#
# Готовит локальную сборку Android APK на хосте aarch64 (Termux / proot-дистрибутив
# на телефоне), где нативный aapt2 из Android build-tools не запускается:
# он собран под x86_64, а на aarch64 падает с
#   "aapt2: cannot execute: required file not found".
#
# Решение: запускать x86_64-сборку aapt2 через qemu-user-static (сборка под
# aarch64) с минимальным amd64-sysroot из libc6 + libgcc-s1.
#
# Скрипт идемпотентен: повторный запуск ничего не ломает.
#
# Использование:
#   tools/setup-aarch64-build.sh
#   ANDROID_SDK_ROOT=$HOME/android-sdk tools/build-local.sh
#
set -euo pipefail

TOOLS_DIR="${DEEP_AGENT_TOOLS:-$HOME/android-tools}"
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/android-sdk}}"
BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-35.0.0}"
UBUNTU_ARCHIVE="${UBUNTU_ARCHIVE:-http://archive.ubuntu.com/ubuntu/pool/main}"
UBUNTU_PORTS="${UBUNTU_PORTS:-http://ports.ubuntu.com/ubuntu-ports/pool/universe/q/qemu}"

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }

if [ "$(uname -m)" != "aarch64" ]; then
  log "Хост не aarch64 ($(uname -m)) — qemu не нужен, сборка пойдёт напрямую."
  exit 0
fi

mkdir -p "$TOOLS_DIR/bin" "$TOOLS_DIR/tmp"

# --- 1. qemu-user-static для хоста aarch64 ---------------------------------
if [ ! -x "$TOOLS_DIR/qemu-x86_64-static" ] || \
   [ "$(head -c 20 "$TOOLS_DIR/qemu-x86_64-static" 2>/dev/null | od -An -tx1 | tr -d ' \n' | cut -c37-40)" != "00b7" ]; then
  log "Скачиваю qemu-user-static (сборка под arm64)…"
  DEB_URL=$(curl -s "$UBUNTU_PORTS/" | grep -o 'qemu-user-static_[^"]*_arm64\.deb' | sort -uV | tail -1)
  [ -n "$DEB_URL" ] || { log "Не нашёл пакет qemu-user-static для arm64"; exit 1; }
  curl -sSL -o "$TOOLS_DIR/tmp/qemu-user-static.deb" "$UBUNTU_PORTS/$DEB_URL"
  rm -rf "$TOOLS_DIR/tmp/qemu"
  mkdir -p "$TOOLS_DIR/tmp/qemu"
  dpkg-deb -x "$TOOLS_DIR/tmp/qemu-user-static.deb" "$TOOLS_DIR/tmp/qemu"
  cp "$TOOLS_DIR/tmp/qemu/usr/bin/qemu-x86_64-static" "$TOOLS_DIR/qemu-x86_64-static"
  chmod +x "$TOOLS_DIR/qemu-x86_64-static"
fi
log "qemu: $("$TOOLS_DIR/qemu-x86_64-static" --version 2>/dev/null | head -1 || echo 'готов')"

# --- 2. amd64 sysroot: libc6 + libgcc-s1 -----------------------------------
SYSROOT="$TOOLS_DIR/amd64-root"
if [ ! -e "$SYSROOT/lib64/ld-linux-x86-64.so.2" ]; then
  log "Собираю amd64-sysroot (libc6, libgcc-s1)…"
  mkdir -p "$SYSROOT" "$TOOLS_DIR/tmp/debs"
  pushd "$TOOLS_DIR/tmp/debs" >/dev/null

  LIBC=$(curl -s "$UBUNTU_ARCHIVE/g/glibc/" | grep -o 'libc6_[0-9][^"]*_amd64\.deb' | sort -uV | tail -1)
  curl -sSL -o libc6.deb "$UBUNTU_ARCHIVE/g/glibc/$LIBC"
  dpkg-deb -x libc6.deb "$SYSROOT"

  for gcc in gcc-14 gcc-13 gcc-12; do
    GCCPKG=$(curl -s "$UBUNTU_ARCHIVE/g/$gcc/" | grep -o 'libgcc-s1_[0-9][^"]*_amd64\.deb' | sort -uV | tail -1 || true)
    if [ -n "${GCCPKG:-}" ]; then
      curl -sSL -o libgcc.deb "$UBUNTU_ARCHIVE/g/$gcc/$GCCPKG"
      dpkg-deb -x libgcc.deb "$SYSROOT"
      break
    fi
  done
  popd >/dev/null

  # Ubuntu использует merged-/usr, а ELF ищет загрузчик в /lib64.
  ln -sfn usr/lib "$SYSROOT/lib"
  ln -sfn usr/lib64 "$SYSROOT/lib64"
fi

# --- 3. обёртка над aapt2 ---------------------------------------------------
cat > "$TOOLS_DIR/bin/aapt2" <<EOF
#!/bin/sh
# Сгенерировано tools/setup-aarch64-build.sh: x86_64 aapt2 через qemu.
export QEMU_LD_PREFIX=$SYSROOT
exec $TOOLS_DIR/qemu-x86_64-static $SDK_DIR/build-tools/$BUILD_TOOLS_VERSION/aapt2 "\$@"
EOF
chmod +x "$TOOLS_DIR/bin/aapt2"

log "Проверка aapt2…"
"$TOOLS_DIR/bin/aapt2" version 2>&1 | head -1

log "Готово."
log "Сборка: ANDROID_SDK_ROOT=$SDK_DIR tools/build-local.sh"
log "Обёртка aapt2: $TOOLS_DIR/bin/aapt2"
