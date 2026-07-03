/*
 * headless_gl.c — Headless EGL/Mesa software rendering context for libretro.
 * Provides a PBuffer-backed OpenGL 3.1 Core context via Mesa's llvmpipe.
 * Used by the RetroConsole libretro bridge to supply HW render contexts
 * to cores like Flycast that request OpenGL Core for hardware rendering.
 *
 * Build:
 *   gcc -shared -fPIC -O2 -o .libheadless_gl.so headless_gl.c -lEGL -lGL -ldl -lpthread
 *   strip .libheadless_gl.so
 */

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GL/gl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <dlfcn.h>
#include <pthread.h>

/* ---- globals ---- */
static EGLDisplay  s_display   = EGL_NO_DISPLAY;
static EGLConfig   s_config;
static EGLContext   s_context   = EGL_NO_CONTEXT;  /* init-thread context */
static int         s_initialized = 0;
static int         s_gl_major    = 3;
static int         s_gl_minor    = 1;
static int         s_api_mode    = 0;  /* 0 = EGL_OPENGL_API, 1 = EGL_OPENGL_ES_API */
static int         s_compat_profile = 0;
static int         s_w = 640, s_h = 480;
static pthread_mutex_t s_egl_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t s_gl_mutex;
static int         s_gl_mutex_ready = 0;
static int         s_make_current_logs = 0;
static EGLSurface  s_shared_surface = EGL_NO_SURFACE;
static int         s_gles_serial = 0;  /* PPSSPP: one context + mutex */

#define CTX_POOL_SIZE 32
static EGLContext  s_ctx_pool[CTX_POOL_SIZE];
static int         s_ctx_pool_size = 0;
static int         s_next_pool_slot = 0;

/* Per-thread surface + context (thread-local storage) */
static __thread EGLSurface tl_surface = EGL_NO_SURFACE;
static __thread EGLContext  tl_ctx    = EGL_NO_CONTEXT;

/* GL function tracking */
static int    s_last_fbo     = 0;
static int    s_call_count   = 0;
static int    s_read_count   = 0;
static int    s_gf_call_count = 0;
static void (*real_glBindFramebuffer)(unsigned int, unsigned int) = NULL;
static void  *libGL_handle   = NULL;

static void build_ctx_attribs(EGLint *out, int major, int minor);
static void build_es_ctx_attribs(EGLint *out, int version);
static void load_fbo_procs(void);
static void recreate_shared_fbo(int w, int h);

static void build_es_ctx_attribs(EGLint *out, int version) {
    out[0] = EGL_CONTEXT_CLIENT_VERSION;
    out[1] = version;
    out[2] = EGL_NONE;
}

static void fill_ctx_attribs(EGLint *out, int major, int minor) {
    if (s_api_mode == 1)
        build_es_ctx_attribs(out, major);
    else
        build_ctx_attribs(out, major, minor);
}

static void ensure_gl_mutex(void) {
    if (s_gl_mutex_ready) return;
    pthread_mutexattr_t attr;
    pthread_mutexattr_init(&attr);
    pthread_mutexattr_settype(&attr, PTHREAD_MUTEX_RECURSIVE);
    pthread_mutex_init(&s_gl_mutex, &attr);
    pthread_mutexattr_destroy(&attr);
    s_gl_mutex_ready = 1;
}

static EGLContext create_gl_context(EGLContext share, int major, int minor);
void hlg_destroy(void);
static int recreate_shared_surface(void);
static int ensure_gl_current(void);

static void destroy_ctx_pool(void) {
    for (int i = 0; i < s_ctx_pool_size; i++) {
        if (s_ctx_pool[i] != EGL_NO_CONTEXT && s_ctx_pool[i] != s_context) {
            eglDestroyContext(s_display, s_ctx_pool[i]);
        }
        s_ctx_pool[i] = EGL_NO_CONTEXT;
    }
    s_ctx_pool_size = 0;
    s_next_pool_slot = 0;
}

static void init_ctx_pool(void) {
    destroy_ctx_pool();
    /* GLES serial: one master context + mutex (GLES cores resolve via
     * get_proc_address, so wrappers can serialize access). Desktop GL
     * compat cores (PPSSPP/PCSX2) use GLEW/dlsym and bypass our
     * get_proc_address, so wrappers can't serialize — they need a real
     * context pool + a shared FBO (see s_shared_fbo). */
    if (s_api_mode == 1) {
        s_gles_serial = 1;
        fprintf(stderr, "[hlg] GLES: single shared context + mutex (no pool)\n");
        return;
    }
    s_gles_serial = 0;
    for (int i = 0; i < CTX_POOL_SIZE; i++) {
        EGLContext ctx = create_gl_context(s_context, s_gl_major, s_gl_minor);
        if (!ctx) break;
        s_ctx_pool[s_ctx_pool_size++] = ctx;
    }
    fprintf(stderr, "[hlg] shared context pool: %d/%d (%s)\n",
            s_ctx_pool_size, CTX_POOL_SIZE,
            s_compat_profile ? "GL Compat" : "GL");
}

static const char *egl_err_str(EGLint err) {
    switch (err) {
        case EGL_SUCCESS: return "SUCCESS";
        case EGL_BAD_MATCH: return "BAD_MATCH";
        case EGL_BAD_ALLOC: return "BAD_ALLOC";
        case EGL_BAD_CONTEXT: return "BAD_CONTEXT";
        case EGL_BAD_CONFIG: return "BAD_CONFIG";
        case EGL_BAD_ATTRIBUTE: return "BAD_ATTRIBUTE";
        case EGL_BAD_ACCESS: return "BAD_ACCESS";
        default: return "OTHER";
    }
}

static EGLContext create_gl_context(EGLContext share, int major, int minor) {
    EGLContext ctx = EGL_NO_CONTEXT;

    /* Shared contexts must inherit attribs from parent — explicit attribs often fail. */
    if (share != EGL_NO_CONTEXT) {
        EGLint inherit[] = { EGL_NONE };
        ctx = eglCreateContext(s_display, s_config, share, inherit);
        if (ctx) return ctx;
        EGLint err = eglGetError();
        fprintf(stderr, "[hlg] eglCreateContext(share=%p, inherit) failed: 0x%x %s\n",
                (void *)share, err, egl_err_str(err));
    }

    EGLint attribs[8];
    fill_ctx_attribs(attribs, major, minor);
    ctx = eglCreateContext(s_display, s_config, share, attribs);
    if (!ctx) {
        EGLint err = eglGetError();
        fprintf(stderr, "[hlg] eglCreateContext(share=%p, %d.%d) failed: 0x%x %s\n",
                (void *)share, major, minor, err, egl_err_str(err));
    }
    return ctx;
}

static int s_ctx_create_fail_logs = 0;

static int s_ensure_gl_fail_logs = 0;
static pthread_t s_gl_ctx_thread = 0;

