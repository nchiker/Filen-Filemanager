#!/usr/bin/env bash
# Builds the official Filen Rust SDK (filen-sdk-rs, `uniffi` feature) for Android and
# generates the Kotlin UniFFI bindings. Usage: ./build-rust.sh [arm64-v8a] [x86_64]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/.local/share/filen-android-sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$(ls -d "$ANDROID_HOME"/ndk/* | tail -1)}"
export PATH="$HOME/.claude/.cargo/bin:$HOME/.cargo/bin:$PATH"
# filen-rs/.cargo/config.toml pins the *host* linker to clang+wild, neither of which is installed
# here; substitute them via env so the upstream checkout stays unmodified.
# The NDK ships an x86_64-linux clang; `wild` is shimmed to the NDK's ld.lld.
NDKBIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
mkdir -p "$ROOT/build-out/shim"
printf '#!/bin/sh\nexec "%s/ld.lld" "$@"\n' "$NDKBIN" > "$ROOT/build-out/shim/wild"
chmod +x "$ROOT/build-out/shim/wild"
export PATH="$ROOT/build-out/shim:$PATH"
export CARGO_TARGET_X86_64_UNKNOWN_LINUX_GNU_LINKER="$NDKBIN/clang"
ABIS=("${@:-arm64-v8a}")
cd "$ROOT/external/filen-rs"
ARGS=(); for a in "${ABIS[@]}"; do ARGS+=(-t "$a"); done
cargo ndk "${ARGS[@]}" -o "$ROOT/filen/src/main/jniLibs" build --release -p filen-sdk-rs --features uniffi
# Kotlin bindings, generated from the freshly built library
LIB="$(ls target/aarch64-linux-android/release/libfilen_sdk_rs.so 2>/dev/null || ls target/*/release/libfilen_sdk_rs.so | head -1)"
rm -rf "$ROOT/build-out/kotlin"
cargo run --release --bin uniffi-bindgen generate --library "$LIB" --language kotlin \
  --out-dir "$ROOT/build-out/kotlin" --no-format
mkdir -p "$ROOT/filen/src/main/java/generated"
rm -rf "$ROOT/filen/src/main/java/generated/uniffi"
cp -r "$ROOT/build-out/kotlin/uniffi" "$ROOT/filen/src/main/java/generated/"
python3 -I "$ROOT/tools/patch_uniffi_kotlin.py" "$ROOT"/filen/src/main/java/generated/uniffi/*/*.kt
echo "Rust build + bindings OK"
