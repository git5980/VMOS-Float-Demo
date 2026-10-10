#!/usr/bin/env bash
# VMOS 浮窗 Demo 直构链（aapt2 + ECJ/javac + d8 + zipalign + apksigner）
#
# v1.9 重写：去掉写死的 /home/z/* 绝对路径与明文签名口令；缺件时给出可操作的报错，
#           不再依赖某个人的机器布局。
#
# 用法：
#   ANDROID_SDK_ROOT=/path/to/android-sdk ./build.sh
#   # 想沿用原来的签名（同名包才可覆盖安装）：
#   KS=/path/to/twinbox.keystore KS_PASS=xxxx ./build.sh
#
# 环境变量：
#   ANDROID_SDK_ROOT / ANDROID_HOME  SDK 根目录（不设则探测常见位置）
#   BT_VERSION  build-tools 版本，默认 34.0.0
#   PLATFORM    平台版本，默认 android-34
#   PLAT_JAR    直接指定 android.jar（SDK 目录结构不标准时用）
#   ECJ         ecj.jar 路径；不设则回退 javac
#   KS / KS_PASS / KS_ALIAS  签名库 / 口令 / 别名（KS 不存在会自动生成）
set -euo pipefail

say() { printf '\n== %s\n' "$*"; }
die() { printf '构建失败：%s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- 环境探测
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK" ]; then
  for c in "$HOME/Android/Sdk" "$HOME/android-sdk" /opt/android-sdk /usr/lib/android-sdk; do
    if [ -d "$c/build-tools" ]; then SDK="$c"; break; fi
  done
fi
[ -n "$SDK" ] || die "没找到 Android SDK，请设置 ANDROID_SDK_ROOT=/path/to/android-sdk"

BT_VERSION="${BT_VERSION:-34.0.0}"
PLATFORM="${PLATFORM:-android-34}"
BT="$SDK/build-tools/$BT_VERSION"
PLAT_JAR="${PLAT_JAR:-$SDK/platforms/$PLATFORM/android.jar}"

MIN_SDK=24
TARGET_SDK=28
OUT=build

[ -d "$BT" ] || die "缺少 build-tools：$BT（sdkmanager \"build-tools;$BT_VERSION\"）"
[ -f "$PLAT_JAR" ] || die "缺少平台包：$PLAT_JAR（sdkmanager \"platforms;$PLATFORM\"）"

# 探测某个工具能否在本机真正执行：Google 的 aapt2/zipalign 只提供 x86-64 二进制，
# 在 arm64 Linux（本容器 / Termux / 树莓派）上执行会返回 126/127。
can_exec() {
  local rc=0
  "$1" "${@:2}" >/dev/null 2>&1 || rc=$?
  [ "$rc" -ne 126 ] && [ "$rc" -ne 127 ]
}

# aapt2 能跑才用它；跑不了就回退旧版 aapt（Debian/Ubuntu 有 arm64 包）。
AAPT2=""
if [ -x "$BT/aapt2" ] && can_exec "$BT/aapt2" version; then
  AAPT2="$BT/aapt2"
fi
AAPT_OLD="$(command -v aapt || true)"
[ -n "$AAPT2" ] || [ -n "$AAPT_OLD" ] || die "没有可用的 aapt2，也没装回退的 aapt（apt install aapt）"

D8="$BT/d8"
ZIPALIGN="$BT/zipalign"
if ! [ -x "$ZIPALIGN" ] || ! can_exec "$ZIPALIGN" -h; then
  ZIPALIGN="$(command -v zipalign || true)"
fi
[ -n "$ZIPALIGN" ] || die "缺少 zipalign（x86-64 版在本机跑不了时请 apt install zipalign）"

APKSIGNER="$BT/apksigner"
if ! [ -x "$APKSIGNER" ]; then
  APKSIGNER="$(command -v apksigner || true)"
fi
[ -n "$APKSIGNER" ] || die "缺少 apksigner"

command -v zip >/dev/null || die "缺少 zip 命令（apt install zip）"

ECJ="${ECJ:-}"
if [ -n "$ECJ" ]; then
  [ -f "$ECJ" ] || die "ECJ=$ECJ 不存在"
elif ! command -v javac >/dev/null; then
  die "既没有 javac 也没设置 ECJ=/path/to/ecj.jar（装 JDK：apt install openjdk-17-jdk-headless）"
fi
command -v java >/dev/null || die "缺少 java 运行时"
command -v keytool >/dev/null || die "缺少 keytool（随 JDK 提供，用于首次生成签名库）"

KS="${KS:-$OUT/twinbox.keystore}"
KS_PASS="${KS_PASS:-twinbox2026}"
KS_ALIAS="${KS_ALIAS:-twinbox}"

rm -rf "$OUT/classes" "$OUT/dex" "$OUT/gen"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/gen"

say "1/5 资源编译链接（minSdk=$MIN_SDK targetSdk=$TARGET_SDK）"
if [ -n "$AAPT2" ]; then
  "$AAPT2" compile --dir res -o "$OUT/res.zip"
  "$AAPT2" link -o "$OUT/base.apk" -I "$PLAT_JAR" \
    --manifest AndroidManifest.xml --java "$OUT/gen" \
    --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
    "$OUT/res.zip"
else
  echo "   aapt2 在本机跑不了（多半是 arm64），回退旧版 aapt：$AAPT_OLD"
  "$AAPT_OLD" package -f -m \
    -J "$OUT/gen" -M AndroidManifest.xml -S res -I "$PLAT_JAR" \
    --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
    -F "$OUT/base.apk"
fi

say "2/5 编译 Java"
find src "$OUT/gen" -name "*.java" > "$OUT/sources.txt"
set +e
if [ -n "$ECJ" ]; then
  echo "   编译器：ECJ（$ECJ）"
  java -jar "$ECJ" -1.8 -nowarn -encoding UTF-8 -bootclasspath "$PLAT_JAR" \
    -d "$OUT/classes" @"$OUT/sources.txt" > "$OUT/compile.log" 2>&1
else
  echo "   编译器：javac（$(javac -version 2>&1)）"
  javac -source 1.8 -target 1.8 -nowarn -Xlint:-options -encoding UTF-8 \
    -bootclasspath "$PLAT_JAR" -d "$OUT/classes" @"$OUT/sources.txt" > "$OUT/compile.log" 2>&1
fi
COMPILE_RC=$?
set -e
# 旧版用 `java ... || true` 丢掉退出码，再 grep "^[0-9]+\. ERROR"（依赖 ECJ 的输出格式）。
# 现在以退出码为准，同时兼容 javac 的 "error:" 与 ECJ 的 "N. ERROR" 两种写法。
if [ "$COMPILE_RC" -ne 0 ] || grep -qE 'error:|[0-9]+\. ERROR' "$OUT/compile.log"; then
  cat "$OUT/compile.log" >&2
  die "Java 编译报错（退出码 $COMPILE_RC，完整日志见 $OUT/compile.log）"
fi
if [ -s "$OUT/compile.log" ]; then
  cat "$OUT/compile.log"
fi

say "3/5 d8 出 dex"
find "$OUT/classes" -name "*.class" > "$OUT/classlist.txt"
[ -s "$OUT/classlist.txt" ] || die "没有编译产出任何 .class，检查上一步日志"
"$D8" --release --min-api "$MIN_SDK" --lib "$PLAT_JAR" --output "$OUT/dex" \
  $(tr '\n' ' ' < "$OUT/classlist.txt")

say "4/5 打包（把 dex 塞进资源 apk）"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
( cd "$OUT" && for d in dex/*.dex; do zip -q -j unsigned.apk "$d"; done )
"$ZIPALIGN" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

say "5/5 签名"
if [ ! -f "$KS" ]; then
  echo "   签名库不存在，自动生成：$KS"
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -alias "$KS_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=VMOS Float Demo, OU=Dev, O=TwinBox, L=NA, ST=NA, C=CN" >/dev/null
  echo "   ⚠ 口令取自 KS_PASS（当前是默认值，正式使用请自行覆盖）。"
  echo "     想沿用旧包签名必须显式传 KS/KS_PASS；换签名库后已装的旧版本无法覆盖安装。"
fi
"$APKSIGNER" sign --ks "$KS" --ks-key-alias "$KS_ALIAS" \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --out "$OUT/VMOS-Float-Demo.apk" "$OUT/aligned.apk"
"$APKSIGNER" verify --verbose "$OUT/VMOS-Float-Demo.apk"

say "完成：$OUT/VMOS-Float-Demo.apk"
DUMPER="${AAPT2:-$AAPT_OLD}"
"$DUMPER" dump badging "$OUT/VMOS-Float-Demo.apk" 2>/dev/null | head -6
