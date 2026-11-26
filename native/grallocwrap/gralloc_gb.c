/*
 * Added for AEmulator Moon Valley, 2026-11-26. GPL-3.0; see LICENSE and NOTICE.md.
 *
 * gralloc.default.so for Android 2.3 firmware that keeps its own default gralloc (Sony Ericsson 2011: Xperia Play). That
 * gralloc allocates RGB formats only. On the phone, video went through the Qualcomm gralloc (pmem), which the emulator
 * sets aside, so a video window (YCrCb_420_SP, NV21) got "alloc(...) failed -22" in SurfaceFlinger, no buffer at all,
 * and the game behind it (FIFA 10's intro movie) waited on a black screen for ever.
 *
 * This module forwards everything to the firmware's own, kept as /system/lib/libgralloc_firmware.so, and turns a YUV
 * request into an RGBA one of at least the same byte size. 2.3's SurfaceFlinger shows a YUV buffer by itself (its
 * TextureManager uploads the Y plane), and a decoder writes the planes where the format puts them, from the base.
 *
 * The firmware module is loaded at its first use, not in a constructor: 2.3's linker holds its lock while it runs a
 * library's constructors, and a dlopen there never returns.
 */
#include <stdint.h>

extern void *dlopen(const char *path, int flags);
extern void *dlsym(void *h, const char *name);
extern void *malloc(unsigned long n);
extern void free(void *p);

#define EXPORT __attribute__((visibility("default")))

struct hw_module;
struct hw_device;
struct hw_module_methods { int (*open)(const struct hw_module *m, const char *id, struct hw_device **dev); };
struct hw_module {
    uint32_t tag;
    uint16_t version_major, version_minor;
    const char *id, *name, *author;
    struct hw_module_methods *methods;
    void *dso;
    uint32_t reserved[25];
};
struct hw_device {
    uint32_t tag, version;
    struct hw_module *module;
    uint32_t reserved[12];
    int (*close)(struct hw_device *dev);
};
struct gralloc_module {
    struct hw_module common;
    int (*registerBuffer)(const struct gralloc_module *m, const void *handle);
    int (*unregisterBuffer)(const struct gralloc_module *m, const void *handle);
    int (*lock)(const struct gralloc_module *m, const void *handle, int usage, int l, int t, int w, int h, void **vaddr);
    int (*unlock)(const struct gralloc_module *m, const void *handle);
    int (*perform)(const struct gralloc_module *m, int operation, ...);
    void *reserved_proc[7];
};
struct alloc_device {
    struct hw_device common;
    int (*alloc)(struct alloc_device *d, int w, int h, int format, int usage, const void **handle, int *stride);
    int (*free)(struct alloc_device *d, const void *handle);
    void *reserved_proc[8];
};
struct wrap_device {
    struct alloc_device dev;
    struct alloc_device *real;
};

#define HARDWARE_MODULE_TAG 0x484d4f44
#define RGBA_8888 1

static const struct gralloc_module *real;

static const struct gralloc_module *firmware(void) {
    if (!real) {
        void *h = dlopen("/system/lib/libgralloc_firmware.so", 0 /* RTLD_NOW on bionic */);
        if (h) real = dlsym(h, "HMI");
    }
    return real;
}

/* Bytes a YUV format needs at [w] x [h]; 0 for any other format. */
static long yuv_bytes(int format, int w, int h) {
    long y = (long)w * h, c420 = 2L * ((w + 1) >> 1) * ((h + 1) >> 1);
    switch (format) {
    case 0x11:                  /* YCrCb_420_SP (NV21) */
    case 0x13:                  /* YCbCr_420_P */
    case 0x15:                  /* YCbCr_420_I */
    case 0x109:                 /* Qualcomm YCbCr_420_SP (NV12) */
    case 0x10a:                 /* Qualcomm YCrCb_420_SP_ADRENO */
    case 0x7fa30c03:            /* Qualcomm NV12 tiled */
        return y + c420 + 8192; /* (tiled and aligned layouts run a little past the plain size) */
    case 0x32315659: {          /* YV12: rows aligned to 16 */
        long ys = (w + 15) & ~15, cs = ((ys >> 1) + 15) & ~15;
        return ys * h + 2L * cs * ((h + 1) >> 1);
    }
    case 0x10:                  /* YCbCr_422_SP (NV16) */
    case 0x12:                  /* YCbCr_422_P */
    case 0x14:                  /* YCbCr_422_I (YUY2) */
    case 0x16:                  /* CbYCrY_422_I */
        return 2L * y;
    }
    return 0;
}

