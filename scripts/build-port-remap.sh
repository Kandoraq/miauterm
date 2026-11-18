#!/usr/bin/env bash
set -euo pipefail
compiler="${ARM64_GLIBC_CC:-aarch64-linux-gnu-gcc}"
command -v "$compiler" >/dev/null || {
    echo "ARM64 glibc cross compiler not found: $compiler" >&2
    exit 1
}
out="app/build/generated/port-remap-assets/libmiauterm-portmap.so"
mkdir -p "$(dirname "$out")"
"$compiler" -shared -fPIC -O2 -Wall -Wextra -Werror \
    -Wl,-z,relro,-z,now -Wl,--no-undefined \
    app/src/main/cpp/miauterm-portmap.c -ldl -o "$out"
readelf -h "$out" | grep -F 'AArch64'
printf 'Built guest port mapping library: %s\n' "$out"
