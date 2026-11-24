#!/usr/bin/env bash
# Linux cloud setup. Keeps toolchains/caches outside the checkout and TLS verification enabled.
set -euo pipefail
repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
tools_dir="${PATHPILOT_TOOLCHAINS:-/workspace/toolchains}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/workspace/.gradle}"
export ANDROID_HOME="${ANDROID_HOME:-$tools_dir/android-sdk}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$tools_dir/android-user-home}"
mkdir -p "$tools_dir/downloads" "$GRADLE_USER_HOME" "$ANDROID_USER_HOME/cache"
for executable in curl unzip tar python3 sha256sum; do
  command -v "$executable" >/dev/null || { echo "Missing prerequisite: $executable" >&2; exit 1; }
done

fetch_verified() {
  local url="$1" file="$2" expected="$3"
  if [[ ! -f "$file" ]]; then
    curl --fail --location --retry 3 "$url" --output "$file.part"
    mv "$file.part" "$file"
  fi
  printf '%s  %s\n' "$expected" "$file" | sha256sum --check --status
}

# The base cloud image may contain only a JRE (no javac/jlink). Android's
# JDK-image transform requires a complete JDK. Checksum is from Temurin's release.
export JAVA_HOME="$tools_dir/jdk-17.0.17+10"
if [[ ! -x "$JAVA_HOME/bin/jlink" ]]; then
  fetch_verified 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.17%2B10/OpenJDK17U-jdk_x64_linux_hotspot_17.0.17_10.tar.gz' \
    "$tools_dir/downloads/jdk17.tar.gz" \
    '992f96e7995075ac7636bb1a8de52b0c61d71ed3137fafc979ab96b4ab78dd75'
  tar -xzf "$tools_dir/downloads/jdk17.tar.gz" -C "$tools_dir"
fi
export PATH="$JAVA_HOME/bin:$PATH"

# SHA256 from the official Gradle distribution checksum; Android archive was
# checked against Google's repository2 XML checksum before pinning its SHA256.
if [[ ! -x "$tools_dir/gradle-9.3.1/bin/gradle" ]]; then
  fetch_verified 'https://services.gradle.org/distributions/gradle-9.3.1-bin.zip' \
    "$tools_dir/downloads/gradle-9.3.1-bin.zip" \
    'b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06'
  unzip -q "$tools_dir/downloads/gradle-9.3.1-bin.zip" -d "$tools_dir"
fi
if [[ ! -x "$ANDROID_HOME/cmdline-tools/23.0/bin/sdkmanager" ]]; then
  fetch_verified 'https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip' \
    "$tools_dir/downloads/android-cli-16111833.zip" \
    '0877a1d048fe4a24efe2eff536ca4223f7adeb58648bb81909d33c446918cfa8'
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  staging_dir="$(mktemp -d "$tools_dir/android-cli.XXXXXX")"
  unzip -q "$tools_dir/downloads/android-cli-16111833.zip" -d "$staging_dir"
  mv "$staging_dir/cmdline-tools" "$ANDROID_HOME/cmdline-tools/23.0"
  rmdir "$staging_dir"
fi

# Java tools do not automatically use HTTPS_PROXY. Read only hostname/port;
# never write proxy credentials to files or print the proxy URL.
proxy_settings="$(python3 - <<'PY'
import os, urllib.parse
raw = os.environ.get('HTTPS_PROXY') or os.environ.get('https_proxy') or ''
u = urllib.parse.urlparse(raw)
if u.hostname:
    print(u.hostname)
    print(u.port or (443 if u.scheme == 'https' else 80))
PY
)"
sdk_proxy=()
gradle_proxy=()
# Reuse the environment's managed CA trust (including its HTTPS proxy CA).
# Keep certificate validation enabled; do not install an unverified custom CA.
if [[ -r /etc/ssl/certs/java/cacerts ]]; then
  export JAVA_OPTS="${JAVA_OPTS:-} -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"
  gradle_proxy+=('-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts')
fi
if [[ -n "$proxy_settings" ]]; then
  proxy_host="${proxy_settings%%$'\n'*}"
  proxy_port="${proxy_settings##*$'\n'}"
  sdk_proxy=(--proxy=http "--proxy_host=$proxy_host" "--proxy_port=$proxy_port")
  gradle_proxy+=("-Dhttps.proxyHost=$proxy_host" "-Dhttps.proxyPort=$proxy_port" "-Dhttp.proxyHost=$proxy_host" "-Dhttp.proxyPort=$proxy_port" '-Dhttp.nonProxyHosts=localhost|127.*|[::1]')
fi

# Setup authorization includes accepting the Android SDK dependency license.
# subprocess keeps sdkmanager's actual exit status (unlike `yes | ...`).
python3 - "$ANDROID_HOME/cmdline-tools/23.0/bin/sdkmanager" "--sdk_root=$ANDROID_HOME" "${sdk_proxy[@]}" <<'PY'
import subprocess,sys
args=sys.argv[1:]+['platforms;android-37.0','build-tools;36.0.0','platform-tools']
result=subprocess.run(args,input='y\n'*20,text=True)
raise SystemExit(result.returncode)
PY

# Do not overwrite a developer's existing SDK selection.
if [[ ! -e "$repo_dir/local.properties" ]]; then
  printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$repo_dir/local.properties"
fi

# Processes are not retained across cloud tasks; source this in each new shell.
{
  printf 'export JAVA_HOME=%q\n' "$JAVA_HOME"
  printf 'export GRADLE_USER_HOME=%q\n' "$GRADLE_USER_HOME"
  printf 'export ANDROID_HOME=%q\n' "$ANDROID_HOME"
  printf 'export ANDROID_USER_HOME=%q\n' "$ANDROID_USER_HOME"
  printf 'export PATH=%q:"$PATH"\n' "$JAVA_HOME/bin:$tools_dir/gradle-9.3.1/bin:$ANDROID_HOME/platform-tools"
  if ((${#gradle_proxy[@]})); then
    # GRADLE_OPTS is interpreted by the Gradle launcher; quote each JVM argument.
    opts=''
    for arg in "${gradle_proxy[@]}"; do printf -v quoted '%q' "$arg"; opts+="$quoted "; done
    printf 'export GRADLE_OPTS=%q\n' "$opts"
  fi
} > "$tools_dir/pathpilot-env.sh"

"$tools_dir/gradle-9.3.1/bin/gradle" "${gradle_proxy[@]}" --version
"$ANDROID_HOME/platform-tools/adb" version
printf '\nToolchains ready. For each shell: source %s/pathpilot-env.sh\n' "$tools_dir"
printf 'Build from %s with: ./gradlew :core:test :app:assembleDebug\n' "$repo_dir"
