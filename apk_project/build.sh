#!/usr/bin/env bash
# 纯命令行编译 CastList APK（不需要 Gradle / Android Studio）
# 用法：./apk_project/build.sh   （在仓库根目录执行也可以）
set -e

# javac/d8 读取 @argfile 时使用 UTF-8，避免路径含中文时报 MalformedInputException
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Dfile.encoding=UTF-8"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-C:/Android}"
# 若 C:/Android 不存在，尝试常见默认路径
if [ ! -d "$SDK" ]; then SDK="${HOME}/Android/Sdk"; fi
BT_NAME="${BUILD_TOOLS_VERSION:-34.0.0}"
PLATFORM="${PLATFORM_VERSION:-android-34}"
BT="$SDK/build-tools/$BT_NAME"
AJAR="$SDK/platforms/$PLATFORM/android.jar"
PY="${PYTHON:-python3}"
BUILD="$ROOT/.build"
OUT_DIR="$ROOT/apk"
OUT="$OUT_DIR/tvguo-castlist.apk"
KEY="${KEYSTORE:-$ROOT/castlist.jks}"

if [ ! -f "$AJAR" ]; then
  echo "ERROR: android.jar not found: $AJAR" >&2
  echo "Set ANDROID_HOME and PLATFORM_VERSION, e.g. ANDROID_HOME=/path/to/Sdk" >&2
  exit 1
fi

# Git Bash 下传给 Windows 原生工具的路径要转成 D:/... 形式
w() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi
}

# 清理构建目录（用 Python，避免不同 shell 下 rm -rf 行为差异）
"$PY" - "$(w "$BUILD")" <<'PY'
import shutil, sys
shutil.rmtree(sys.argv[1], ignore_errors=True)
PY
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$OUT_DIR"
cp -r "$ROOT/apk_project/src" "$BUILD/"
cp "$ROOT/apk_project/AndroidManifest.xml" "$BUILD/"

echo "=== [1/6] aapt2 link ==="
"$(w "$BT/aapt2.exe")" link -I "$(w "$AJAR")" \
    --manifest "$(w "$BUILD/AndroidManifest.xml")" \
    -o "$(w "$BUILD/base.apk")" --java "$(w "$BUILD/gen")" \
    --min-sdk-version 21 --target-sdk-version 30

echo "=== [2/6] javac ==="
find "$BUILD/src" "$BUILD/gen" -name '*.java' 2>/dev/null | while read -r f; do
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$f"; else echo "$f"; fi
done > "$BUILD/sources.txt"
javac -source 8 -target 8 -encoding UTF-8 \
    -bootclasspath "$(w "$AJAR")" -cp "$(w "$AJAR")" \
    -d "$(w "$BUILD/classes")" "@$(w "$BUILD/sources.txt")"

echo "=== [3/6] d8 (class -> dex) ==="
find "$BUILD/classes" -name '*.class' | while read -r f; do
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$f"; else echo "$f"; fi
done > "$BUILD/classlist.txt"
java -cp "$(w "$BT/lib/d8.jar")" com.android.tools.r8.D8 \
    --lib "$(w "$AJAR")" --min-api 21 \
    --output "$(w "$BUILD/dex")" "@$(w "$BUILD/classlist.txt")"

echo "=== [4/6] 写入 classes.dex ==="
"$PY" - "$(w "$BUILD/base.apk")" "$(w "$BUILD/dex/classes.dex")" <<'PY'
import zipfile, os, sys
base, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(base, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex, 'classes.dex')
print('dex added, apk size =', os.path.getsize(base))
PY

echo "=== [5/6] zipalign ==="
"$(w "$BT/zipalign.exe")" -f -p 4 \
    "$(w "$BUILD/base.apk")" "$(w "$BUILD/aligned.apk")"

echo "=== [6/6] 签名 ==="
if [ ! -f "$KEY" ]; then
  keytool -genkeypair -keystore "$(w "$KEY")" \
    -storepass 123456 -keypass 123456 -alias castlist \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=CastList, OU=DIY, O=Home, L=Home, ST=Home, C=CN" 2>/dev/null
fi
java -jar "$(w "$BT/lib/apksigner.jar")" sign \
    --ks "$(w "$KEY")" --ks-pass pass:123456 --key-pass pass:123456 \
    --out "$(w "$BUILD/castlist.apk")" "$(w "$BUILD/aligned.apk")"

echo "=== 校验签名 ==="
java -jar "$(w "$BT/lib/apksigner.jar")" verify --verbose "$(w "$BUILD/castlist.apk")"

cp "$BUILD/castlist.apk" "$OUT"
ls -l "$OUT"
echo BUILD_OK
