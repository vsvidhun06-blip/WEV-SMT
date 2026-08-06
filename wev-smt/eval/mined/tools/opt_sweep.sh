#!/bin/bash
# Optimisation-level sweep for the fake-dependency probe kernel.
# Answers: at which -O level does a fake dependency still exist as machine-level
# load -> store register flow? Run from repo root inside WSL:
#     bash eval/mined/tools/opt_sweep.sh
set -u
cd "$(dirname "$0")/../../.." || exit 1
SRC=eval/mined/src/lkmm_fakedep.c
OUT=eval/mined/asm/optsweep
mkdir -p "$OUT"

FUNCS="lb_xor lb_linear lb_sub lb_andzero lb_mulzero lb_selfand lb_copy lb_fakectrl lb_realctrl lb_modone lb_orcompl lb_shiftmask"

for lvl in O0 O1 O2 O3 Os; do
  for cfg in "clang-aarch64:clang -S -std=c11 --target=aarch64-linux-gnu" \
             "gcc-aarch64:aarch64-linux-gnu-gcc -S -std=c11" \
             "clang-x86_64:clang -S -std=c11" \
             "gcc-x86_64:gcc -S -std=c11"; do
    tag=${cfg%%:*}; cmd=${cfg#*:}
    dst="$OUT/lkmm_fakedep.$tag.$lvl.s"
    $cmd "-$lvl" -fno-asynchronous-unwind-tables -o "$dst" "$SRC" 2>/dev/null
  done
done

echo "opt-level sweep written to $OUT/ ($(ls "$OUT"/*.s | wc -l) files)"
echo
printf '%-14s %-14s %s\n' LEVEL CONFIG "idiom instructions found (eor r,r,r / sub r,r,r / and r,r,#0 / mul r,r,zr)"
for lvl in O0 O1 O2 O3 Os; do
  for tag in clang-aarch64 gcc-aarch64 clang-x86_64 gcc-x86_64; do
    f="$OUT/lkmm_fakedep.$tag.$lvl.s"
    [ -f "$f" ] || continue
    n=$(grep -cE '\b(eor|sub|and|orr)[[:space:]]+([wx][0-9]+)[[:space:]]*,[[:space:]]*\2[[:space:]]*,[[:space:]]*\2\b|\band[[:space:]]+[wx][0-9]+[[:space:]]*,[[:space:]]*[wx][0-9]+[[:space:]]*,[[:space:]]*#?0\b|\bmul[[:space:]]+[wx][0-9]+[[:space:]]*,[[:space:]]*[wx][0-9]+[[:space:]]*,[[:space:]]*[wx]zr\b|\b(xorl|subl|andl)[[:space:]]+%([a-z0-9]+),[[:space:]]*%\5\b' "$f")
    printf '%-14s %-14s %s\n' "$lvl" "$tag" "$n"
  done
done