static void release_gl_current(void) {
    if (eglGetCurrentContext() == EGL_NO_CONTEXT) return;
    eglMakeCurrent(s_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (s_gl_ctx_thread != 0 && pthread_equal(s_gl_ctx_thread, pthread_self()))
        s_gl_ctx_thread = 0;
}

static int ensure_gl_current(void) {
    if (!s_initialized || s_shared_surface == EGL_NO_SURFACE || s_context == EGL_NO_CONTEXT)
        return 0;

    if (eglGetCurrentContext() == s_context &&
        eglGetCurrentSurface(EGL_DRAW) == s_shared_surface)
        return 1;

    /* Detach this thread from any stale binding, then bind the shared context. */
    eglMakeCurrent(s_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    eglReleaseThread();

    if (eglMakeCurrent(s_display, s_shared_surface, s_shared_surface, s_context)) {
        s_gl_ctx_thread = pthread_self();
        return 1;
    }

    EGLint err = eglGetError();
    if (s_ensure_gl_fail_logs < 20) {
        fprintf(stderr, "[hlg] ensure_gl_current failed: 0x%x %s\n", err, egl_err_str(err));
        s_ensure_gl_fail_logs++;
    }
    return 0;
}

static int recreate_shared_surface(void) {
    if (s_shared_surface != EGL_NO_SURFACE) {
        eglDestroySurface(s_display, s_shared_surface);
        s_shared_surface = EGL_NO_SURFACE;
    }
    EGLint pbuf_attribs[] = { EGL_WIDTH, s_w, EGL_HEIGHT, s_h, EGL_NONE };
    s_shared_surface = eglCreatePbufferSurface(s_display, s_config, pbuf_attribs);
    if (s_shared_surface == EGL_NO_SURFACE) {
        fprintf(stderr, "[hlg] eglCreatePbufferSurface FAILED err=0x%x (driver may not support pbuffer — try surfaceless)\n",
                eglGetError());
        return 0;
    }
    return 1;
}

static int ensure_thread_gl(void) {
    if (!s_initialized) return 0;

    if (s_gles_serial) {
        pthread_mutex_lock(&s_gl_mutex);
        pthread_mutex_lock(&s_egl_mutex);
        tl_ctx = s_context;
        int ok = ensure_gl_current();
        if (ok && s_make_current_logs < 4) {
            fprintf(stderr, "[hlg] ensure_thread_gl: serial ctx %dx%d\n", s_w, s_h);
            s_make_current_logs++;
        }
        pthread_mutex_unlock(&s_egl_mutex);
        pthread_mutex_unlock(&s_gl_mutex);
        return ok;
    }

    pthread_mutex_lock(&s_egl_mutex);

    if (!tl_surface) {
        EGLint pbuf_attribs[] = { EGL_WIDTH, s_w, EGL_HEIGHT, s_h, EGL_NONE };
        tl_surface = eglCreatePbufferSurface(s_display, s_config, pbuf_attribs);
        if (!tl_surface) {
            fprintf(stderr, "[hlg] ensure_thread_gl: PBuffer creation failed\n");
            pthread_mutex_unlock(&s_egl_mutex);
            return 0;
        }
    }

    if (!tl_ctx) {
        eglMakeCurrent(s_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (s_ctx_pool_size > 0 && s_next_pool_slot < s_ctx_pool_size) {
            tl_ctx = s_ctx_pool[s_next_pool_slot++];
        } else {
            tl_ctx = create_gl_context(s_context, s_gl_major, s_gl_minor);
        }
        if (!tl_ctx) {
            if (s_ctx_create_fail_logs < 4) {
                fprintf(stderr, "[hlg] ensure_thread_gl: reusing master context on this thread\n");
                s_ctx_create_fail_logs++;
            }
            tl_ctx = s_context;
        }
        if (!tl_ctx) {
            fprintf(stderr, "[hlg] ensure_thread_gl: failed to create any context\n");
            pthread_mutex_unlock(&s_egl_mutex);
            return 0;
        }
    }

    if (!eglMakeCurrent(s_display, tl_surface, tl_surface, tl_ctx)) {
        EGLint err = eglGetError();
        fprintf(stderr, "[hlg] ensure_thread_gl: eglMakeCurrent failed: 0x%x %s\n",
                err, egl_err_str(err));
        pthread_mutex_unlock(&s_egl_mutex);
        return 0;
    }

    typedef void (*glViewport_t)(int, int, int, int);
    glViewport_t gv = (glViewport_t)eglGetProcAddress("glViewport");
    if (gv) gv(0, 0, s_w, s_h);

    if (s_make_current_logs < 8) {
        fprintf(stderr, "[hlg] ensure_thread_gl: ctx+surface %dx%d (shared=%d)\n",
                s_w, s_h, tl_ctx != s_context);
        s_make_current_logs++;
    }

    pthread_mutex_unlock(&s_egl_mutex);
    return 1;
}

/* Cached GL function pointers (loaded via eglGetProcAddress / dlsym) */
static void *pBlit = NULL;  /* glBlitFramebuffer */
static void *pF    = NULL;  /* glFinish            */
static void *pGI   = NULL;  /* glGetIntegerv       */
static void *pRP   = NULL;  /* glReadPixels        */

/* Shared render FBO — what get_current_framebuffer returns to the core.
 * Created in the shared object namespace, so every pooled context sees it.
 * Cores render here; readback binds it and reads. This mirrors RetroArch. */
static unsigned int s_shared_fbo = 0;
static unsigned int s_shared_fbo_tex = 0;
static int s_fbo_w = 0, s_fbo_h = 0;

/* Raw GL entry points for FBO management (loaded once, called under s_egl_mutex). */
static void (*pGenFramebuffers)(int, unsigned int *);
static void (*pBindFramebuffer)(unsigned int, unsigned int);
static void (*pGenTextures)(int, unsigned int *);
static void (*pBindTexture)(unsigned int, unsigned int);
static void (*pTexImage2D)(unsigned int, int, int, int, int, int, unsigned int, unsigned int, const void *);
static void (*pTexParameteri)(unsigned int, unsigned int, int);
static void (*pFramebufferTexture2D)(unsigned int, unsigned int, unsigned int, unsigned int, int);
static void (*pDeleteFramebuffers)(int, const unsigned int *);
static void (*pDeleteTextures)(int, const unsigned int *);
static unsigned int (*pCheckFramebufferStatus)(unsigned int);

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
            if (s_api_mode == 1) {
                libGL_handle = dlopen("libGLESv2.so.2", RTLD_LAZY);
                if (!libGL_handle)
                    libGL_handle = dlopen("libGLESv2.so", RTLD_LAZY);
            }
            if (!libGL_handle)
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
static void build_ctx_attribs(EGLint *out, int major, int minor) {
    out[0] = EGL_CONTEXT_MAJOR_VERSION_KHR;
    out[1] = major;
    out[2] = EGL_CONTEXT_MINOR_VERSION_KHR;
    out[3] = minor;
    out[4] = 0x30FD;  /* EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR */
    out[5] = s_compat_profile ? 2 : 1;  /* COMPAT vs CORE */
    out[6] = EGL_NONE;
}

/* ---- helper: try to create a GL context for a given major version ---- */
static EGLContext try_create_context(EGLContext share, int major, int minor) {
    return create_gl_context(share, major, minor);
}

/* ==================================================================
 *  hlg_init(major, minor)
 *  Initialize EGL display, choose config, create context + PBuffer.
 *  Returns 1 on success, 0 on failure.
 * ================================================================== */
/* ==================================================================
 *  hlg_init_ex(api, major, minor, flags)
 *  api: 0 = desktop OpenGL, 1 = OpenGL ES
 *  flags: bit0 = compatibility profile (PPSSPP/GLEW)
 * ================================================================== */
int hlg_init_ex(int api, int major, int minor, int flags) {
    int compat = flags & 1;
    if (s_initialized) {
        if (s_api_mode == api && s_gl_major == major && s_gl_minor == minor
            && s_compat_profile == compat)
            return 1;
        hlg_destroy();
    }

    s_api_mode = api ? 1 : 0;
    s_compat_profile = compat;
    s_gl_major = major;
    s_gl_minor = minor;
    ensure_gl_mutex();

    if (s_api_mode == 1) {
        setenv("MESA_GLTHREAD", "false", 0);
        setenv("MESA_GLES_VERSION_OVERRIDE", "3.0", 0);
    }

    /* GL driver selection via HLG_GL_DRIVER env:
     *   d3d12   (default) - mesa d3d12/dzn, real GPU via /dev/dxg (WSL2)
     *   zink             - GL->Vulkan->D3D12
     *   llvmpipe         - software (slow, but always works)
     *   auto             - let mesa pick
     */
    const char *drv = getenv("HLG_GL_DRIVER");
    if (drv == NULL) drv = "d3d12";
    if (strcmp(drv, "llvmpipe") == 0) {
        setenv("LIBGL_ALWAYS_SOFTWARE", "1", 1);
        unsetenv("GALLIUM_DRIVER");
    } else if (strcmp(drv, "zink") == 0) {
        unsetenv("LIBGL_ALWAYS_SOFTWARE");
        setenv("GALLIUM_DRIVER", "zink", 1);
    } else if (strcmp(drv, "auto") == 0) {
        unsetenv("LIBGL_ALWAYS_SOFTWARE");
        unsetenv("GALLIUM_DRIVER");
    } else {
        /* d3d12 */
        unsetenv("LIBGL_ALWAYS_SOFTWARE");
        setenv("GALLIUM_DRIVER", "d3d12", 1);
        setenv("LIBGL_DRI3_DISABLE", "1", 0);
    }
    fprintf(stderr, "[hlg] GL driver: %s\n", drv);

    s_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (!s_display) {
        fprintf(stderr, "[hlg] eglGetDisplay FAILED (driver=%s)\n", drv);
        return 0;
    }
    if (!eglInitialize(s_display, NULL, NULL)) {
        fprintf(stderr, "[hlg] eglInitialize FAILED (driver=%s) err=0x%x\n",
                drv, eglGetError());
        return 0;
    }

    EGLint num_configs;
    EGLint config_attribs_gl[] = {
        EGL_SURFACE_TYPE,    EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 24, EGL_STENCIL_SIZE, 8,
        EGL_NONE
    };
    EGLint config_attribs_es[] = {
        EGL_SURFACE_TYPE,    EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 24, EGL_STENCIL_SIZE, 8,
        EGL_NONE
    };

    if (s_api_mode == 1) {
        if (!eglBindAPI(EGL_OPENGL_ES_API)) return 0;
        if (!eglChooseConfig(s_display, config_attribs_es, &s_config, 1, &num_configs)
            || num_configs == 0) {
            config_attribs_es[3] = EGL_OPENGL_ES2_BIT;
            if (!eglChooseConfig(s_display, config_attribs_es, &s_config, 1, &num_configs)
                || num_configs == 0)
                return 0;
        }
    } else {
        if (!eglBindAPI(EGL_OPENGL_API)) {
            fprintf(stderr, "[hlg] eglBindAPI(OPENGL) FAILED err=0x%x\n", eglGetError());
            return 0;
        }
        if (!eglChooseConfig(s_display, config_attribs_gl, &s_config, 1, &num_configs)
            || num_configs == 0) {
            fprintf(stderr, "[hlg] eglChooseConfig(GL pbuffer) FAILED err=0x%x num=%d\n",
                    eglGetError(), num_configs);
            return 0;
        }
    }

    s_context = try_create_context(EGL_NO_CONTEXT, major, minor);
    if (!s_context && s_api_mode == 1) {
        s_gl_major = 2;
        s_context = try_create_context(EGL_NO_CONTEXT, 2, 0);
    }
    if (!s_context && s_api_mode == 0) {
        for (unsigned i = 0; i < NUM_FALLBACK_VERSIONS; i++) {
            int fm = version_table[i].major;
            int fn = version_table[i].minor;
            s_gl_major = fm;
            s_gl_minor = fn;
            s_context = try_create_context(EGL_NO_CONTEXT, fm, fn);
            if (s_context) break;
        }
    }
    if (!s_context) return 0;

    fprintf(stderr, "[hlg] %s %d.%d %s OK (master ctx)\n",
            s_api_mode == 1 ? "GLES" : "GL",
            s_gl_major, s_gl_minor,
            s_compat_profile ? "Compat" : "Core");

    if (!recreate_shared_surface()) return 0;
    if (!eglMakeCurrent(s_display, s_shared_surface, s_shared_surface, s_context)) return 0;

    typedef void (*glViewport_t)(int, int, int, int);
    glViewport_t gv = (glViewport_t)eglGetProcAddress("glViewport");
    if (gv) gv(0, 0, s_w, s_h);

    fprintf(stderr, "[hlg] init: context made current on init thread %dx%d\n", s_w, s_h);

    init_ctx_pool();

    /* Shared render FBO lives in the shared object namespace. */
    if (!s_gles_serial) {
        load_fbo_procs();
        recreate_shared_fbo(s_w, s_h);
    }

    s_initialized = 1;
    return 1;
}

int hlg_init(int major, int minor) {
    return hlg_init_ex(0, major, minor, 0);
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
        if (s_gles_serial) pthread_mutex_lock(&s_gl_mutex);
        pthread_mutex_lock(&s_egl_mutex);
        release_gl_current();
        eglReleaseThread();
        pthread_mutex_unlock(&s_egl_mutex);
        if (s_gles_serial) pthread_mutex_unlock(&s_gl_mutex);
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
    return ensure_thread_gl();
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

    if (s_gles_serial) pthread_mutex_lock(&s_gl_mutex);
    pthread_mutex_lock(&s_egl_mutex);
    release_gl_current();
    eglReleaseThread();

    if (s_gles_serial) {
        if (!recreate_shared_surface()) {
            pthread_mutex_unlock(&s_egl_mutex);
            if (s_gles_serial) pthread_mutex_unlock(&s_gl_mutex);
            return 0;
        }
    } else if (tl_surface) {
        eglDestroySurface(s_display, tl_surface);
        EGLint pbuf_attribs[] = { EGL_WIDTH, s_w, EGL_HEIGHT, s_h, EGL_NONE };
        tl_surface = eglCreatePbufferSurface(s_display, s_config, pbuf_attribs);
        if (tl_surface) {
            EGLContext ctx = tl_ctx ? tl_ctx : s_context;
            eglMakeCurrent(s_display, tl_surface, tl_surface, ctx);
        }
    }
    /* Recreate the shared render FBO at the new size (needs a current ctx). */
    if (!s_gles_serial && s_shared_fbo) {
        if (s_w != s_fbo_w || s_h != s_fbo_h)
            recreate_shared_fbo(s_w, s_h);
    }
    pthread_mutex_unlock(&s_egl_mutex);
    if (s_gles_serial) pthread_mutex_unlock(&s_gl_mutex);

    typedef void (*glViewport_t)(int, int, int, int);
    glViewport_t gv = (glViewport_t)eglGetProcAddress("glViewport");
    if (gv) gv(0, 0, s_w, s_h);

    return 1;
}

/* ---- GLES mutex wrappers (PPSSPP multi-thread GL) ---- */

static int gl_use_wrappers(void) {
    /* Only GLES serial mode needs mutex wrappers. Desktop GL compat cores
     * resolve GL via GLEW/dlsym (bypassing get_proc_address), so wrappers
     * can't intercept their calls — give them real functions + shared FBO. */
    return s_gles_serial;
}

static int wgl_begin(void) {
    pthread_mutex_lock(&s_gl_mutex);
    if (s_gles_serial) {
        pthread_mutex_lock(&s_egl_mutex);
        if (!ensure_gl_current()) {
            pthread_mutex_unlock(&s_egl_mutex);
            pthread_mutex_unlock(&s_gl_mutex);
            return 0;
        }
    } else if (!ensure_thread_gl()) {
        pthread_mutex_unlock(&s_gl_mutex);
        return 0;
    }
    return 1;
}

static void wgl_end(void) {
    if (s_gles_serial) {
        /* Keep context current on this thread — avoids BAD_ACCESS from
         * release/makeCurrent storms across PPSSPP worker threads. */
        pthread_mutex_unlock(&s_egl_mutex);
    }
    pthread_mutex_unlock(&s_gl_mutex);
}

/* glGetString needs a TLS copy — pointer invalid after unlock */
static const GLubyte *(*real_glGetString)(GLenum);
static const GLubyte *wrap_glGetString(GLenum name) {
    static __thread char tls_str[512];
    if (!wgl_begin()) return NULL;
    const GLubyte *r = real_glGetString ? real_glGetString(name) : NULL;
    if (r) {
        snprintf(tls_str, sizeof(tls_str), "%s", (const char *)r);
        r = (const GLubyte *)tls_str;
    }
    wgl_end();
    return r;
}

static GLenum (*real_glGetError)(void);
static GLenum wrap_glGetError(void) {
    if (!wgl_begin()) return 0;
    GLenum r = real_glGetError ? real_glGetError() : 0;
    wgl_end();
    return r;
}

static void (*real_glGetIntegerv)(GLenum, GLint *);
static void wrap_glGetIntegerv(GLenum pname, GLint *data) {
    if (!wgl_begin()) return;
    if (real_glGetIntegerv) real_glGetIntegerv(pname, data);
    wgl_end();
}

static void (*real_glViewport)(GLint, GLint, GLsizei, GLsizei);
static void wrap_glViewport(GLint x, GLint y, GLsizei w, GLsizei h) {
    if (!wgl_begin()) return;
    if (real_glViewport) real_glViewport(x, y, w, h);
    wgl_end();
}

static void (*real_glClear)(GLenum);
static void wrap_glClear(GLenum mask) {
    if (!wgl_begin()) return;
    if (real_glClear) real_glClear(mask);
    wgl_end();
}

static void (*real_glClearColor)(GLfloat, GLfloat, GLfloat, GLfloat);
static void wrap_glClearColor(GLfloat r, GLfloat g, GLfloat b, GLfloat a) {
    if (!wgl_begin()) return;
    if (real_glClearColor) real_glClearColor(r, g, b, a);
    wgl_end();
}

static void (*real_glEnable)(GLenum);
static void wrap_glEnable(GLenum cap) {
    if (!wgl_begin()) return;
    if (real_glEnable) real_glEnable(cap);
    wgl_end();
}

static void (*real_glDisable)(GLenum);
static void wrap_glDisable(GLenum cap) {
    if (!wgl_begin()) return;
    if (real_glDisable) real_glDisable(cap);
    wgl_end();
}

static void (*real_glGenTextures)(GLsizei, GLuint *);
static void wrap_glGenTextures(GLsizei n, GLuint *ids) {
    if (!wgl_begin()) return;
    if (real_glGenTextures) real_glGenTextures(n, ids);
    wgl_end();
}

static void (*real_glBindTexture)(GLenum, GLuint);
static void wrap_glBindTexture(GLenum target, GLuint tex) {
    if (!wgl_begin()) return;
    if (real_glBindTexture) real_glBindTexture(target, tex);
    wgl_end();
}

static void (*real_glTexParameteri)(GLenum, GLenum, GLint);
static void wrap_glTexParameteri(GLenum target, GLenum pname, GLint param) {
    if (!wgl_begin()) return;
    if (real_glTexParameteri) real_glTexParameteri(target, pname, param);
    wgl_end();
}

static GLuint (*real_glCreateShader)(GLenum);
static GLuint wrap_glCreateShader(GLenum type) {
    if (!wgl_begin()) return 0;
    GLuint r = real_glCreateShader ? real_glCreateShader(type) : 0;
    wgl_end();
    return r;
}

static GLuint (*real_glCreateProgram)(void);
static GLuint wrap_glCreateProgram(void) {
    if (!wgl_begin()) return 0;
    GLuint r = real_glCreateProgram ? real_glCreateProgram() : 0;
    wgl_end();
    return r;
}

static void (*real_glShaderSource)(GLuint, GLsizei, const GLchar *const *, const GLint *);
static void wrap_glShaderSource(GLuint shader, GLsizei count, const GLchar *const *src, const GLint *len) {
    if (!wgl_begin()) return;
    if (real_glShaderSource) real_glShaderSource(shader, count, src, len);
    wgl_end();
}

static void (*real_glCompileShader)(GLuint);
static void wrap_glCompileShader(GLuint shader) {
    if (!wgl_begin()) return;
    if (real_glCompileShader) real_glCompileShader(shader);
    wgl_end();
}

static void (*real_glAttachShader)(GLuint, GLuint);
static void wrap_glAttachShader(GLuint prog, GLuint shader) {
    if (!wgl_begin()) return;
    if (real_glAttachShader) real_glAttachShader(prog, shader);
    wgl_end();
}

static void (*real_glLinkProgram)(GLuint);
static void wrap_glLinkProgram(GLuint prog) {
    if (!wgl_begin()) return;
    if (real_glLinkProgram) real_glLinkProgram(prog);
    wgl_end();
}

static void (*real_glUseProgram)(GLuint);
static void wrap_glUseProgram(GLuint prog) {
    if (!wgl_begin()) return;
    if (real_glUseProgram) real_glUseProgram(prog);
    wgl_end();
}

static void (*real_glGetShaderiv)(GLuint, GLenum, GLint *);
static void wrap_glGetShaderiv(GLuint shader, GLenum pname, GLint *params) {
    if (!wgl_begin()) return;
    if (real_glGetShaderiv) real_glGetShaderiv(shader, pname, params);
    wgl_end();
}

static void (*real_glGetProgramiv)(GLuint, GLenum, GLint *);
static void wrap_glGetProgramiv(GLuint prog, GLenum pname, GLint *params) {
    if (!wgl_begin()) return;
    if (real_glGetProgramiv) real_glGetProgramiv(prog, pname, params);
    wgl_end();
}

static void (*real_glGenBuffers)(GLsizei, GLuint *);
static void wrap_glGenBuffers(GLsizei n, GLuint *ids) {
    if (!wgl_begin()) return;
    if (real_glGenBuffers) real_glGenBuffers(n, ids);
    wgl_end();
}

static void (*real_glBindBuffer)(GLenum, GLuint);
static void wrap_glBindBuffer(GLenum target, GLuint buf) {
    if (!wgl_begin()) return;
    if (real_glBindBuffer) real_glBindBuffer(target, buf);
    wgl_end();
}

static void (*real_glBufferData)(GLenum, GLsizeiptr, const void *, GLenum);
static void wrap_glBufferData(GLenum target, GLsizeiptr size, const void *data, GLenum usage) {
    if (!wgl_begin()) return;
    if (real_glBufferData) real_glBufferData(target, size, data, usage);
    wgl_end();
}

static void (*real_glGenVertexArrays)(GLsizei, GLuint *);
static void wrap_glGenVertexArrays(GLsizei n, GLuint *ids) {
    if (!wgl_begin()) return;
    if (real_glGenVertexArrays) real_glGenVertexArrays(n, ids);
    wgl_end();
}

static void (*real_glBindVertexArray)(GLuint);
static void wrap_glBindVertexArray(GLuint arr) {
    if (!wgl_begin()) return;
    if (real_glBindVertexArray) real_glBindVertexArray(arr);
    wgl_end();
}

static void (*real_glDrawArrays)(GLenum, GLint, GLsizei);
static void wrap_glDrawArrays(GLenum mode, GLint first, GLsizei count) {
    if (!wgl_begin()) return;
    if (real_glDrawArrays) real_glDrawArrays(mode, first, count);
    wgl_end();
}

static void (*real_glDrawElements)(GLenum, GLsizei, GLenum, const void *);
static void wrap_glDrawElements(GLenum mode, GLsizei count, GLenum type, const void *indices) {
    if (!wgl_begin()) return;
    if (real_glDrawElements) real_glDrawElements(mode, count, type, indices);
    wgl_end();
}

static void (*real_glFlush)(void);
static void wrap_glFlush(void) {
    if (!wgl_begin()) return;
    if (real_glFlush) real_glFlush();
    wgl_end();
}

static void (*real_glFinish)(void);
static void wrap_glFinish(void) {
    if (!wgl_begin()) return;
    if (real_glFinish) real_glFinish();
    wgl_end();
}

static void (*real_glGenFramebuffers)(GLsizei, GLuint *);
static void wrap_glGenFramebuffers(GLsizei n, GLuint *ids) {
    if (!wgl_begin()) return;
    if (real_glGenFramebuffers) real_glGenFramebuffers(n, ids);
    wgl_end();
}

static void (*real_glFramebufferTexture2D)(GLenum, GLenum, GLenum, GLuint, GLint);
static void wrap_glFramebufferTexture2D(GLenum target, GLenum attachment, GLenum textarget, GLuint texture, GLint level) {
    if (!wgl_begin()) return;
    if (real_glFramebufferTexture2D) real_glFramebufferTexture2D(target, attachment, textarget, texture, level);
    if (attachment == 0x8CE0 /* GL_COLOR_ATTACHMENT0 */ &&
            (target == 0x8D40 /* GL_FRAMEBUFFER */ || target == 0x8CA9 /* GL_DRAW_FRAMEBUFFER */)) {
        if (!real_glGetIntegerv)
            real_glGetIntegerv = (void (*)(GLenum, GLint *))load_gl("glGetIntegerv");
        if (real_glGetIntegerv) {
            GLint fbo = 0;
            real_glGetIntegerv(0x8CA9 /* GL_DRAW_FRAMEBUFFER_BINDING */, &fbo);
            if (fbo > 0) s_last_fbo = (int)fbo;
        }
    }
    wgl_end();
}

static GLenum (*real_glCheckFramebufferStatus)(GLenum);
static GLenum wrap_glCheckFramebufferStatus(GLenum target) {
    if (!wgl_begin()) return 0;
    GLenum r = real_glCheckFramebufferStatus ? real_glCheckFramebufferStatus(target) : 0;
    wgl_end();
    return r;
}

static void (*real_glBlitFramebuffer)(GLint, GLint, GLint, GLint, GLint, GLint, GLint, GLint, GLbitfield, GLenum);
static void wrap_glBlitFramebuffer(GLint srcX0, GLint srcY0, GLint srcX1, GLint srcY1,
        GLint dstX0, GLint dstY0, GLint dstX1, GLint dstY1,
        GLbitfield mask, GLenum filter) {
    if (!wgl_begin()) return;
    if (real_glBlitFramebuffer)
        real_glBlitFramebuffer(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
    wgl_end();
}

#include "gles_wrappers.inc"

static void *gles_wrap_sym(const char *sym, void *real) {
    if (!gl_use_wrappers() || !real) return real;
#define MAP(S, wrap) if (strcmp(sym, S) == 0) { real_##wrap = real; return (void *)wrap_##wrap; }
    MAP("glGetString", glGetString)
    MAP("glGetStringi", glGetStringi)
    MAP("glGetError", glGetError)
    MAP("glGetIntegerv", glGetIntegerv)
    MAP("glViewport", glViewport)
    MAP("glClear", glClear)
    MAP("glClearColor", glClearColor)
    MAP("glEnable", glEnable)
    MAP("glDisable", glDisable)
    MAP("glGenTextures", glGenTextures)
    MAP("glBindTexture", glBindTexture)
    MAP("glTexParameteri", glTexParameteri)
    MAP("glTexImage2D", glTexImage2D)
    MAP("glTexSubImage2D", glTexSubImage2D)
    MAP("glActiveTexture", glActiveTexture)
    MAP("glCreateShader", glCreateShader)
    MAP("glCreateProgram", glCreateProgram)
    MAP("glShaderSource", glShaderSource)
    MAP("glCompileShader", glCompileShader)
    MAP("glAttachShader", glAttachShader)
    MAP("glLinkProgram", glLinkProgram)
    MAP("glUseProgram", glUseProgram)
    MAP("glGetShaderiv", glGetShaderiv)
    MAP("glGetProgramiv", glGetProgramiv)
    MAP("glGetShaderInfoLog", glGetShaderInfoLog)
    MAP("glGetProgramInfoLog", glGetProgramInfoLog)
    MAP("glGetAttribLocation", glGetAttribLocation)
    MAP("glGetUniformLocation", glGetUniformLocation)
    MAP("glBindAttribLocation", glBindAttribLocation)
    MAP("glGenBuffers", glGenBuffers)
    MAP("glBindBuffer", glBindBuffer)
    MAP("glBufferData", glBufferData)
    MAP("glBufferSubData", glBufferSubData)
    MAP("glMapBufferRange", glMapBufferRange)
    MAP("glUnmapBuffer", glUnmapBuffer)
    MAP("glGenVertexArrays", glGenVertexArrays)
    MAP("glBindVertexArray", glBindVertexArray)
    MAP("glEnableVertexAttribArray", glEnableVertexAttribArray)
    MAP("glDisableVertexAttribArray", glDisableVertexAttribArray)
    MAP("glVertexAttribPointer", glVertexAttribPointer)
    MAP("glUniform1i", glUniform1i)
    MAP("glUniform1f", glUniform1f)
    MAP("glUniform2f", glUniform2f)
    MAP("glUniform4f", glUniform4f)
    MAP("glUniform4fv", glUniform4fv)
    MAP("glUniformMatrix4fv", glUniformMatrix4fv)
    MAP("glDrawArrays", glDrawArrays)
    MAP("glDrawElements", glDrawElements)
    MAP("glFlush", glFlush)
    MAP("glFinish", glFinish)
    MAP("glBlitFramebuffer", glBlitFramebuffer)
    MAP("glGenFramebuffers", glGenFramebuffers)
    MAP("glFramebufferTexture2D", glFramebufferTexture2D)
    MAP("glFramebufferRenderbuffer", glFramebufferRenderbuffer)
    MAP("glGenRenderbuffers", glGenRenderbuffers)
    MAP("glBindRenderbuffer", glBindRenderbuffer)
    MAP("glRenderbufferStorage", glRenderbufferStorage)
    MAP("glCheckFramebufferStatus", glCheckFramebufferStatus)
    MAP("glDeleteTextures", glDeleteTextures)
    MAP("glDeleteShader", glDeleteShader)
    MAP("glDeleteProgram", glDeleteProgram)
    MAP("glDeleteBuffers", glDeleteBuffers)
    MAP("glDeleteFramebuffers", glDeleteFramebuffers)
    MAP("glDeleteVertexArrays", glDeleteVertexArrays)
    MAP("glDeleteRenderbuffers", glDeleteRenderbuffers)
    MAP("glBlendFunc", glBlendFunc)
    MAP("glBlendFuncSeparate", glBlendFuncSeparate)
    MAP("glDepthFunc", glDepthFunc)
    MAP("glDepthMask", glDepthMask)
    MAP("glScissor", glScissor)
    MAP("glPixelStorei", glPixelStorei)
    MAP("glCullFace", glCullFace)
    MAP("glGenerateMipmap", glGenerateMipmap)
    MAP("glFramebufferTexture", glFramebufferTexture)
    MAP("glBindBufferBase", glBindBufferBase)
    MAP("glDrawBuffer", glDrawBuffer)
    MAP("glReadBuffer", glReadBuffer)
    MAP("glHint", glHint)
    MAP("glPolygonMode", glPolygonMode)
    MAP("glPointSize", glPointSize)
    MAP("glColorMask", glColorMask)
    MAP("glDepthRange", glDepthRange)
    MAP("glFrontFace", glFrontFace)
    MAP("glLineWidth", glLineWidth)
    MAP("glStencilFunc", glStencilFunc)
    MAP("glStencilMask", glStencilMask)
    MAP("glStencilOp", glStencilOp)
#undef MAP
    static int raw_logs = 0;
    if (raw_logs < 200)
        fprintf(stderr, "[hlg] %sUNWRAPPED: %s @ %p\n",
                s_gles_serial ? "serial " : "GLES RAW ", sym, real);
    raw_logs++;
    return real;
}

/* ==================================================================
 *  hlg_get_proc_address(sym)
 *  Resolve a GL function by name. Intercepts glBindFramebuffer
 *  to track FBO state. Uses eglGetProcAddress, then dlsym fallback.
 * ================================================================== */
static void wrap_glBindFramebuffer(unsigned int target, unsigned int fbo);

static void tracked_glBindFramebuffer(unsigned int target, unsigned int fbo) {
    if (gl_use_wrappers()) {
        wrap_glBindFramebuffer(target, fbo);
        return;
    }
    if (!ensure_thread_gl()) return;
    if (!real_glBindFramebuffer)
        real_glBindFramebuffer = (void (*)(unsigned int, unsigned int))load_gl("glBindFramebuffer");
    if (real_glBindFramebuffer)
        real_glBindFramebuffer(target, fbo);
    if ((target == 0x8CA9) || (target == 0x8CA8) || (target == 0x8D40))
        s_last_fbo = (int)fbo;
}

static void (*real_glBindFramebuffer_mutex)(unsigned int, unsigned int);
static void wrap_glBindFramebuffer(unsigned int target, unsigned int fbo) {
    if (!wgl_begin()) return;
    if (!real_glBindFramebuffer_mutex)
        real_glBindFramebuffer_mutex = (void (*)(unsigned int, unsigned int))load_gl("glBindFramebuffer");
    if (real_glBindFramebuffer_mutex)
        real_glBindFramebuffer_mutex(target, fbo);
    if ((target == 0x8CA9) || (target == 0x8CA8) || (target == 0x8D40))
        s_last_fbo = (int)fbo;
    wgl_end();
}

void *hlg_get_proc_address(const char *sym) {
    if (!gl_use_wrappers())
        ensure_thread_gl();

    int count = ++s_call_count;

    if (count <= 20) {
        fprintf(stderr, "[hlg] get_proc_address(\"%s\") call #%d\n",
                sym ? sym : "NULL", count);
    }

    if (!sym) return NULL;

    if (strcmp(sym, "glBindFramebuffer") == 0 || strcmp(sym, "glBindFramebufferEXT") == 0) {
        if (gl_use_wrappers()) {
            if (!real_glBindFramebuffer_mutex)
                real_glBindFramebuffer_mutex = (void (*)(unsigned int, unsigned int))load_gl("glBindFramebuffer");
            return (void *)wrap_glBindFramebuffer;
        }
        if (!real_glBindFramebuffer)
            real_glBindFramebuffer = (void (*)(unsigned int, unsigned int))load_gl("glBindFramebuffer");
        return (void *)tracked_glBindFramebuffer;
    }

    void *p = eglGetProcAddress(sym);
    if (!p) p = load_gl(sym);
    if (p) return gles_wrap_sym(sym, p);

    // DEBUG: log unresolved symbols — return NULL (no silent stubs; stubs corrupt PPSSPP init)
    if (s_call_count < 200) {
        fprintf(stderr, "[hlg] get_proc_address(\"%s\") UNRESOLVED\n", sym);
    }
    return NULL;
}

/* Clear cached proc pointers so a later hlg_init after hlg_destroy never
 * calls into a terminated EGL/GL context (breaks PCSX2 after PPSSPP). */
static void reset_gl_proc_caches(void) {
    real_glBindFramebuffer = NULL;
    real_glBindFramebuffer_mutex = NULL;    real_glGetString = NULL;
    real_glGetError = NULL;
    real_glGetIntegerv = NULL;
    real_glViewport = NULL;
    real_glClear = NULL;
    real_glClearColor = NULL;
    real_glEnable = NULL;
    real_glDisable = NULL;
    real_glGenTextures = NULL;
    real_glBindTexture = NULL;
    real_glTexParameteri = NULL;
    real_glCreateShader = NULL;
    real_glCreateProgram = NULL;
    real_glShaderSource = NULL;
    real_glCompileShader = NULL;
    real_glAttachShader = NULL;
    real_glLinkProgram = NULL;
    real_glUseProgram = NULL;
    real_glGetShaderiv = NULL;
    real_glGetProgramiv = NULL;
    real_glGenBuffers = NULL;
    real_glBindBuffer = NULL;
    real_glBufferData = NULL;
    real_glGenVertexArrays = NULL;
    real_glBindVertexArray = NULL;
    real_glDrawArrays = NULL;
    real_glDrawElements = NULL;
    real_glFlush = NULL;
    real_glFinish = NULL;
    real_glGenFramebuffers = NULL;
    real_glFramebufferTexture2D = NULL;
    real_glCheckFramebufferStatus = NULL;
    real_glBlitFramebuffer = NULL;
    pBlit = NULL;
    pF = NULL;
    pGI = NULL;
    pRP = NULL;
    pGenFramebuffers = NULL;
    pBindFramebuffer = NULL;
    pGenTextures = NULL;
    pBindTexture = NULL;
    pTexImage2D = NULL;
    pTexParameteri = NULL;
    pFramebufferTexture2D = NULL;
    pDeleteFramebuffers = NULL;
    pDeleteTextures = NULL;
    pCheckFramebufferStatus = NULL;
    s_shared_fbo = 0;
    s_shared_fbo_tex = 0;
    s_fbo_w = 0;
    s_fbo_h = 0;
    s_last_fbo = 0;
    s_call_count = 0;
    s_read_count = 0;
    s_gl_ctx_thread = 0;
    s_ensure_gl_fail_logs = 0;
    s_gf_call_count = 0;
    if (libGL_handle) {
        dlclose(libGL_handle);
        libGL_handle = NULL;
    }
}

/* Load raw GL entry points for FBO management (no wrappers). */
static void load_fbo_procs(void) {
    if (!pGenFramebuffers) pGenFramebuffers = (void (*)(int, unsigned int *))load_gl("glGenFramebuffers");
    if (!pBindFramebuffer) pBindFramebuffer = (void (*)(unsigned int, unsigned int))load_gl("glBindFramebuffer");
    if (!pGenTextures)    pGenTextures    = (void (*)(int, unsigned int *))load_gl("glGenTextures");
    if (!pBindTexture)    pBindTexture    = (void (*)(unsigned int, unsigned int))load_gl("glBindTexture");
    if (!pTexImage2D)     pTexImage2D     = (void (*)(unsigned int, int, int, int, int, int, unsigned int, unsigned int, const void *))load_gl("glTexImage2D");
    if (!pTexParameteri)  pTexParameteri  = (void (*)(unsigned int, unsigned int, int))load_gl("glTexParameteri");
    if (!pFramebufferTexture2D) pFramebufferTexture2D = (void (*)(unsigned int, unsigned int, unsigned int, unsigned int, int))load_gl("glFramebufferTexture2D");
    if (!pDeleteFramebuffers) pDeleteFramebuffers = (void (*)(int, const unsigned int *))load_gl("glDeleteFramebuffers");
    if (!pDeleteTextures) pDeleteTextures = (void (*)(int, const unsigned int *))load_gl("glDeleteTextures");
    if (!pCheckFramebufferStatus) pCheckFramebufferStatus = (unsigned int (*)(unsigned int))load_gl("glCheckFramebufferStatus");
}

/* Sample an FBO for non-black pixels. Returns -1 if FBO incomplete/missing. */
static int sample_fbo_nz(unsigned int fbo, int w, int h) {
    if (!fbo || !pBindFramebuffer || !pRP || w <= 0 || h <= 0) return -1;
    pBindFramebuffer(0x8CA8 /* GL_READ_FRAMEBUFFER */, fbo);
    if (pCheckFramebufferStatus) {
        unsigned int st = pCheckFramebufferStatus(0x8CA8);
        if (st != 0x8CD5 /* GL_FRAMEBUFFER_COMPLETE */) return -1;
    }
    typedef void (*glReadBuffer_t)(unsigned int);
    glReadBuffer_t rb = (glReadBuffer_t)load_gl("glReadBuffer");
    if (rb) rb(0x8CE0 /* GL_COLOR_ATTACHMENT0 */);
    /* Sparse sample: every 8th pixel in a 64x64 grid for speed */
    int nz = 0, samples = 0;
    unsigned int px = 0;
    int step_x = w > 64 ? w / 64 : 1;
    int step_y = h > 64 ? h / 64 : 1;
    for (int y = 0; y < h; y += step_y) {
        for (int x = 0; x < w; x += step_x) {
            ((void (*)(int, int, int, int, unsigned int, unsigned int, void *))pRP)(
                x, y, 1, 1, 0x1908, 0x1401, &px);
            if (px & 0x00FFFFFF) nz++;
            samples++;
        }
    }
    return nz;
}

/* PPSSPP renders to internal FBOs (2+) and may not blit to our shared FBO 1. */
static unsigned int find_ppsspp_render_fbo(int w, int h, int *out_nz) {
    unsigned int best = 0;
    int best_nz = 0;
    for (unsigned int fbo = 2; fbo <= 64; fbo++) {
        int nz = sample_fbo_nz(fbo, w, h);
        if (nz > best_nz) {
            best_nz = nz;
            best = fbo;
        }
    }
    if (out_nz) *out_nz = best_nz;
    return best;
}

static void read_bound_fbo(int w, int h, void *pixels_out) {
    typedef void (*glReadBuffer_t)(unsigned int);
    glReadBuffer_t rb = (glReadBuffer_t)load_gl("glReadBuffer");
    if (rb) rb(0x8CE0 /* GL_COLOR_ATTACHMENT0 */);
    ((void (*)(int, int, int, int, unsigned int, unsigned int, void *))pRP)(
        0, 0, w, h, 0x1908 /* GL_RGBA */, 0x1401 /* GL_UNSIGNED_BYTE */, pixels_out);
}

/* (Re)create the shared render FBO at size w×h. Must be called with a
 * context current (objects are shared, so any pooled context works). */
static void recreate_shared_fbo(int w, int h) {
    if (w <= 0 || h <= 0) return;
    if (!pGenFramebuffers) load_fbo_procs();
    if (!pGenFramebuffers || !pBindFramebuffer || !pGenTextures || !pBindTexture ||
        !pTexImage2D || !pTexParameteri || !pFramebufferTexture2D) {
        fprintf(stderr, "[hlg] recreate_shared_fbo: missing GL procs\n");
        return;
    }
    if (s_shared_fbo == 0) pGenFramebuffers(1, &s_shared_fbo);
    if (s_shared_fbo_tex == 0) pGenTextures(1, &s_shared_fbo_tex);

    pBindTexture(0x0DE1 /* GL_TEXTURE_2D */, s_shared_fbo_tex);
    pTexImage2D(0x0DE1, 0, 0x8058 /* GL_RGBA8 */, w, h, 0,
                0x1908 /* GL_RGBA */, 0x1401 /* GL_UNSIGNED_BYTE */, NULL);
    pTexParameteri(0x0DE1, 0x2801 /* GL_TEXTURE_MIN_FILTER */, 0x2600 /* NEAREST */);
    pTexParameteri(0x0DE1, 0x2800 /* GL_TEXTURE_MAG_FILTER */, 0x2600 /* NEAREST */);
    pTexParameteri(0x0DE1, 0x280E /* GL_TEXTURE_WRAP_S */, 0x812F /* CLAMP_TO_EDGE */);
    pTexParameteri(0x0DE1, 0x280F /* GL_TEXTURE_WRAP_T */, 0x812F /* CLAMP_TO_EDGE */);

    pBindFramebuffer(0x8D40 /* GL_FRAMEBUFFER */, s_shared_fbo);
    pFramebufferTexture2D(0x8D40, 0x8CE0 /* GL_COLOR_ATTACHMENT0 */,
                          0x0DE1, s_shared_fbo_tex, 0);
    pBindFramebuffer(0x8D40, 0);
    pBindTexture(0x0DE1, 0);
    s_fbo_w = w;
    s_fbo_h = h;
    fprintf(stderr, "[hlg] shared FBO %dx%d (id=%u tex=%u)\n", w, h, s_shared_fbo, s_shared_fbo_tex);
}

/* ==================================================================
 *  hlg_get_framebuffer()
 *  Returns the shared render FBO id. Cores render into this; readback
 *  reads from it. This is what RetroArch returns to HW-render cores.
 * ================================================================== */
unsigned long hlg_get_framebuffer(void) {
    ensure_thread_gl();
    if (s_gf_call_count < 8) {
        fprintf(stderr, "[hlg] get_framebuffer() -> %u (call #%d)\n",
                s_shared_fbo, ++s_gf_call_count);
    } else {
        s_gf_call_count++;
    }
    return (unsigned long)s_shared_fbo;
}

/* Return function pointers (for JNA to read from C memory) */
void *hlg_get_framebuffer_ptr(void)   { return (void *)hlg_get_framebuffer; }
void *hlg_get_proc_address_ptr(void)  { return (void *)hlg_get_proc_address; }

/* Native variadic log callback for RETRO_ENVIRONMENT_GET_LOG_INTERFACE.
 * libretro's retro_log_printf_t is `void (*)(enum level, const char *fmt, ...)`.
 * JNA can't bind variadic callbacks, so we expose this C function pointer
 * and Java writes it directly into retro_log_callback.log. This lets us
 * finally see PPSSPP/PCSX2 printf-style log output (shader errors, etc). */
void hlg_log_cb(int level, const char *fmt, ...) {
    char buf[2048];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt ? fmt : "(null)", ap);
    va_end(ap);
    const char *tag = "DBG";
    switch (level) {
        case 0: tag = "ERR"; break;
        case 1: tag = "WRN"; break;
        case 2: tag = "INF"; break;
        case 3: tag = "DBG"; break;
    }
    fprintf(stderr, "[hlg][%s] %s\n", tag, buf);
}
void *hlg_get_log_cb_ptr(void) { return (void *)hlg_log_cb; }

/* ==================================================================
 *  hlg_debug_fbo()
 *  Print current GL FBO state for debugging.
 * ================================================================== */
void hlg_debug_fbo(void) {
    if (!wgl_begin()) return;

    if (!pGI)  pGI  = load_gl("glGetIntegerv");
    if (!pRP)  pRP  = load_gl("glReadPixels");
    if (!pF)   pF   = load_gl("glFinish");
    if (!pBlit) pBlit = load_gl("glBlitFramebuffer");

    if (!pGI) return;

    int readFbo = 0, drawFbo = 0;
    int vp[4] = {0, 0, 0, 0};

    ((void (*)(unsigned int, int *))pGI)(0x8CA8, &readFbo);  /* GL_READ_FRAMEBUFFER_BINDING */
    ((void (*)(unsigned int, int *))pGI)(0x8CA9, &drawFbo);  /* GL_DRAW_FRAMEBUFFER_BINDING */
    ((void (*)(unsigned int, int *))pGI)(0x0BA2, vp);         /* GL_VIEWPORT */

    if (s_read_count <= 10) {
        fprintf(stderr, "[hlg] video_cb #%d: readFbo=%d drawFbo=%d vp=%d,%d,%d,%d "
                "lastFbo=%d pbuf=%dx%d\n",
                s_read_count, readFbo, drawFbo,
                vp[0], vp[1], vp[2], vp[3],
                s_last_fbo, s_w, s_h);
    }
    s_read_count++;
    wgl_end();
}

/* ==================================================================
 *  hlg_read_pixels(viewport_out, pixels_out, max_pixels)
 *  Read the current GL framebuffer into a CPU buffer.
 *  viewport_out: int[4] receives the GL viewport.
 *  pixels_out:   pre-allocated buffer of at least width*height*4 bytes.
 * ================================================================== */
void hlg_read_pixels(int *viewport_out, void *pixels_out, int max_pixels) {
    if (!viewport_out || !pixels_out) return;
    if (!wgl_begin()) return;

    if (!pGI)  pGI  = load_gl("glGetIntegerv");
    if (!pRP)  pRP  = load_gl("glReadPixels");
    if (!pF)   pF   = load_gl("glFinish");
    if (!pBlit) pBlit = load_gl("glBlitFramebuffer");
    if (!pBindFramebuffer) load_fbo_procs();
    if (!pGI || !pRP || !pBindFramebuffer) {
        wgl_end();
        return;
    }

    int vp[4] = {0};
    ((void (*)(unsigned int, int *))pGI)(0x0BA2, vp);  /* GL_VIEWPORT */

    int read_w = vp[2] > 0 ? vp[2] : s_w;
    int read_h = vp[3] > 0 ? vp[3] : s_h;
    if (read_w <= 0 || read_h <= 0) { read_w = s_w; read_h = s_h; }

    /* Grow the shared FBO to fit the GL viewport (never shrink — avoids
     * resize thrash between Java's retro-geometry size and the GL viewport). */
    if (s_shared_fbo && (read_w > s_fbo_w || read_h > s_fbo_h)) {
        int fw = read_w > s_fbo_w ? read_w : s_fbo_w;
        int fh = read_h > s_fbo_h ? read_h : s_fbo_h;
        recreate_shared_fbo(fw, fh);
    }

    /* Clamp read region to the FBO size. */
    if (s_shared_fbo) {
        if (read_w > s_fbo_w) read_w = s_fbo_w;
        if (read_h > s_fbo_h) read_h = s_fbo_h;
    }

    if (max_pixels > 0 && read_w * read_h > max_pixels) {
        if (s_read_count <= 10)
            fprintf(stderr, "[hlg] read_pixels: clamp %dx%d to fit %d pixels\n",
                    read_w, read_h, max_pixels);
        read_h = max_pixels / read_w;
        if (read_h <= 0) { wgl_end(); return; }
    }

    viewport_out[0] = vp[0];
    viewport_out[1] = vp[1];
    viewport_out[2] = read_w;
    viewport_out[3] = read_h;

    if (pF) ((void (*)(void))pF)();

    /* Self-test on first readback: clear shared FBO to red, read it back.
     * If we get red, our FBO readback pipeline works and the core just
     * isn't rendering into FBO 1. If black, our readback is broken. */
    static int selftest_done = 0;
    if (s_shared_fbo && !selftest_done) {
        selftest_done = 1;
        pBindFramebuffer(0x8D40 /* GL_FRAMEBUFFER */, s_shared_fbo);
        typedef void (*glClearColor_t)(float, float, float, float);
        typedef void (*glClear_t)(unsigned int);
        glClearColor_t ccc = (glClearColor_t)load_gl("glClearColor");
        glClear_t cc = (glClear_t)load_gl("glClear");
        if (ccc && cc) {
            ccc(1.0f, 0.0f, 0.0f, 1.0f);
            cc(0x4000 /* GL_COLOR_BUFFER_BIT */);
            if (pF) ((void (*)(void))pF)();
        }
        unsigned int testpx = 0;
        pBindFramebuffer(0x8CA8 /* GL_READ_FRAMEBUFFER */, s_shared_fbo);
        typedef void (*glReadBuffer_t)(unsigned int);
        glReadBuffer_t rb = (glReadBuffer_t)load_gl("glReadBuffer");
        if (rb) rb(0x8CE0);
        ((void (*)(int, int, int, int, unsigned int, unsigned int, void *))pRP)(
            0, 0, 1, 1, 0x1908, 0x1401, &testpx);
        fprintf(stderr, "[hlg] SELFTEST: cleared FBO1 to red, read pixel=0x%08x "
                "(expect 0xff0000ff)\n", testpx);
    }

    /* Bind the shared render FBO for reading — the core rendered into it. */
    unsigned int readTarget = s_shared_fbo ? s_shared_fbo : 0;
    pBindFramebuffer(0x8CA8 /* GL_READ_FRAMEBUFFER */, readTarget);
    if (s_shared_fbo) {
        typedef void (*glReadBuffer_t)(unsigned int);
        glReadBuffer_t rb = (glReadBuffer_t)load_gl("glReadBuffer");
        if (rb) rb(0x8CE0 /* GL_COLOR_ATTACHMENT0 */);
    } else if (s_compat_profile) {
        typedef void (*glReadBuffer_t)(unsigned int);
        glReadBuffer_t rb = (glReadBuffer_t)load_gl("glReadBuffer");
        if (rb) rb(0x0405 /* GL_BACK */);
    }

    ((void (*)(int, int, int, int, unsigned int, unsigned int, void *))pRP)(
        0, 0, read_w, read_h,
        0x1908 /* GL_RGBA */, 0x1401 /* GL_UNSIGNED_BYTE */, pixels_out);

    int total = read_w * read_h;
    int nonzero = 0;
    unsigned int *px = (unsigned int *)pixels_out;
    for (int i = 0; i < total; i++) {
        if (px[i] & 0x00FFFFFF) nonzero++;
    }

    /* If shared FBO 1 is empty, PPSSPP may have rendered to an internal FBO. */
    unsigned int alt_fbo = 0;
    int alt_nz = 0;
    if (nonzero == 0 && s_shared_fbo) {
        alt_fbo = find_ppsspp_render_fbo(read_w, read_h, &alt_nz);
        if (alt_fbo > 0 && alt_nz > 0) {
            pBindFramebuffer(0x8CA8 /* GL_READ_FRAMEBUFFER */, alt_fbo);
            read_bound_fbo(read_w, read_h, pixels_out);
            nonzero = 0;
            for (int i = 0; i < total; i++) {
                if (px[i] & 0x00FFFFFF) nonzero++;
            }
            if (s_read_count <= 100 || (s_read_count % 60) == 0) {
                fprintf(stderr, "[hlg] read_pixels: FBO1 empty, read from internal FBO %u "
                        "(probe_nz=%d full_nz=%d)\n", alt_fbo, alt_nz, nonzero);
            }
        }
    }

    /* Diagnostic: also sample the default framebuffer (FBO 0) to find
     * out where the core actually rendered. */
    int fbo0_nonzero = -1;
    if (s_shared_fbo && (s_read_count <= 100 || (s_read_count % 60) == 0)) {
        pBindFramebuffer(0x8CA8 /* GL_READ_FRAMEBUFFER */, 0);
        if (s_compat_profile) {
            typedef void (*glReadBuffer_t)(unsigned int);
            glReadBuffer_t rb = (glReadBuffer_t)load_gl("glReadBuffer");
            if (rb) rb(0x0405 /* GL_BACK */);
        }
        unsigned int *tmp = (unsigned int *)malloc((size_t)total * 4);
        if (tmp) {
            ((void (*)(int, int, int, int, unsigned int, unsigned int, void *))pRP)(
                0, 0, read_w, read_h, 0x1908, 0x1401, tmp);
            fbo0_nonzero = 0;
            for (int i = 0; i < total; i++) if (tmp[i] & 0x00FFFFFF) fbo0_nonzero++;
            free(tmp);
        }
    }

    if (s_read_count <= 100 || (s_read_count % 60) == 0 || nonzero > 0) {
        fprintf(stderr, "[hlg] read_pixels: fbo=%u vp=%dx%d read=%dx%d fboSz=%dx%d "
                "fbo1nz=%d fbo0nz=%d altFbo=%u (#%d)\n",
                s_shared_fbo, vp[2], vp[3], read_w, read_h, s_fbo_w, s_fbo_h,
                nonzero, fbo0_nonzero, alt_fbo, s_read_count);
    }
    s_read_count++;

    wgl_end();
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
    pthread_mutex_lock(&s_egl_mutex);
    reset_gl_proc_caches();
    eglMakeCurrent(s_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (tl_surface) {
        eglDestroySurface(s_display, tl_surface);
        tl_surface = EGL_NO_SURFACE;
    }
    if (s_shared_surface) {
        eglDestroySurface(s_display, s_shared_surface);
        s_shared_surface = EGL_NO_SURFACE;
    }
    tl_ctx = EGL_NO_CONTEXT;
    destroy_ctx_pool();
    if (s_context) {
        eglDestroyContext(s_display, s_context);
        s_context = EGL_NO_CONTEXT;
    }
    if (s_display != EGL_NO_DISPLAY) {
        eglTerminate(s_display);
        s_display = EGL_NO_DISPLAY;
    }
    s_initialized = 0;
    s_gles_serial = 0;
    s_compat_profile = 0;
    s_gl_ctx_thread = 0;
    pthread_mutex_unlock(&s_egl_mutex);
}
