#!/usr/bin/env bash
# Rebuild the sandbox toolchain after a session recycle (tools/ is wiped except
# gradle-home). Committed 2026-11-25 so every future session can restore in one
# command instead of rediscovering the URLs (STATE.md referenced a session-local
# script that died with the recycle - this closes that gap).
#
# Sandbox facts (recorded in STATE.md):
# - Maven Central AND the Gradle Plugin Portal 429 from this IP: gradle builds MUST
#   pass -I scripts/dev/gradle-init-mirror.gradle (Google mirrors).
# - gradle-home/caches partially survives; purge empty-dir shells under
#   caches/<version>/ before debugging "GradleWorkerMain ClassNotFound" (2026-11-24).
# - No KVM: local verification is compile-only; emulator evidence comes from CI (D4).
# - 4GB RAM: pass --max-workers=1 or parallel KSP/Hilt workers OOM the daemon.
# - System java is a JRE (no javac): every gradle shell needs JAVA_HOME=$TOOLS/jdk17.
set -euo pipefail

TOOLS=/home/z/my-project/tools
mkdir -p "$TOOLS" "$TOOLS/bin"

have() { [ -e "$1" ]; }

# 1. gh CLI (needed for gh run list/view/api)
if ! have "$TOOLS/gh/bin/gh"; then
  echo "[restore] installing gh 2.63.2"
  curl -sL -o /tmp/gh.tgz https://github.com/cli/cli/releases/download/v2.63.2/gh_2.63.2_linux_amd64.tar.gz
  tar xzf /tmp/gh.tgz -C /tmp
  mv /tmp/gh_2.63.2_linux_amd64 "$TOOLS/gh"
else
  echo "[restore] gh ok"
fi

# 2. JDK 17 (Adoptium latest GA; AGP needs javac)
if ! have "$TOOLS/jdk17/bin/javac"; then
  echo "[restore] installing JDK 17 (Adoptium)"
  curl -sL -o /tmp/jdk17.tgz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
  mkdir -p "$TOOLS/jdk17"
  tar xzf /tmp/jdk17.tgz -C "$TOOLS/jdk17" --strip-components=1
else
  echo "[restore] jdk17 ok"
fi

# 3. Android SDK (compile-only: platform + build-tools + platform-tools)
SDK="$TOOLS/android-sdk"
if ! have "$SDK/platforms/android-34/android.jar"; then
  echo "[restore] installing Android SDK cmdline-tools + platform-34 + build-tools 34.0.0"
  mkdir -p "$SDK/cmdline-tools"
  curl -sL -o /tmp/clt.zip "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  unzip -q /tmp/clt.zip -d "$SDK/cmdline-tools"
  mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
  yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null 2>&1 || true
  "$SDK/cmdline-tools/latest/bin/sdkmanager" "platforms;android-34" "build-tools;34.0.0" "platform-tools" > /dev/null
else
  echo "[restore] android-sdk ok"
fi

# 4. actionlint
if ! have "$TOOLS/bin/actionlint"; then
  echo "[restore] installing actionlint 1.7.7"
  curl -sL -o /tmp/al.tgz https://github.com/rhysd/actionlint/releases/download/v1.7.7/actionlint_1.7.7_linux_amd64.tar.gz
  tar xzf /tmp/al.tgz -C /tmp actionlint
  cp /tmp/actionlint "$TOOLS/bin/"
else
  echo "[restore] actionlint ok"
fi

# 5. local.properties (untracked, sandbox-only sdk.dir)
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
if ! have "$REPO/android/local.properties"; then
  echo "sdk.dir=$SDK" > "$REPO/android/local.properties"
  echo "[restore] wrote android/local.properties"
fi

echo "[restore] DONE. Gradle shells must set:"
echo "  export JAVA_HOME=$TOOLS/jdk17 PATH=$TOOLS/gh/bin:$TOOLS/bin:\$JAVA_HOME/bin:\$PATH"
echo "  export GRADLE_USER_HOME=$TOOLS/gradle-home"
echo "  cd android && ./gradlew --max-workers=1 -I ../scripts/dev/gradle-init-mirror.gradle <tasks>"
