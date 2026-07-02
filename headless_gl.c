/*
 * headless_gl.c — Headless EGL/Mesa software rendering context for libretro.
 * Provides a PBuffer-backed OpenGL 3.1 Core context via Mesa's llvmpipe.
 * Used by the RetroConsole libretro bridge to supply HW render contexts
 * to cores like Flycast that request OpenGL Core for hardware rendering.
 *
 * Build:
 *   gcc -shared -fPIC -O2 -o .libheadless_gl.so headless_gl.c -lEGL -lGL -ldl
 *   strip .libheadless_gl.so
 */

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GL/gl.h>
#include <stdio.h>
#include <string.h>
#include <dlfcn.h>
#include <pthread.h>

/* ---- globals ---- */
static EGLDisplay  s_display   = EGL_NO_DISPLAY;
static EGLConfig   s_config;
static EGLContext   s_context   = EGL_NO_CONTEXT;  /* init-thread context */
static int         s_initialized = 0;
static int         s_w = 640, s_h = 480;

/* Per-thread surface + context (thread-local storage) */
static __thread EGLSurface tl_surface = EGL_NO_SURFACE;
static __thread EGLContext  tl_ctx    = EGL_NO_CONTEXT;

/* GL function tracking */
static int    s_last_fbo     = 0;
static int    s_call_count   = 0;
static int    s_read_count   = 0;
static void (*real_glBindFramebuffer)(unsigned int, unsigned int) = NULL;
static void  *libGL_handle   = NULL;

/* Cached GL function pointers (loaded via eglGetProcAddress / dlsym) */
static void *pBlit = NULL;  /* glBlitFramebuffer */
static void *pBF   = NULL;  /* glBindFramebuffer   (unused, tracked version used) */
static void *pF    = NULL;  /* glFinish            */
static void *pGI   = NULL;  /* glGetIntegerv       */
static void *pRP   = NULL;  /* glReadPixels        */
static void *p5    = NULL;  /* glViewport (via eglGetProcAddress in init) */

/* ---- version table for context creation fallback ---- */
typedef struct { int major; int minor; } gl_version_t;
static const gl_version_t version_table[] = {
    { 4, 5 }, { 4, 3 }, { 4, 1 }, { 3, 3 }
};
#define NUM_FALLBACK_VERSIONS (sizeof(version_table)/sizeof(version_table[0]))

/* ---- helper: load a GL function by name ---- */
static void *load_gl(const char *name) {
    void *p = eglGetProcAddress(name);
    if (!p) {
        if (!libGL_handle) {
            libGL_handle = dlopen("libGL.so.1", RTLD_LAZY);
            if (!libGL_handle)
                libGL_handle = dlopen("libGL.so", RTLD_LAZY);
        }
        if (libGL_handle)
            p = dlsym(libGL_handle, name);
    }
    return p;
}

/* ---- helper: build context attrib list ---- */
static void build_ctx_attribs(EGLint *out, int major) {
    out[0] = EGL_CONTEXT_MAJOR_VERSION_KHR;
    out[1] = major;
    out[2] = 0x30FB;  /* EGL_CONTEXT_RELEASE_BEHAVIOR_KHR */
    out[3] = 1;       /* EGL_CONTEXT_RELEASE_BEHAVIOR_FLUSH_KHR */
    out[4] = 0x30FD;  /* EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR */
    out[5] = 1;       /* EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT_KHR */
    out[6] = EGL_NONE;
}

/* ---- helper: try to create a GL context for a given major version ---- */
static EGLContext try_create_context(int major) {
    EGLint attribs[7];
    build_ctx_attribs(attribs, major);
    return eglCreateContext(s_display, s_config, s_context, attribs);
}

/* ==================================================================
 *  hlg_init(major, minor)
 *  Initialize EGL display, choose config, create context + PBuffer.
 *  Returns 1 on success, 0 on failure.
 * ================================================================== */
