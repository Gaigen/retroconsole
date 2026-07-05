/*
 * headless_gl_win.c — Headless WGL OpenGL context for libretro on Windows.
 * Same C API as headless_gl.c (Linux EGL) so LibretroCoreWindows can share
 * the HeadlessGL JNA interface.
 *
 * Build (MinGW-w64):
 *   gcc -shared -O2 -o .libheadless_gl.dll headless_gl_win.c -lopengl32 -lgdi32 -luser32
 */

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <GL/gl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>

#ifndef WGL_CONTEXT_MAJOR_VERSION_ARB
#define WGL_CONTEXT_MAJOR_VERSION_ARB 0x2091
#define WGL_CONTEXT_MINOR_VERSION_ARB 0x2092
#define WGL_CONTEXT_PROFILE_MASK_ARB  0x9126
#define WGL_CONTEXT_CORE_PROFILE_BIT_ARB 0x00000001
#define WGL_CONTEXT_COMPATIBILITY_PROFILE_BIT_ARB 0x00000002
#define WGL_DRAW_TO_WINDOW_ARB 0x2001
#define WGL_SUPPORT_OPENGL_ARB 0x2010
#define WGL_DOUBLE_BUFFER_ARB  0x2011
#define WGL_PIXEL_TYPE_ARB     0x2013
#define WGL_TYPE_RGBA_ARB      0x202B
#define WGL_COLOR_BITS_ARB     0x2014
#define WGL_DEPTH_BITS_ARB     0x2022
#define WGL_STENCIL_BITS_ARB   0x2023
#endif

typedef HGLRC (WINAPI *PFNWGLCREATECONTEXTATTRIBSARBPROC)(HDC, HGLRC, const int *);
typedef BOOL  (WINAPI *PFNWGLCHOOSEPIXELFORMATARBPROC)(HDC, const int *, const float *, UINT, int *, UINT *);

static HWND s_hwnd = NULL;
static HDC  s_hdc  = NULL;
static HGLRC s_hglrc = NULL;
static int s_initialized = 0;
static int s_gl_major = 3;
static int s_gl_minor = 1;
static int s_api_mode = 0;
static int s_compat_profile = 0;
static int s_w = 640, s_h = 480;
static CRITICAL_SECTION s_gl_cs;
static int s_cs_ready = 0;
static char s_gpu_info[512] = "";
static int s_last_fbo = 0;
static int s_read_count = 0;

static void hlg_destroy_internal(void);

static void ensure_cs(void) {
    if (!s_cs_ready) {
        InitializeCriticalSection(&s_gl_cs);
        s_cs_ready = 1;
    }
}

static void *load_gl(const char *name) {
    void *p = (void *)wglGetProcAddress(name);
    if (!p) {
        HMODULE mod = GetModuleHandleA("opengl32.dll");
        if (mod) p = (void *)GetProcAddress(mod, name);
    }
    return p;
}

static void log_gpu_identity(void) {
    if (s_gpu_info[0]) return;
    typedef const unsigned char *(*glGetString_t)(unsigned int);
    glGetString_t gs = (glGetString_t)load_gl("glGetString");
    const char *vendor = gs ? (const char *)gs(0x1F00) : "(null)";
    const char *renderer = gs ? (const char *)gs(0x1F01) : "(null)";
    const char *version = gs ? (const char *)gs(0x1F02) : "(null)";
    snprintf(s_gpu_info, sizeof(s_gpu_info), "%s / %s (%s)",
             renderer ? renderer : "?", vendor ? vendor : "?", version ? version : "?");
    fprintf(stderr, "[hlg-win] GPU: %s\n", s_gpu_info);
}

static int ensure_current(void) {
    if (!s_initialized || !s_hdc || !s_hglrc) return 0;
    if (wglGetCurrentContext() == s_hglrc) return 1;
    return wglMakeCurrent(s_hdc, s_hglrc) ? 1 : 0;
}

static LRESULT CALLBACK hidden_wnd_proc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
  return DefWindowProcA(hwnd, msg, wp, lp);
}

