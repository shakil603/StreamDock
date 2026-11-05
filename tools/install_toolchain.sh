#!/bin/bash
set -e
TC=/home/user/.cache/toolchain
mkdir -p "$TC"
cd "$TC"

if [ ! -x "$TC/jdk17/bin/java" ]; then
  echo "== JDK 17 =="
  curl -sL -o jdk17.tar.gz "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse?project=jdk"
  mkdir -p jdk17
  tar -xzf jdk17.tar.gz -C jdk17 --strip-components=1
  rm -f jdk17.tar.gz
fi
"$TC/jdk17/bin/java" -version

if [ ! -x "$TC/sdk/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "== Android cmdline-tools =="
  curl -sL -o cmdtools.zip "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  mkdir -p sdk/cmdline-tools
  python3 - <<'PY'
import zipfile, os, shutil
z=zipfile.ZipFile('/home/user/.cache/toolchain/cmdtools.zip')
z.extractall('/home/user/.cache/toolchain/sdk/cmdline-tools')
src='/home/user/.cache/toolchain/sdk/cmdline-tools/cmdline-tools'
dst='/home/user/.cache/toolchain/sdk/cmdline-tools/latest'
if os.path.isdir(src) and not os.path.isdir(dst):
    shutil.move(src,dst)
PY
  rm -f cmdtools.zip
fi
chmod -R +x "$TC/sdk/cmdline-tools/latest/bin" || true

export JAVA_HOME="$TC/jdk17"
export ANDROID_SDK_ROOT="$TC/sdk"
if [ ! -d "$TC/sdk/build-tools/35.0.0" ] || [ ! -d "$TC/sdk/platform-tools" ]; then
  yes | "$TC/sdk/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$TC/sdk" "platform-tools" "platforms;android-35" "build-tools;35.0.0" > /home/user/.cache/toolchain-sdk-install.log 2>&1 || true
  tail -2 /home/user/.cache/toolchain-sdk-install.log
fi
echo "== toolchain ready =="
ls "$TC" "$TC/sdk"
