#!/bin/sh
set -eu
repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
sdk_dir=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}
ndk_dir=${ANDROID_NDK_HOME:-$sdk_dir/ndk/28.2.13676358}
test_dir=$(mktemp -d "${TMPDIR:-/tmp}/gcam-capture-test.XXXXXX")
trap 'rm -rf "$test_dir"' EXIT
headers=$ndk_dir/toolchains/llvm/prebuilt/darwin-x86_64/sysroot/usr/include
for folder in EGL GLES2 GLES3 KHR; do ln -s "$headers/$folder" "$test_dir/$folder"; done
java_dir=$(/usr/libexec/java_home)
angle_dir=$sdk_dir/emulator/lib64/gles_angle
driver_prefix=${GCAM_TEST_EGL_PREFIX-libswiftshader_}
clang -std=gnu11 -g -O1 -fsanitize=address,undefined -fno-omit-frame-pointer \
    -I"$repo_dir/app/src/test/native/support" -I"$test_dir" \
    -I"$java_dir/include" -I"$java_dir/include/darwin" \
    "$repo_dir/app/src/test/native/capture_backpressure_test.c" \
    "$angle_dir/${driver_prefix}libEGL.dylib" "$angle_dir/${driver_prefix}libGLESv2.dylib" -lpthread \
    -Wl,-rpath,"$angle_dir" -o "$test_dir/capture-test"
run_test() {
    DYLD_LIBRARY_PATH="$angle_dir" ASAN_OPTIONS=detect_leaks=0 "$test_dir/capture-test" "$@"
}
if [ "$#" -gt 0 ]; then
    run_test "$@"
else
    run_test --hud-isolation
    run_test --hud-swap-failure
    run_test --hud-geometry
    run_test
    run_test --fence-failure
    run_test --admission-all-busy
    run_test --admission-peer
    run_test --admission-generation
    run_test --admission-stop
    run_test --admission-sync-failure
    run_test --surface-reuse
    run_test --surface-current-failure
    run_test --surface-detach-failure
    run_test --frame-cpu-metrics
    run_test --pending-phase-swap
    run_test --pending-phase-wait
    run_test --pending-phase-finish
    run_test --exception-new-recorder-owner
    run_test --exception-new-hud-owner
fi
