#!/usr/bin/env bash
# Idempotent repository bootstrap for the Rokid Nexus Cloud Agent environment.
#
# The base snapshot already carries the stable, slow-moving pieces: a JDK, the
# Android SDK under /opt/android-sdk (exported through /etc/profile.d), and the
# sibling CxrGlobal checkout the two hubs link against. This script only refreshes
# the source-derived state after checkout and warms the Gradle caches, so it must
# stay safe to run repeatedly.
set -euo pipefail

# Gradle locates the Android SDK through ANDROID_SDK_ROOT, so the machine's
# untracked local.properties is never involved. The value is baked into the
# snapshot's profile; re-export it here in case install runs in a bare shell.
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"

if [ ! -d "$ANDROID_SDK_ROOT/platforms" ]; then
  echo "error: Android SDK not found at $ANDROID_SDK_ROOT." >&2
  echo "       It is provisioned into the environment's base snapshot; a build" >&2
  echo "       without that snapshot cannot compile the Android modules." >&2
  exit 1
fi

# settings.gradle.kts pulls the vendor CXR library from the sibling composite at
# ../CxrGlobal (relative to the repo root). Keep that checkout present and pinned
# to the release the hub CI builds against.
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
  # Already present from the base snapshot. Only reach out to the network when the
  # pinned tag is missing, so a build with no fetch access still succeeds when the
  # snapshot already carries the right revision.
  if ! git -C "$CXR_DIR" rev-parse -q --verify "refs/tags/$CXR_REF^{commit}" >/dev/null; then
    git -C "$CXR_DIR" fetch --depth 1 --force origin "refs/tags/$CXR_REF:refs/tags/$CXR_REF"
  fi
  git -C "$CXR_DIR" checkout -q --force "$CXR_REF"
fi

# Warm the Gradle caches across the whole module graph. The plugins and public
# SDK build with the CXR composite disabled; the hubs link against it and build
# without the flag, which also exercises the ../CxrGlobal include.
./gradlew --no-daemon --console=plain \
  :shared:assembleDebug :bus-client:assembleDebug :ink-engine:assemble \
  :plugin-sample:assembleDebug :plugin-assistant:assembleDebug \
  -PskipCxrGlobal=true

./gradlew --no-daemon --console=plain \
  :phone-hub:assembleDebug :glasses-hub:assembleDebug
