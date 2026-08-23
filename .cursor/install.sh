#!/usr/bin/env bash
# Idempotent repository bootstrap for the Rokid Nexus Cloud Agent environment.
#
# The repository is Android/Kotlin built with the checked-in Gradle wrapper. This
# script provisions the two things a bare base image lacks — the Android SDK and
# the sibling CxrGlobal composite the hubs link against — and then warms the
# Gradle caches. It must stay safe to run repeatedly: every step is guarded so a
# second run, or a run on a snapshot that already carries this state, is a no-op.
set -euo pipefail

ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
export ANDROID_SDK_ROOT
export ANDROID_HOME="$ANDROID_SDK_ROOT"

# --- Android SDK -----------------------------------------------------------
# Gradle locates the SDK through ANDROID_SDK_ROOT, so the machine's untracked
# local.properties is never involved. compileSdk is 36 and the build targets
# build-tools 36.0.0 (see the module build.gradle.kts files).
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
SDK_PACKAGES=("platform-tools" "platforms;android-36" "build-tools;36.0.0")

if [ ! -d "$ANDROID_SDK_ROOT/platforms/android-36" ] || [ ! -d "$ANDROID_SDK_ROOT/build-tools/36.0.0" ]; then
  echo "Provisioning Android SDK at $ANDROID_SDK_ROOT ..."
  if [ ! -w "$(dirname "$ANDROID_SDK_ROOT")" ] && [ ! -d "$ANDROID_SDK_ROOT" ]; then
    sudo mkdir -p "$ANDROID_SDK_ROOT"
    sudo chown "$(id -u):$(id -g)" "$ANDROID_SDK_ROOT"
  fi
  mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
  if [ ! -x "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]; then
    tmp_zip="$(mktemp --suffix=.zip)"
    curl -fsSL -o "$tmp_zip" "$CMDLINE_TOOLS_URL"
    rm -rf "$ANDROID_SDK_ROOT/cmdline-tools/latest"
    unzip -q -o "$tmp_zip" -d "$ANDROID_SDK_ROOT/cmdline-tools"
    mv "$ANDROID_SDK_ROOT/cmdline-tools/cmdline-tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest"
    rm -f "$tmp_zip"
  fi
  sdkmanager="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
  yes | "$sdkmanager" --sdk_root="$ANDROID_SDK_ROOT" --licenses >/dev/null
  "$sdkmanager" --sdk_root="$ANDROID_SDK_ROOT" "${SDK_PACKAGES[@]}"
fi

# Export the SDK location for interactive and login shells of future agents.
profile_line="export ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT"
profile_file="/etc/profile.d/android-sdk.sh"
if [ ! -f "$profile_file" ] || ! grep -q "ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT" "$profile_file" 2>/dev/null; then
  {
    echo "$profile_line"
    echo "export ANDROID_HOME=$ANDROID_SDK_ROOT"
    echo "export PATH=\"\$PATH:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin\""
  } | sudo tee "$profile_file" >/dev/null || true
fi

# --- CxrGlobal sibling composite -------------------------------------------
# settings.gradle.kts pulls the vendor CXR library from the sibling composite at
# ../CxrGlobal (relative to the repo root). Keep that checkout present and pinned
# to the release the hub CI builds against (.github/workflows/app-release.yml).
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CXR_DIR="$(cd "$REPO_ROOT/.." && pwd)/CxrGlobal"
CXR_REF="v0.2.3"
if [ ! -d "$CXR_DIR/.git" ]; then
  if ! mkdir -p "$CXR_DIR" 2>/dev/null; then
    sudo mkdir -p "$CXR_DIR"
    sudo chown "$(id -u):$(id -g)" "$CXR_DIR"
  fi
  git clone --depth 1 --branch "$CXR_REF" \
    https://github.com/Anezium/CxrGlobal.git "$CXR_DIR"
else
  # Already present (e.g. from a base snapshot). Only reach out to the network
  # when the pinned tag is missing, so a build with no fetch access still
  # succeeds when the checkout already carries the right revision.
  if ! git -C "$CXR_DIR" rev-parse -q --verify "refs/tags/$CXR_REF^{commit}" >/dev/null; then
    git -C "$CXR_DIR" fetch --depth 1 --force origin "refs/tags/$CXR_REF:refs/tags/$CXR_REF"
  fi
  git -C "$CXR_DIR" checkout -q --force "$CXR_REF"
fi

# --- Warm the Gradle caches ------------------------------------------------
# The plugins and public SDK build with the CXR composite disabled; the hubs link
# against it and build without the flag, which also exercises the ../CxrGlobal
# include. Warming here means the first real build in an agent is mostly cached.
./gradlew --no-daemon --console=plain \
  :shared:assembleDebug :bus-client:assembleDebug :ink-engine:assemble \
  :plugin-sample:assembleDebug :plugin-assistant:assembleDebug \
  -PskipCxrGlobal=true

./gradlew --no-daemon --console=plain \
  :phone-hub:assembleDebug :glasses-hub:assembleDebug
