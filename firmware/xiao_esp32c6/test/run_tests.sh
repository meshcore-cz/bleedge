#!/usr/bin/env bash
# Compiles the firmware mesh logic on the host and cross-checks it against the
# Go reference implementation (wire format, CRC32, fragmentation, relay transcode).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"
bin="$(mktemp -t bleedge_host_test.XXXXXX)"
trap 'rm -f "$bin"' EXIT

echo "==> compiling host_test"
ed="$here/../src/ed25519"
c++ -std=c++17 -I"$here" -I"$here/.." -I"$ed" \
  "$here/host_test.cpp" "$here/../mesh.cpp" \
  "$ed/fe.c" "$ed/ge.c" "$ed/sc.c" "$ed/sha512.c" \
  "$ed/keypair.c" "$ed/sign.c" "$ed/verify.c" \
  -o "$bin"

echo "==> running cross-check against Go reference"
cd "$repo"
go run ./firmware/xiao_esp32c6/test/crosscheck "$bin"
