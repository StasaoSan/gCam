#!/bin/sh
set -eu
repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
test_dir=$(mktemp -d "${TMPDIR:-/tmp}/gcam-cadence-test.XXXXXX")
trap 'rm -rf "$test_dir"' EXIT
for test_name in recorder_load_policy frame_cadence; do
    clang -std=c11 -Wall -Wextra -Werror -g -O1 \
        -fsanitize=address,undefined -fno-omit-frame-pointer \
        "$repo_dir/app/src/test/native/${test_name}_test.c" -o "$test_dir/$test_name"
    "$test_dir/$test_name"
done
