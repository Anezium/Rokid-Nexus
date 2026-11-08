#!/usr/bin/env bash
# Builds the Meta Messages native engine AAR (plugins/meta/libs/metamessages.aar), as
# Build-Android.ps1 does on Windows. Needs the go.mod toolchain on PATH and ANDROID_HOME
# with an NDK; it installs nothing.
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_HOME:?ANDROID_HOME must refer to the existing Android SDK}"
export GOTOOLCHAIN=local
required=$(sed -nE 's/^toolchain[[:space:]]+(go[0-9.]+)\r?$/\1/p' go.mod)
actual=$(go env GOVERSION)
if [[ "$actual" != "$required" ]]; then
  echo "This build requires $required; found $actual." >&2
  exit 1
fi
gobind=$(go tool -n gobind)
# Git Bash on Windows: the tool path comes back in Windows form.
if command -v cygpath >/dev/null; then gobind=$(cygpath -u "$gobind"); fi
export PATH="$(dirname "$gobind"):$PATH"
mkdir -p ../libs
go tool gomobile bind -trimpath -target=android/arm64 -androidapi=30 \
  '-ldflags=-s -w -extldflags=-Wl,-z,max-page-size=16384' \
  -javapkg=com.anezium.rokidbus.plugin.meta.nativebridge -o ../libs/metamessages.aar \
  ./mobile/instagram ./mobile/whatsapp ./mobile/messenger
