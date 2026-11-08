#!/usr/bin/env bash
set -euo pipefail

PROJECT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND="$(cd "${QAQH_BACKEND_DIR:-$PROJECT/../qaqh-backend}" && pwd)"
PROFILE="${1:-debug}"
NDK_VERSION=30.0.16248370
ABIS=(arm64-v8a x86_64)

case "$PROFILE" in
    debug) PROFILE_ARGS=() ;;
    release) PROFILE_ARGS=(--release) ;;
    *) echo "Usage: $0 [debug|release]" >&2; exit 2 ;;
esac

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] && [ -f "$PROJECT/local.properties" ]; then
    SDK=$(awk -F= '/^sdk.dir=/{sub(/^[^=]*=/, ""); print; exit}' "$PROJECT/local.properties")
fi
if [ -z "$SDK" ] || [ ! -d "$SDK/ndk/$NDK_VERSION" ]; then
    echo "Set ANDROID_HOME to an SDK with NDK $NDK_VERSION installed." >&2
    exit 1
fi
export ANDROID_NDK_HOME="$(cd "$SDK/ndk/$NDK_VERSION" && pwd)"
export CARGO_BUILD_JOBS="${CARGO_BUILD_JOBS:-2}"

OUTPUT="$PROJECT/app/build/rust-core/$PROFILE"
mkdir -p "$OUTPUT"
STAGE=$(mktemp -d "$OUTPUT/stage.XXXXXX")
trap 'rm -rf -- "$STAGE"' EXIT

cd "$BACKEND"
cargo ndk -t arm64-v8a -t x86_64 --platform 21 --link-libcxx-shared \
    -o "$STAGE/jniLibs" build --locked --lib -p qaqh-ffi "${PROFILE_ARGS[@]}"

BINDGEN_LIBRARY="$STAGE/jniLibs/arm64-v8a/libqaqh_ffi.so"
if [ "$PROFILE" = release ]; then
    # The workspace release profile strips the symbol table used by bindgen.
    cargo build --locked --lib -p qaqh-ffi --target-dir "$BACKEND/target"
    HOST_LIBRARY=$(rustc --print file-names --crate-name qaqh_ffi --crate-type cdylib - </dev/null)
    BINDGEN_LIBRARY="$BACKEND/target/debug/$HOST_LIBRARY"
fi

cargo run --locked -p qaqh-ffi --bin uniffi-bindgen -- generate \
    --library "$BINDGEN_LIBRARY" \
    --language kotlin --config crates/qaqh-ffi/uniffi-global.toml \
    --out-dir "$STAGE/bindings" --no-format

BINDING=com/qaqh/harness/core/ffi/qaqh_ffi.kt
cp "$STAGE/bindings/$BINDING" "$PROJECT/app/src/main/java/$BINDING"
for abi in "${ABIS[@]}"; do
    mkdir -p "$OUTPUT/jniLibs/$abi"
    cp "$STAGE/jniLibs/$abi/"{libqaqh_ffi.so,libc++_shared.so} "$OUTPUT/jniLibs/$abi/"
done
echo "Rust core ($PROFILE): $OUTPUT/jniLibs"
