#!/bin/sh
set -eu
repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
test_dir=$(mktemp -d "${TMPDIR:-/tmp}/gcam-native-pool-test.XXXXXX")
trap 'rm -rf "$test_dir"' EXIT
clang -std=c11 -D_POSIX_C_SOURCE=200809L -Wall -Wextra -Werror -g -O1 \
    -fsanitize=address,undefined -fno-omit-frame-pointer -pthread \
    "$repo_dir/app/src/test/native/native_sample_pool_test.c" \
    "$repo_dir/app/src/main/cpp/native_sample_pool.c" -o "$test_dir/native_sample_pool"
"$test_dir/native_sample_pool"
