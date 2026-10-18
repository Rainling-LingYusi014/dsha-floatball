#!/usr/bin/env bash
# 构建 DSHA 状态球 APK（只用 JDK + Android SDK 的 aapt2/d8，无 Gradle）
#
# 需要：
#   javac, keytool, apksigner    (JDK 17)
#   aapt2, d8                    (Android SDK build-tools)
#   android.jar                  (Android SDK platforms/android-35)
#
# 用法：
#   ANDROID_JAR=/path/android.jar  R8_JAR=/path/r8.jar  ./build.sh
#
# 在 arm64 + x86_64 aapt2 的环境（如 Termux/proot）下，可加：
#   AAPT2_RUN="qemu-x86_64-static -L /usr/x86_64-linux-gnu /usr/x86_64-linux-gnu/lib/ld-linux-x86-64.so.2"
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

ANDROID_JAR="${ANDROID_JAR:-$HOME/android-sdk/platforms/android-35/android.jar}"
R8_JAR="${R8_JAR:-$HOME/android-sdk/build-tools/r8.jar}"
AAPT2="${AAPT2:-aapt2}"
AAPT2_RUN="${AAPT2_RUN:-}"
OUT="${OUT:-build}"

[ -f "$ANDROID_JAR" ] || { echo "找不到 android.jar：$ANDROID_JAR（用 ANDROID_JAR= 指定）" >&2; exit 1; }
[ -f "$R8_JAR" ] || { echo "找不到 r8.jar：$R8_JAR（用 R8_JAR= 指定）" >&2; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "== 1/5 javac"
find src -name '*.java' > "$OUT/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -nowarn \
  -cp "$ANDROID_JAR" -d "$OUT/classes" @"$OUT/sources.txt"

echo "== 2/5 d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
java -cp "$R8_JAR" com.android.tools.r8.D8 \
  --min-api 26 --lib "$ANDROID_JAR" --output "$OUT/dex" "@$OUT/classes.txt"

echo "== 3/5 aapt2 link"
$AAPT2_RUN "$AAPT2" link \
  -o "$OUT/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml \
  -A assets \
  --min-sdk-version 26 --target-sdk-version 33

echo "== 4/5 注入 classes.dex"
python3 - "$OUT" <<'PY'
import sys, zipfile
out = sys.argv[1]
with zipfile.ZipFile(f'{out}/base.apk', 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(f'{out}/dex/classes.dex', 'classes.dex')
print('dex injected')
PY

echo "== 5/5 签名"
if [ ! -f keystore.jks ]; then
  echo "  生成默认签名（仅本地测试用；正式发布请自行保管 keystore）"
  keytool -genkeypair -keystore keystore.jks -alias dsha -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass dshadsha -keypass dshadsha \
    -dname "CN=DSHA Floatball, O=DSHA, C=CN" >/dev/null 2>&1
fi
apksigner sign --ks keystore.jks --ks-pass pass:dshadsha --key-pass pass:dshadsha \
  --out "$OUT/floatball.apk" "$OUT/base.apk"
apksigner verify --print-certs "$OUT/floatball.apk" | head -3
ls -lh "$OUT/floatball.apk"
echo "BUILD_OK"