static int create_hidden_window(void) {
    static int registered = 0;
    if (!registered) {
        WNDCLASSA wc = {0};
        wc.lpfnWndProc = hidden_wnd_proc;
        wc.hInstance = GetModuleHandleA(NULL);
        wc.lpszClassName = "RetroConsoleHeadlessGL";
        if (!RegisterClassA(&wc)) return 0;
        registered = 1;
    }
    s_hwnd = CreateWindowExA(0, "RetroConsoleHeadlessGL", "hlg",
            WS_OVERLAPPEDWINDOW, 0, 0, s_w, s_h, NULL, NULL,
            GetModuleHandleA(NULL), NULL);
    if (!s_hwnd) return 0;
    ShowWindow(s_hwnd, SW_HIDE);
    s_hdc = GetDC(s_hwnd);
    return s_hdc != NULL;
}

static int create_gl_context(int major, int minor, int compat) {
    PIXELFORMATDESCRIPTOR pfd = {0};
    pfd.nSize = sizeof(pfd);
    pfd.nVersion = 1;
    pfd.dwFlags = PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL | PFD_DOUBLEBUFFER;
    pfd.iPixelType = PFD_TYPE_RGBA;
    pfd.cColorBits = 32;
    pfd.cDepthBits = 24;
    pfd.cStencilBits = 8;
    int pf = ChoosePixelFormat(s_hdc, &pfd);
    if (!pf || !SetPixelFormat(s_hdc, pf, &pfd)) return 0;

    HGLRC tmp = wglCreateContext(s_hdc);
    if (!tmp || !wglMakeCurrent(s_hdc, tmp)) return 0;

    PFNWGLCREATECONTEXTATTRIBSARBPROC createAttribs =
        (PFNWGLCREATECONTEXTATTRIBSARBPROC)wglGetProcAddress("wglCreateContextAttribsARB");

    if (createAttribs) {
        int profile = compat ? WGL_CONTEXT_COMPATIBILITY_PROFILE_BIT_ARB
                             : WGL_CONTEXT_CORE_PROFILE_BIT_ARB;
        int attribs[] = {
            WGL_CONTEXT_MAJOR_VERSION_ARB, major,
            WGL_CONTEXT_MINOR_VERSION_ARB, minor,
            WGL_CONTEXT_PROFILE_MASK_ARB, profile,
            0
        };
        s_hglrc = createAttribs(s_hdc, 0, attribs);
        if (!s_hglrc && !compat) {
            attribs[5] = WGL_CONTEXT_COMPATIBILITY_PROFILE_BIT_ARB;
            s_hglrc = createAttribs(s_hdc, 0, attribs);
            s_compat_profile = 1;
        }
    }
    if (!s_hglrc) {
        s_hglrc = tmp;
        tmp = NULL;
    } else if (tmp) {
        wglMakeCurrent(NULL, NULL);
        wglDeleteContext(tmp);
        tmp = NULL;
    }

    if (!wglMakeCurrent(s_hdc, s_hglrc)) return 0;

    typedef void (*glViewport_t)(int, int, int, int);
    glViewport_t gv = (glViewport_t)load_gl("glViewport");
    if (gv) gv(0, 0, s_w, s_h);
    return 1;
}

__declspec(dllexport) int hlg_init_ex(int api, int major, int minor, int flags) {
    int compat = flags & 1;
    if (s_initialized) {
        if (s_api_mode == api && s_gl_major == major && s_gl_minor == minor
            && s_compat_profile == compat)
            return 1;
        hlg_destroy_internal();
    }

    s_api_mode = api ? 1 : 0;
    s_compat_profile = compat;
    s_gl_major = major;
    s_gl_minor = minor;
    ensure_cs();

    if (s_api_mode == 1) {
        fprintf(stderr, "[hlg-win] GLES not supported on Windows WGL — use desktop GL\n");
        return 0;
    }

    if (!create_hidden_window()) {
        fprintf(stderr, "[hlg-win] hidden window creation failed\n");
        return 0;
    }
    if (!create_gl_context(major, minor, compat)) {
        fprintf(stderr, "[hlg-win] GL %d.%d context creation failed\n", major, minor);
        hlg_destroy_internal();
        return 0;
    }

    s_initialized = 1;
    log_gpu_identity();
    fprintf(stderr, "[hlg-win] init OK: GL %d.%d %s %dx%d\n",
            s_gl_major, s_gl_minor, s_compat_profile ? "Compat" : "Core", s_w, s_h);
    return 1;
}

