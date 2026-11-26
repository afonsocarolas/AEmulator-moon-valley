#!/bin/sh
# gralloc_gb.c (Android 2.3, in front of the firmware's own gralloc) with zig cc, like native/gralloc1/build-zig.sh: the
# imports are resolved against stand-in libc/libdl that carry no code; the guest's bionic provides the real ones.
#   sh build-gb.sh [output]     (default: the engine asset engines/kk/gralloc.yuv-gb.so)
set -eu
ZIG=${ZIG:-python3 -m ziglang}
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${1:-$HERE/../../app/src/main/assets/engines/kk/gralloc.yuv-gb.so}
STUB=$(mktemp -d); trap 'rm -rf "$STUB"' EXIT
cat > "$STUB/libc.c" <<'EOT'
void *malloc(unsigned long n) { (void)n; return 0; }
void free(void *p) { (void)p; }
EOT
cat > "$STUB/libdl.c" <<'EOT'
void *dlopen(const char *p, int f) { (void)p; (void)f; return 0; }
void *dlsym(void *h, const char *n) { (void)h; (void)n; return 0; }
EOT
T="--target=thumb-linux-musleabi -mcpu=cortex_a8 -mfloat-abi=soft -fPIC -shared -nostdlib -ffreestanding -fno-builtin"
$ZIG cc $T -Os -Wl,-soname,libc.so -o "$STUB/libc.so" "$STUB/libc.c"
$ZIG cc $T -Os -Wl,-soname,libdl.so -o "$STUB/libstubdl.so" "$STUB/libdl.c"
$ZIG cc $T -Os -fvisibility=hidden -fno-stack-protector -fno-exceptions -fno-unwind-tables -fno-asynchronous-unwind-tables \
  -Wl,-z,norelro -Wl,-z,now -Wl,--build-id=none -Wl,--hash-style=both -Wl,--no-undefined -Wl,-s -Wl,-soname,gralloc.default.so \
  -L"$STUB" -o "$OUT" "$HERE/gralloc_gb.c" -Wl,--no-as-needed -lc -lstubdl
if grep -q -E '__aeabi_(u?idiv|uldiv|ldiv|uidivmod|ldivmod)|__aeabi_unwind|__stack_chk|__divsi3|__divdi3' "$OUT"; then echo "error: $OUT imports a helper the guest libc lacks" >&2; exit 1; fi
ls -l "$OUT"
