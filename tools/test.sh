#!/usr/bin/env bash
# 纯 JVM 单测：余额解析、抛掷物理、天气解析。
# 数据层通过 Txt 取本地化文案；单测没有 Android 运行时，Txt 会回退到中文 fallback。
# 所以要用 aapt2 生成 R.java，并把 android.jar 放进 classpath（只当符号用）。
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=$(mktemp -d)
GEN="$OUT/gen"
mkdir -p "$GEN"
SDK=${ANDROID_SDK:-/usr/lib/android-sdk}
BT=${ANDROID_BUILD_TOOLS:-$SDK/build-tools/34.0.0}
PLATFORM=$SDK/platforms/android-34/android.jar
AAPT2=${ANDROID_AAPT2:-$(command -v aapt2 || echo "$BT/aapt2")}
SRC="app/src/com/dsha/whalepet"

"$AAPT2" compile --dir app/res -o "$OUT/res.zip"
"$AAPT2" link -o "$OUT/test.apk" -I "$PLATFORM" --manifest app/AndroidManifest.xml \
  -R "$OUT/res.zip" --java "$GEN" --auto-add-overlay >/dev/null

javac -encoding UTF-8 -cp "$PLATFORM:$GEN" -d "$OUT" \
  "$GEN"/com/dsha/whalepet/R.java \
  "$SRC/MiniJson.java" "$SRC/BalanceFmt.java" "$SRC/BalanceClient.java" \
  "$SRC/Http.java" "$SRC/Weather.java" "$SRC/Physics.java" "$SRC/Log.java" "$SRC/Txt.java" \
  tests/TestBalance.java tests/TestPhysics.java tests/TestWeather.java
fail=0
for t in TestBalance TestPhysics TestWeather; do
  echo "===== $t"
  if ! java -Dfile.encoding=UTF-8 -cp "$OUT:$PLATFORM" "com.dsha.whalepet.$t"; then fail=1; fi
done
rm -rf "$OUT"
exit $fail
