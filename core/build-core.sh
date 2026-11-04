#!/usr/bin/env bash
# build-core.sh —— 交叉编译 Rust core(.so)并生成 UniFFI Kotlin 绑定,装入 app。
#
# 前置环境(本机已就绪,重装时参照):
#   1. source /home/qaqtamsy/Projects/hiandroid/env.sh   # ANDROID_HOME(SDK 根)
#   2. sdkmanager "ndk;30.0.16248370"                    # NDK
#   3. rustup target add aarch64-linux-android x86_64-linux-android
#   4. cargo install cargo-ndk
#
# 用法:
#   ./core/build-core.sh            # 默认 debug(迭代快,内存友好)
#   ./core/build-core.sh release    # 发布(继承 workspace LTO,注意内存门控)
#
# 资源门控:8G 内存机器,zram swap 已满时 cargo 可能 OOM——脚本内置 free 检查。
set -euo pipefail

PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND="$PROJ/../qaqh-backend"
PROFILE="${1:-debug}"
ABIS=("arm64-v8a" "x86_64")
TARGETS=("aarch64-linux-android" "x86_64-linux-android")

source /home/qaqtamsy/Projects/hiandroid/env.sh >/dev/null

# ── 资源门控 ────────────────────────────────────────────────────────────
AVAIL_MB=$(free -m | awk 'NR==2{print $7}')
if [ "${AVAIL_MB:-0}" -lt 2500 ]; then
    echo "❌ 可用内存仅 ${AVAIL_MB}MB(<2500MB)。先 ./gradlew --stop 或等待回收。"
    exit 1
fi
echo "✅ 内存门控通过(可用 ${AVAIL_MB}MB)"

# ── Rust .so ────────────────────────────────────────────────────────────
FLAGS=(--lib -p qaqh-ffi)
[ "$PROFILE" = "release" ] && FLAGS+=(--release)
(cd "$BACKEND" && cargo ndk \
    $(for t in "${ABIS[@]}"; do printf -- "-t %s " "$t"; done) \
    -o "$PROJ/app/src/main/jniLibs" \
    build "${FLAGS[@]}")

# ── strip + C++ 运行时 ──────────────────────────────────────────────────
# libqaqh_ffi.so 依赖 libc++_shared.so(NDK clang 链接的 C++ 运行时),必须随包。
NDK_DIR=$(ls -d "$ANDROID_HOME"/ndk/* 2>/dev/null | sort -V | tail -1)
STRIP="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
declare -A ABI_MAP=( ["arm64-v8a"]="aarch64-linux-android" ["x86_64"]="x86_64-linux-android" )
for abi in "${ABIS[@]}"; do
    so="$PROJ/app/src/main/jniLibs/$abi/libqaqh_ffi.so"
    [ -f "$so" ] && "$STRIP" "$so"
    cp "$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/${ABI_MAP[$abi]}/libc++_shared.so" \
       "$PROJ/app/src/main/jniLibs/$abi/libc++_shared.so"
done

# ── Kotlin 绑定 ─────────────────────────────────────────────────────────
# host 侧 cdylib(--library 需要);debug 产物即可,metadata 与 profile 无关。
(cd "$BACKEND" && cargo build -q -p qaqh-ffi --lib)
(cd "$BACKEND" && cargo run -q -p qaqh-ffi --bin uniffi-bindgen -- generate \
    --library target/debug/libqaqh_ffi.so \
    --language kotlin \
    --config crates/qaqh-ffi/uniffi-global.toml \
    --out-dir "$PROJ/app/src/main/java" \
    --no-format)

# ── 产物清单 ────────────────────────────────────────────────────────────
echo "── 产物 ──"
for abi in "${ABIS[@]}"; do
    so="$PROJ/app/src/main/jniLibs/$abi/libqaqh_ffi.so"
    [ -f "$so" ] && du -h "$so"
done
KT=$(find "$PROJ/app/src/main/java/com/qaqh/harness/core/ffi" -name "*.kt" | head -1)
[ -n "$KT" ] && echo "bindings: ${KT#$PROJ/}"
echo "✅ core 构建完成($PROFILE)"
