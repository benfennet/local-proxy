#!/bin/bash
# 本地代理 APK 构建（无 Gradle，原始工具链：aapt2 → javac → d8 → zipalign → apksigner）
# 需要：JAVA_HOME（JDK 17）、ANDROID_HOME（platforms;android-34 + build-tools;34.0.0）
set -e
cd "$(dirname "$0")"
JAVA_HOME="${JAVA_HOME:-$HOME/workspace/tools/jdk17}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/workspace/tools/android-sdk}"
export PATH="$JAVA_HOME/bin:$PATH"
BT="$ANDROID_HOME/build-tools/34.0.0"
AJAR="$ANDROID_HOME/platforms/android-34/android.jar"
KS="release.keystore"
KSPASS_FILE="keystore.pass"

if [ ! -f "$KS" ]; then
  # 首次：生成发布密钥（只在本机，永不入库；丢了就无法覆盖升级，务必备份）
  openssl rand -hex 24 > "$KSPASS_FILE"
  keytool -genkeypair -keystore "$KS" -alias tvproxy -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$(cat "$KSPASS_FILE")" -keypass "$(cat "$KSPASS_FILE")" \
    -dname "CN=benfen localproxy, O=benfen.net" 2>/dev/null
  echo "已生成签名密钥 $KS（密码在 $KSPASS_FILE，两者都不要提交到公开仓库）"
fi

rm -rf build dist
mkdir -p build/gen build/classes build/dex dist

echo "[1/5] aapt2 资源与清单"
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/app-unsigned.apk -I "$AJAR" \
  --manifest AndroidManifest.xml -R build/res.zip \
  --java build/gen --auto-add-overlay

echo "[2/5] javac"
find src build/gen -name '*.java' > build/sources.txt
javac -encoding UTF-8 -source 1.8 -target 1.8 \
  -bootclasspath "$AJAR" -cp "$AJAR" -d build/classes @build/sources.txt

echo "[3/5] d8 转 dex"
(cd build/classes && "$JAVA_HOME/bin/jar" cf ../classes.jar .)
"$BT/d8" --lib "$AJAR" --output build/dex build/classes.jar

echo "[4/5] 合包 + 对齐"
(cd build/dex && zip -q ../app-unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/app-unsigned.apk build/app-aligned.apk

echo "[5/5] 签名"
TVPROXY_PASS="$(cat "$KSPASS_FILE")"
export TVPROXY_PASS
"$BT/apksigner" sign --ks "$KS" --ks-pass env:TVPROXY_PASS \
  --key-pass env:TVPROXY_PASS --out dist/tvproxy.apk build/app-aligned.apk
"$BT/apksigner" verify --print-certs dist/tvproxy.apk | head -3
ls -la dist/tvproxy.apk
echo "构建完成：dist/tvproxy.apk"
