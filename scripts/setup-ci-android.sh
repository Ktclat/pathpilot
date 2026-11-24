#!/usr/bin/env bash
# Use Google's pinned CLI directly; don't depend on a third-party setup action.
set -euo pipefail
: "${RUNNER_TEMP:?RUNNER_TEMP must point to the CI temporary directory}"
export ANDROID_HOME="${ANDROID_HOME:-$RUNNER_TEMP/android-sdk}"
cli_root="$RUNNER_TEMP/pathpilot-android-cli-11076708"
archive="$RUNNER_TEMP/pathpilot-android-cli-11076708.zip"
mkdir -p "$cli_root" "$ANDROID_HOME"
curl --fail --location --retry 3 \
  https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
  --output "$archive"
printf '%s  %s\n' \
  2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258 \
  "$archive" | sha256sum --check --status
unzip -oq "$archive" -d "$cli_root"
python3 - "$cli_root/cmdline-tools/bin/sdkmanager" "$ANDROID_HOME" <<'PY'
import subprocess
import sys

# Preserve sdkmanager's exit status while accepting its standard SDK license.
result = subprocess.run(
    [sys.argv[1], '--sdk_root=' + sys.argv[2],
     'platforms;android-35', 'build-tools;35.0.0', 'platform-tools'],
    input='y\n' * 30, text=True,
)
raise SystemExit(result.returncode)
PY
test -f "$ANDROID_HOME/platforms/android-35/android.jar"
test -x "$ANDROID_HOME/build-tools/35.0.0/apksigner"
if [[ -n "${GITHUB_ENV:-}" ]]; then
  printf 'ANDROID_HOME=%s\nANDROID_SDK_ROOT=%s\n' "$ANDROID_HOME" "$ANDROID_HOME" >> "$GITHUB_ENV"
fi
