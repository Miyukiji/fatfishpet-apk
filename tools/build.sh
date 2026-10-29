#!/usr/bin/env bash
# 纯手工 Android 构建（无 gradle）：aapt2 编资源 -> javac -> d8 -> 打包 -> zipalign -> apksigner
set -euo pipefail

ROOT=${ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}
SDK=${ANDROID_SDK:-/usr/lib/android-sdk}
BT=${ANDROID_BUILD_TOOLS:-$SDK/build-tools/29.0.3}
# 这台机器是 aarch64，SDK 自带的 aapt2/zipalign 是 x86_64 的跑不了；
# 用系统的 aarch64 版本，d8 是 Java 程序直接叫 java 跑。
AAPT2=/usr/bin/aapt2
ZIPALIGN=/usr/bin/zipalign
APKSIGNER=/usr/bin/apksigner
D8="java -cp $ROOT/tools/r8.jar com.android.tools.r8.D8"
PLATFORM=$SDK/platforms/android-34/android.jar
APP=$ROOT/app
BUILD=$ROOT/build
OUT=${OUT:-$ROOT/dist}
MIN_SDK=29
TARGET_SDK=${TARGET_SDK:-30}
VER_CODE=${VER_CODE:-1}
VER_NAME=${VER_NAME:-1.0.0}
KS=$ROOT/keystore.jks
KS_PASS=${KS_PASS:-$(cat "$ROOT/.kspass" 2>/dev/null || true)}
if [ -z "${KS_PASS:-}" ]; then echo "缺少 keystore 口令：设置 KS_PASS 或本地 .kspass" >&2; exit 1; fi
KS_ALIAS=whalepet

rm -rf "$BUILD" && mkdir -p "$BUILD/compiled" "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$OUT"

echo "== 1/6 aapt2 compile"
"$AAPT2" compile --dir "$APP/res" -o "$BUILD/res.zip"

# 版本号写进临时清单（Debian 的 aapt2 不认 --version-code 覆盖）
sed -e "s/android:versionCode=\"[0-9]*\"/android:versionCode=\"$VER_CODE\"/" \
    -e "s/android:versionName=\"[^\"]*\"/android:versionName=\"$VER_NAME\"/" \
    -e "s/android:targetSdkVersion=\"[0-9]*\"/android:targetSdkVersion=\"$TARGET_SDK\"/" \
    "$APP/AndroidManifest.xml" > "$BUILD/AndroidManifest.xml"

echo "== 2/6 aapt2 link"
"$AAPT2" link \
  -o "$BUILD/base.apk" \
  -I "$PLATFORM" \
  --manifest "$BUILD/AndroidManifest.xml" \
  -R "$BUILD/res.zip" \
  --java "$BUILD/gen" \
  --min-sdk-version $MIN_SDK \
  --target-sdk-version $TARGET_SDK \
  --version-code "$VER_CODE" \
  --version-name "$VER_NAME" \
  --auto-add-overlay

echo "== 3/6 javac"
find "$APP/src" "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
javac --release 11 -encoding UTF-8 \
  -classpath "$PLATFORM" \
  -d "$BUILD/classes" \
  @"$BUILD/sources.txt"

echo "== 4/6 d8"
find "$BUILD/classes" -name '*.class' > "$BUILD/classes.txt"
$D8 --lib "$PLATFORM" --min-api $MIN_SDK --release \
  --output "$BUILD/dex" @"$BUILD/classes.txt"

echo "== 5/6 组装（assets + classes.dex）"
python3 - "$BUILD/base.apk" "$BUILD/dex/classes.dex" "$APP/assets" "$BUILD/unsigned.apk" <<'PY'
import os, sys, zipfile
base, dex, assets, out = sys.argv[1:5]
src = zipfile.ZipFile(base)
dst = zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED)
for it in src.infolist():
    dst.writestr(it, src.read(it.filename))
dst.write(dex, 'classes.dex')
n = 0
for dirpath, _dirs, files in os.walk(assets):
    for f in sorted(files):
        full = os.path.join(dirpath, f)
        rel = os.path.relpath(full, assets)
        # webm 本身已是压缩格式：再 deflate 一次没有收益，还会让 MediaPlayer 无法
        # 直接用 AssetFileDescriptor 播放（压缩过的 asset 不能 openFd）。
        st = zipfile.ZIP_STORED if f.endswith(".webm") else zipfile.ZIP_DEFLATED
        dst.write(full, os.path.join('assets', rel), compress_type=st)
        n += 1
dst.close()
print('assets entries:', n)
PY

echo "== 6/6 zipalign + apksigner"
"$ZIPALIGN" -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias "$KS_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=WhalePet, OU=DSHA, O=dsha, L=Local, S=Local, C=CN" >/dev/null 2>&1
fi

APK="$OUT/whalepet-$VER_NAME.apk"
"$APKSIGNER" sign --ks "$KS" --ks-key-alias "$KS_ALIAS" \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --min-sdk-version $MIN_SDK \
  --out "$APK" "$BUILD/aligned.apk"

"$APKSIGNER" verify --min-sdk-version $MIN_SDK "$APK"
echo "APK: $APK  ($(stat -c%s "$APK") bytes)"