__declspec(dllexport) int hlg_init(int major, int minor) {
    return hlg_init_ex(0, major, minor, 0);
}

__declspec(dllexport) void hlg_destroy(void) {
    hlg_destroy_internal();
}

static void hlg_destroy_internal(void) {
    if (s_hglrc) {
        wglMakeCurrent(NULL, NULL);
        wglDeleteContext(s_hglrc);
        s_hglrc = NULL;
    }
    if (s_hdc && s_hwnd) {
        ReleaseDC(s_hwnd, s_hdc);
        s_hdc = NULL;
    }
    if (s_hwnd) {
        DestroyWindow(s_hwnd);
        s_hwnd = NULL;
    }
    s_initialized = 0;
    s_gpu_info[0] = '\0';
    s_last_fbo = 0;
    s_read_count = 0;
}

__declspec(dllexport) void hlg_release(void) {
    if (s_initialized) {
        EnterCriticalSection(&s_gl_cs);
        wglMakeCurrent(NULL, NULL);
        LeaveCriticalSection(&s_gl_cs);
        fprintf(stderr, "[hlg-win] release: context detached from init thread\n");
    }
}

__declspec(dllexport) int hlg_make_current(void) {
    if (!s_initialized) return 0;
    EnterCriticalSection(&s_gl_cs);
    int ok = ensure_current();
    LeaveCriticalSection(&s_gl_cs);
    return ok;
}

__declspec(dllexport) int hlg_resize(int w, int h) {
    if (!s_initialized || w <= 0 || h <= 0) return 0;
    if (w == s_w && h == s_h) return 1;
    s_w = w;
    s_h = h;
    if (s_hwnd) SetWindowPos(s_hwnd, NULL, 0, 0, w, h, SWP_NOMOVE | SWP_NOZORDER | SWP_NOACTIVATE);
    EnterCriticalSection(&s_gl_cs);
    if (ensure_current()) {
        typedef void (*glViewport_t)(int, int, int, int);
        glViewport_t gv = (glViewport_t)load_gl("glViewport");
        if (gv) gv(0, 0, s_w, s_h);
    }
    LeaveCriticalSection(&s_gl_cs);
    return 1;
}

static void (*real_glBindFramebuffer)(unsigned int, unsigned int);

static void tracked_glBindFramebuffer(unsigned int target, unsigned int fbo) {
    if (!ensure_current()) return;
    if (!real_glBindFramebuffer)
        real_glBindFramebuffer = (void (*)(unsigned int, unsigned int))load_gl("glBindFramebuffer");
    if (real_glBindFramebuffer)
        real_glBindFramebuffer(target, fbo);
    if (target == 0x8CA9 || target == 0x8CA8 || target == 0x8D40)
        s_last_fbo = (int)fbo;
}

__declspec(dllexport) void *hlg_get_proc_address(const char *sym) {
    if (!sym) return NULL;
    if (!ensure_current()) return NULL;
    if (strcmp(sym, "glBindFramebuffer") == 0 || strcmp(sym, "glBindFramebufferEXT") == 0) {
        if (!real_glBindFramebuffer)
            real_glBindFramebuffer = (void (*)(unsigned int, unsigned int))load_gl("glBindFramebuffer");
        return (void *)tracked_glBindFramebuffer;
    }
    return load_gl(sym);
}

__declspec(dllexport) unsigned long hlg_get_framebuffer(void) {
    return 0;
}

__declspec(dllexport) void *hlg_get_framebuffer_ptr(void) {
    return (void *)hlg_get_framebuffer;
}

