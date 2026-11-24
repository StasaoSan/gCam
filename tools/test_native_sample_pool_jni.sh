#!/bin/sh
set -eu
repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
test_dir=$(mktemp -d "${TMPDIR:-/tmp}/gcam-native-pool-jni.XXXXXX")
trap 'rm -rf "$test_dir"' EXIT
if [ -n "${JAVA_HOME:-}" ]; then
    java_dir=$JAVA_HOME
elif [ -x /usr/libexec/java_home ]; then
    java_dir=$(/usr/libexec/java_home)
else
    java_dir=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")
fi
case $(uname -s) in
    Darwin) jni_platform=darwin; lib_name=libgcam_buffer.dylib ;;
    Linux) jni_platform=linux; lib_name=libgcam_buffer.so ;;
    *) printf '%s\n' 'Unsupported JNI host platform' >&2; exit 1 ;;
esac
clang -std=c11 -D_POSIX_C_SOURCE=200809L -Wall -Wextra -Werror -g -O1 -pthread \
    -shared -fPIC -I"$java_dir/include" -I"$java_dir/include/$jni_platform" \
    "$repo_dir/app/src/main/cpp/native_sample_pool.c" \
    "$repo_dir/app/src/main/cpp/native_sample_pool_jni.c" -o "$test_dir/$lib_name"
"$java_dir/bin/javac" -d "$test_dir/classes" \
    "$repo_dir/app/src/test/native/jni/com/stasao/gcam/NativeSampleMemory.java"
"$java_dir/bin/java" -cp "$test_dir/classes" com.stasao.gcam.NativeSampleMemory "$test_dir/$lib_name"
