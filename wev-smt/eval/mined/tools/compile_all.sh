#!/bin/bash
# Phase A step 2 — compile every mined kernel with each (compiler, arch) configuration
# and keep the -O2 assembly. Run from the repo root inside WSL:
#     bash eval/mined/tools/compile_all.sh
#
# Four configurations, all real installed toolchains (versions recorded in
# eval/mined/asm/TOOLCHAIN.txt):
#     clang -O2 -S  x86_64        clang -O2 -S --target=aarch64-linux-gnu
#     gcc   -O2 -S  x86_64        aarch64-linux-gnu-gcc -O2 -S
set -u

SRC=eval/mined/src
OUT=eval/mined/asm
mkdir -p "$OUT"

{
  echo "# toolchain provenance for eval/mined/asm/*.s"
  echo "# generated $(date -u +%Y-%m-%dT%H:%M:%SZ) on $(uname -srm)"
  echo
  echo "clang:                 $(clang --version | head -1)"
  echo "gcc:                   $(gcc --version | head -1)"
  echo "aarch64-linux-gnu-gcc: $(aarch64-linux-gnu-gcc --version | head -1)"
  echo
  echo "flags: -O2 -S -fno-asynchronous-unwind-tables -fomit-frame-pointer"
} > "$OUT/TOOLCHAIN.txt"

CFLAGS="-O2 -S -std=c11 -fno-asynchronous-unwind-tables -fomit-frame-pointer"

compile() {   # compile <tag> <compiler-cmd...> -- <src>
  local tag="$1"; shift
  local src="${!#}"
  local base
  base=$(basename "$src" .c)
  local dst="$OUT/${base}.${tag}.s"
  if "$@" -o "$dst" 2> "$OUT/${base}.${tag}.err"; then
    if [ -s "$OUT/${base}.${tag}.err" ]; then
      echo "  [warn] $base $tag (see ${base}.${tag}.err)"
    else
      rm -f "$OUT/${base}.${tag}.err"
    fi
    echo "  [ok]   $base $tag"
  else
    echo "  [FAIL] $base $tag"
    head -3 "$OUT/${base}.${tag}.err" | sed 's/^/         /'
  fi
}

for src in "$SRC"/*.c; do
  echo "$(basename "$src"):"
  compile clang-x86_64  clang   $CFLAGS                              "$src"
  compile clang-aarch64 clang   $CFLAGS --target=aarch64-linux-gnu   "$src"
  compile gcc-x86_64    gcc     $CFLAGS                              "$src"
  compile gcc-aarch64   aarch64-linux-gnu-gcc $CFLAGS                "$src"
done

echo
echo "assembly written to $OUT/ ($(ls "$OUT"/*.s 2>/dev/null | wc -l) files)"