__declspec(dllexport) void *hlg_get_proc_address_ptr(void) {
    return (void *)hlg_get_proc_address;
}

__declspec(dllexport) void hlg_debug_fbo(void) {
    if (!ensure_current()) return;
    typedef void (*glGetIntegerv_t)(unsigned int, int *);
    glGetIntegerv_t gi = (glGetIntegerv_t)load_gl("glGetIntegerv");
    if (!gi) return;
    int drawFbo = 0;
    gi(0x8CA9, &drawFbo);
    fprintf(stderr, "[hlg-win] debug_fbo: drawFbo=%d lastFbo=%d\n", drawFbo, s_last_fbo);
}

__declspec(dllexport) void hlg_read_pixels(int *viewport_out, void *pixels_out,
        int max_pixels, int req_w, int req_h) {
    if (!viewport_out || !pixels_out) return;
    EnterCriticalSection(&s_gl_cs);
    if (!ensure_current()) {
        LeaveCriticalSection(&s_gl_cs);
        return;
    }

    typedef void (*glGetIntegerv_t)(unsigned int, int *);
    typedef void (*glReadPixels_t)(int, int, int, int, unsigned int, unsigned int, void *);
    typedef void (*glFinish_t)(void);
    glGetIntegerv_t gi = (glGetIntegerv_t)load_gl("glGetIntegerv");
    glReadPixels_t rp = (glReadPixels_t)load_gl("glReadPixels");
    glFinish_t fin = (glFinish_t)load_gl("glFinish");
    if (!gi || !rp) {
        LeaveCriticalSection(&s_gl_cs);
        return;
    }

    int vp[4] = {0};
    gi(0x0BA2, vp);
    int read_w = req_w > 0 ? req_w : (vp[2] > 0 ? vp[2] : s_w);
    int read_h = req_h > 0 ? req_h : (vp[3] > 0 ? vp[3] : s_h);
    if (max_pixels > 0 && read_w * read_h > max_pixels)
        read_h = max_pixels / read_w;
    if (read_w <= 0 || read_h <= 0) {
        LeaveCriticalSection(&s_gl_cs);
        return;
    }

    viewport_out[0] = vp[0];
    viewport_out[1] = vp[1];
    viewport_out[2] = vp[2];
    viewport_out[3] = vp[3];

    if (fin && (s_read_count % 120) == 0) fin();
    rp(0, 0, read_w, read_h, 0x1908, 0x1401, pixels_out);
    s_read_count++;
    LeaveCriticalSection(&s_gl_cs);
}

__declspec(dllexport) void hlg_dump_hw_render(const void *data, int size) {
    fprintf(stderr, "[hlg-win] HW RENDER DUMP (%d bytes)\n", size);
    const unsigned char *p = (const unsigned char *)data;
    for (int i = 0; i < size; i += 8) {
        fprintf(stderr, "  +%2d: ", i);
        for (int j = 0; j < 8 && (i + j) < size; j++)
            fprintf(stderr, "%02x ", p[i + j]);
        fprintf(stderr, "\n");
    }
}

__declspec(dllexport) const char *hlg_get_gpu_info(void) {
    return s_gpu_info[0] ? s_gpu_info : "GPU info not yet available";
}

__declspec(dllexport) void hlg_log_gpu_identity(void) {
    if (s_initialized && ensure_current()) log_gpu_identity();
}

__declspec(dllexport) void hlg_log_cb(int level, const char *fmt, ...) {
    char buf[2048];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt ? fmt : "(null)", ap);
    va_end(ap);
    fprintf(stderr, "[hlg-win][%d] %s\n", level, buf);
}

__declspec(dllexport) void *hlg_get_log_cb_ptr(void) {
    return (void *)hlg_log_cb;
}

BOOL WINAPI DllMain(HINSTANCE hinst, DWORD reason, LPVOID reserved) {
    (void)hinst;
    (void)reserved;
    if (reason == DLL_PROCESS_DETACH && s_cs_ready) {
        DeleteCriticalSection(&s_gl_cs);
        s_cs_ready = 0;
    }
    return TRUE;
}