int hlg_init(int major, int minor) {
    if (s_initialized) return 1;

    s_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (!s_display) return 0;

    if (!eglInitialize(s_display, NULL, NULL)) return 0;

    /* Choose an OpenGL-compatible PBuffer config */
    EGLint num_configs;
    EGLint config_attribs[] = {
        EGL_SURFACE_TYPE,    EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT,
        EGL_RED_SIZE,        8,
        EGL_GREEN_SIZE,      8,
        EGL_BLUE_SIZE,       8,
        EGL_ALPHA_SIZE,      8,
        EGL_DEPTH_SIZE,      24,
        EGL_STENCIL_SIZE,    8,
        EGL_NONE
    };
    if (!eglChooseConfig(s_display, config_attribs, &s_config, 1, &num_configs)
        || num_configs == 0)
        return 0;

    if (!eglBindAPI(EGL_OPENGL_API)) return 0;

    /* Try the requested version first, then fallbacks */
    s_context = try_create_context(major);
    if (s_context) {
        fprintf(stderr, "[hlg] GL %d.%d Core OK (shared ctx)\n", major, minor);
    } else {
        for (unsigned i = 0; i < NUM_FALLBACK_VERSIONS; i++) {
            int fm = version_table[i].major;
            int fn = version_table[i].minor;
            /* Skip if this is the same version we already tried */
            if (i > 0 && fm == major && fn == minor) continue;
            s_context = try_create_context(fm);
            if (s_context) {
                fprintf(stderr, "[hlg] GL %d.%d Core OK (shared ctx)\n", fm, fn);
                break;
            }
        }
    }

    if (!s_context) return 0;

    /* Create PBuffer surface */
    EGLint pbuf_attribs[] = {
        EGL_WIDTH,  s_w,
        EGL_HEIGHT, s_h,
        EGL_NONE
    };
    EGLSurface surface = eglCreatePbufferSurface(s_display, s_config, pbuf_attribs);
    if (!surface) return 0;

    if (!eglMakeCurrent(s_display, surface, surface, s_context)) return 0;

    /* Load glViewport via eglGetProcAddress and set initial viewport */
    typedef void (*glViewport_t)(int, int, int, int);
    glViewport_t gv = (glViewport_t)eglGetProcAddress("glViewport");
    if (gv) gv(0, 0, s_w, s_h);

    fprintf(stderr, "[hlg] init: context made current on init thread %dx%d\n", s_w, s_h);

    s_initialized = 1;
    return 1;
}

/* ==================================================================
 *  hlg_release()
 *  Release the EGL context from the current (init) thread so that
 *  another thread (the emulator thread) can make it current.
 *  Must be called on the init thread AFTER all init-time GL work
 *  (context_reset, retro_load_game, etc.) is done.
 * ================================================================== */