/* n / d by shift and subtract: 2.3's libc has no __aeabi_uidiv to import */
static unsigned long udiv(unsigned long n, unsigned long d) {
    unsigned long q = 0;
    for (int i = 31; i >= 0; i--) if ((n >> i) >= d) { n -= d << i; q |= 1UL << i; }
    return q;
}

static int d_alloc(struct alloc_device *d, int w, int h, int format, int usage, const void **handle, int *stride) {
    struct alloc_device *r = ((struct wrap_device *)d)->real;
    long need = yuv_bytes(format, w, h);
    if (!need) return r->alloc(r, w, h, format, usage, handle, stride);
    if (w <= 0 || h <= 0 || w > 4096 || h > 4096) return -22;
    /* rows of the buffer's own width: the firmware gralloc sizes it as stride x rows x 4 */
    int rows = (int)udiv((unsigned long)(need + 4L * w - 1), 4UL * (unsigned long)w);
    int unused = 0;
    int e = r->alloc(r, w, rows, RGBA_8888, usage, handle, &unused);
    if (e == 0) *stride = format == 0x32315659 ? ((w + 15) & ~15) : w;
    return e;
}
static int d_free(struct alloc_device *d, const void *handle) { struct alloc_device *r = ((struct wrap_device *)d)->real; return r->free(r, handle); }
static int d_close(struct hw_device *d) {
    struct alloc_device *r = ((struct wrap_device *)d)->real;
    int e = r->common.close ? r->common.close(&r->common) : 0;
    free(d);
    return e;
}

static int w_open(const struct hw_module *m, const char *id, struct hw_device **out) {
    const struct gralloc_module *g = firmware();
    if (!g) return -22;
    struct hw_device *dev = 0;
    int e = g->common.methods->open(&g->common, id, &dev);
    if (e || !dev) return e ? e : -22;
    /* "gpu0" is the allocator; the framebuffer ("fb0") stays the firmware's as it is */
    if (!(id[0] == 'g' && id[1] == 'p' && id[2] == 'u' && id[3] == '0' && !id[4])) { *out = dev; return 0; }
    struct wrap_device *wd = malloc(sizeof *wd);
    if (!wd) { *out = dev; return 0; }
    wd->dev = *(struct alloc_device *)dev;
    wd->real = (struct alloc_device *)dev;
    wd->dev.common.module = (struct hw_module *)m;
    wd->dev.common.close = d_close;
    wd->dev.alloc = d_alloc;
    wd->dev.free = d_free;
    *out = &wd->dev.common;
    return 0;
}

static int w_register(const struct gralloc_module *m, const void *h) { (void)m; return firmware() ? real->registerBuffer(real, h) : -22; }
static int w_unregister(const struct gralloc_module *m, const void *h) { (void)m; return firmware() ? real->unregisterBuffer(real, h) : -22; }
static int w_lock(const struct gralloc_module *m, const void *h, int usage, int l, int t, int w, int hh, void **vaddr) {
    (void)m; return firmware() ? real->lock(real, h, usage, l, t, w, hh, vaddr) : -22;
}
static int w_unlock(const struct gralloc_module *m, const void *h) { (void)m; return firmware() ? real->unlock(real, h) : -22; }
static int w_perform(const struct gralloc_module *m, int op, long a, long b, long c, long d, long e, long f) {
    (void)m;
    if (!firmware() || !real->perform) return -22;
    return real->perform(real, op, a, b, c, d, e, f);
}

static struct hw_module_methods methods = { w_open };

EXPORT struct gralloc_module HMI = {
    .common = {
        .tag = HARDWARE_MODULE_TAG,
        .version_major = 1,
        .version_minor = 0,
        .id = "gralloc",
        .name = "AEmulator gralloc 2.3 (firmware + YUV)",
        .author = "AEmulator Moon Valley",
        .methods = &methods,
    },
    .registerBuffer = w_register,
    .unregisterBuffer = w_unregister,
    .lock = w_lock,
    .unlock = w_unlock,
    .perform = (int (*)(const struct gralloc_module *, int, ...))w_perform,
};