void hlg_release(void) {
    if (s_display != EGL_NO_DISPLAY) {
        eglMakeCurrent(s_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        fprintf(stderr, "[hlg] release: context released from init thread\n");
    }
}

/* ==================================================================
 *  hlg_make_current()
 *  Make the GL context current on the calling thread.
 *  REUSES s_context (the init context) — does NOT create a new one.
 *  Creates a per-thread PBuffer surface if needed.
 *  Returns 1 on success, 0 on failure.
 * ================================================================== */
int hlg_make_current(void) {
    if (!s_initialized) return 0;

    /* Create a per-thread PBuffer surface if this thread doesn't have one */
    if (!tl_surface) {
        EGLint pbuf_attribs[] = {
            EGL_WIDTH,  s_w,
            EGL_HEIGHT, s_h,
            EGL_NONE
        };
        tl_surface = eglCreatePbufferSurface(s_display, s_config, pbuf_attribs);
        if (!tl_surface) {
            fprintf(stderr, "[hlg] make_current: PBuffer creation failed\n");
            return 0;
        }
    }

    /* Make the shared context current on this thread */
    if (!eglMakeCurrent(s_display, tl_surface, tl_surface, s_context)) {
        fprintf(stderr, "[hlg] make_current: eglMakeCurrent failed\n");
        return 0;
    }

    typedef void (*glViewport_t)(int, int, int, int);
    glViewport_t gv = (glViewport_t)eglGetProcAddress("glViewport");
    if (gv) gv(0, 0, s_w, s_h);

    fprintf(stderr, "[hlg] make_current: context+surface %dx%d on thread\n", s_w, s_h);
    return 1;
}

/* ==================================================================
 *  hlg_resize(w, h)
 *  Resize the PBuffer surface for the calling thread.
 *  Returns 1 on success, 0 on failure.
 * ================================================================== */
int hlg_resize(int w, int h) {
    if (!s_initialized) return 0;
    if (w <= 0 || h <= 0) return 0;

    if (w == s_w && h == s_h) return 1;

    fprintf(stderr, "[hlg] resize: %dx%d -> %dx%d\n", s_w, s_h, w, h);
    s_w = w;
    s_h = h;

    if (!tl_surface) return 1;

    /* Release current, destroy old surface, create new one */
    eglMakeCurrent(s_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglDestroySurface(s_display, tl_surface);

    EGLint pbuf_attribs[] = {
        EGL_WIDTH,  s_w,
        EGL_HEIGHT, s_h,
        EGL_NONE
    };
    tl_surface = eglCreatePbufferSurface(s_display, s_config, pbuf_attribs);
    if (!tl_surface) return 1;

    if (!tl_ctx && !s_context) return 1;

    eglMakeCurrent(s_display, tl_surface, tl_surface, s_context);

    typedef void (*glViewport_t)(int, int, int, int);
    glViewport_t gv = (glViewport_t)eglGetProcAddress("glViewport");
    if (gv) gv(0, 0, s_w, s_h);

    return 1;
}

/* ==================================================================
 *  hlg_get_proc_address(sym)
 *  Resolve a GL function by name. Intercepts glBindFramebuffer
 *  to track FBO state. Uses eglGetProcAddress, then dlsym fallback.
 * ================================================================== */
static void tracked_glBindFramebuffer(unsigned int target, unsigned int fbo) {
    if (!real_glBindFramebuffer) {
        real_glBindFramebuffer = (void (*)(unsigned int, unsigned int))
            eglGetProcAddress("glBindFramebuffer");
    }
    if (real_glBindFramebuffer)
        real_glBindFramebuffer(target, fbo);

    /* Track FBOs for readback: GL_DRAW_FRAMEBUFFER=0x8CA9,
       GL_READ_FRAMEBUFFER=0x8CA8, GL_FRAMEBUFFER=0x8D40 */
    if ((target == 0x8CA9) || (target == 0x8CA8) || (target == 0x8D40)) {
        s_last_fbo = (int)fbo;
    }
}

void *hlg_get_proc_address(const char *sym) {
    int count = ++s_call_count;

    if (count <= 20) {
        fprintf(stderr, "[hlg] get_proc_address(\"%s\") call #%d\n",
                sym ? sym : "NULL", count);
    }

    if (!sym) return NULL;

    if (strcmp(sym, "glBindFramebuffer") == 0) {
        if (!real_glBindFramebuffer) {
            real_glBindFramebuffer = (void (*)(unsigned int, unsigned int))
                eglGetProcAddress("glBindFramebuffer");
        }
        return (void *)tracked_glBindFramebuffer;
    }

    void *p = eglGetProcAddress(sym);
    if (p) return p;

    return load_gl(sym);
}

/* ==================================================================
 *  hlg_get_framebuffer()
 *  Returns 0 — the frontend's "current framebuffer" is the default
 *  FBO (0) since we use a PBuffer, not an FBO-backed surface.
 * ================================================================== */
unsigned long hlg_get_framebuffer(void) {
    return 0;
}

/* Return function pointers (for JNA to read from C memory) */
void *hlg_get_framebuffer_ptr(void)   { return (void *)hlg_get_framebuffer; }
void *hlg_get_proc_address_ptr(void)  { return (void *)hlg_get_proc_address; }

/* ==================================================================
 *  hlg_debug_fbo()
 *  Print current GL FBO state for debugging.
 * ================================================================== */
void hlg_debug_fbo(void) {
    if (!pGI)  pGI  = load_gl("glGetIntegerv");
    if (!pRP)  pRP  = load_gl("glReadPixels");
    if (!pF)   pF   = load_gl("glFinish");
    if (!pBlit) pBlit = load_gl("glBlitFramebuffer");

    if (!pGI) return;

    typedef void (*glGetIntegerv_t)(unsigned int, int *);
    typedef void (*glFinish_t)(void);
    typedef void (*glReadPixels_t)(int, int, int, int, unsigned int, unsigned int, void *);

    int readFbo = 0, drawFbo = 0;
    int vp[4] = {0, 0, 0, 0};

    ((void (*)(unsigned int, int *))pGI)(0x8CA8, &readFbo);  /* GL_READ_FRAMEBUFFER_BINDING */
    ((void (*)(unsigned int, int *))pGI)(0x8CA9, &drawFbo);  /* GL_DRAW_FRAMEBUFFER_BINDING */
    ((void (*)(unsigned int, int *))pGI)(0x0BA2, vp);         /* GL_VIEWPORT */

    fprintf(stderr, "[hlg] video_cb #%d: readFbo=%d drawFbo=%d vp=%d,%d,%d,%d "
            "lastFbo=%d pbuf=%dx%d\n",
            ++s_read_count, readFbo, drawFbo,
            vp[0], vp[1], vp[2], vp[3],
            s_last_fbo, s_w, s_h);
}

/* ==================================================================
 *  hlg_read_pixels(viewport_out, pixels_out)
 *  Read the current GL framebuffer into a CPU buffer.
 *  viewport_out: int[4] receives the GL viewport.
 *  pixels_out:   pre-allocated buffer of at least width*height*4 bytes.
 * ================================================================== */
void hlg_read_pixels(int *viewport_out, void *pixels_out) {
    if (!pGI)  pGI  = load_gl("glGetIntegerv");
    if (!pRP)  pRP  = load_gl("glReadPixels");
    if (!pF)   pF   = load_gl("glFinish");

    typedef void (*glGetIntegerv_t)(unsigned int, int *);
    typedef void (*glFinish_t)(void);
    typedef void (*glReadPixels_t)(int, int, int, int, unsigned int, unsigned int, void *);

    int vp[4] = {0};
    ((void (*)(unsigned int, int *))pGI)(0x0BA2, vp);  /* GL_VIEWPORT */

    fprintf(stderr, "[hlg] read_pixels: lastFbo=%d vp=%dx%d\n",
            s_last_fbo, vp[2], vp[3]);

    viewport_out[0] = vp[0];
    viewport_out[1] = vp[1];
    viewport_out[2] = vp[2];
    viewport_out[3] = vp[3];

    if (pF) ((void (*)(void))pF)();

    if (pRP && vp[2] > 0 && vp[3] > 0) {
        ((void (*)(int, int, int, int, unsigned int, unsigned int, void *))
         pRP)(0, 0, vp[2], vp[3], 0x1908 /* GL_RGBA */, 0x1401 /* GL_UNSIGNED_BYTE */, pixels_out);

        /* Count non-zero pixels for debug */
        int total = vp[2] * vp[3];
        int nonzero = 0;
        unsigned int *px = (unsigned int *)pixels_out;
        for (int i = 0; i < total; i++) {
            if (px[i] & 0x00FFFFFF) nonzero++;
        }
        fprintf(stderr, "[hlg] readback: %d/%d non-zero pixels\n", nonzero, total);
    }
}

/* ==================================================================
 *  hlg_dump_hw_render(data, size)
 *  Hex dump the retro_hw_render_callback struct for debugging.
 * ================================================================== */
void hlg_dump_hw_render(const void *data, int size) {
    fprintf(stderr, "[hlg] === HW RENDER DUMP (%d bytes) ===\n", size);
    const unsigned char *p = (const unsigned char *)data;
    for (int i = 0; i < size; i += 8) {
        fprintf(stderr, "  +%2d: ", i);
        for (int j = 0; j < 8 && (i+j) < size; j++)
            fprintf(stderr, "%02x ", p[i+j]);
        fprintf(stderr, " [%u]", *(unsigned int *)(p + i));
        fprintf(stderr, "\n");
    }
    fprintf(stderr, "[hlg] === END ===\n");
}

/* ==================================================================
 *  hlg_destroy()
 *  Clean up all EGL resources. Safe to call multiple times.
 * ================================================================== */
void hlg_destroy(void) {
    if (tl_surface) {
        eglMakeCurrent(s_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroySurface(s_display, tl_surface);
        tl_surface = EGL_NO_SURFACE;
    }
    if (s_context) {
        eglDestroyContext(s_display, s_context);
        s_context = EGL_NO_CONTEXT;
    }
    if (s_display) {
        eglTerminate(s_display);
        s_display = EGL_NO_DISPLAY;
    }
    s_initialized = 0;
}
