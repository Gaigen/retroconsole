/*
 * headless_gl_win.c — Headless WGL OpenGL for libretro (Windows).
 *
 * Per-instance: hidden window + HGLRC + FBO (hlg_create/hlg_free).
 * get_current_framebuffer has no user-data → thread-local t_cur.
 * Build: gcc -shared -O2 -o .libheadless_gl.dll headless_gl_win.c -lopengl32 -lgdi32 -luser32
 */

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <GL/gl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <ctype.h>

#ifndef WGL_CONTEXT_MAJOR_VERSION_ARB
#define WGL_CONTEXT_MAJOR_VERSION_ARB 0x2091
#define WGL_CONTEXT_MINOR_VERSION_ARB 0x2092
#define WGL_CONTEXT_PROFILE_MASK_ARB  0x9126
#define WGL_CONTEXT_CORE_PROFILE_BIT_ARB 0x00000001
#define WGL_CONTEXT_COMPATIBILITY_PROFILE_BIT_ARB 0x00000002
#endif

#define HLG_GL_VENDOR            0x1F00
#define HLG_GL_RENDERER          0x1F01
#define HLG_GL_VERSION           0x1F02
#define HLG_GL_VIEWPORT          0x0BA2
#define HLG_GL_BGRA              0x80E1
#define HLG_GL_UNSIGNED_BYTE     0x1401
#define HLG_GL_BACK              0x0405
#define HLG_GL_PACK_ALIGNMENT    0x0D05
#define HLG_GL_DRAW_FRAMEBUFFER  0x8CA9
#define HLG_GL_READ_FRAMEBUFFER  0x8CA8
#define HLG_GL_FRAMEBUFFER       0x8D40
#define HLG_GL_READ_FB_BINDING   0x8CAA
#define HLG_GL_COLOR_ATTACHMENT0        0x8CE0
#define HLG_GL_RENDERBUFFER             0x8D41
#define HLG_GL_RGBA8                    0x8058
#define HLG_GL_DEPTH24_STENCIL8         0x88F0
#define HLG_GL_DEPTH_STENCIL_ATTACHMENT 0x821A
#define HLG_GL_FRAMEBUFFER_COMPLETE     0x8CD5
#define HLG_GL_PIXEL_PACK_BUFFER        0x88EB
#define HLG_GL_STREAM_READ              0x88E8
#define HLG_GL_READ_ONLY                0x88B8

typedef HGLRC (WINAPI *PFNWGLCREATECONTEXTATTRIBSARBPROC)(HDC, HGLRC, const int *);

/* Per-instance GL context. */
typedef struct hlg_ctx {
    HWND  hwnd;
    HDC   hdc;
    HGLRC hglrc;
    int   initialized;
    int   gl_major, gl_minor;
    int   api_mode;
    int   compat_profile;
    int   w, h;
    CRITICAL_SECTION cs;
    char  gpu_info[512];
    int   last_fbo;
    int   read_count;
    unsigned int fbo, color_rb, depth_rb;
    int   fbo_ready;
    unsigned int pbo[2];
    int   pbo_idx;
    int   pbo_w[2], pbo_h[2];
    int   pbo_cap_w, pbo_cap_h;
} hlg_ctx;

#if defined(_MSC_VER)
#define HLG_TLS __declspec(thread)
#else
#define HLG_TLS __thread
#endif
static HLG_TLS hlg_ctx *t_cur = NULL;

static CRITICAL_SECTION s_global_cs;
static int s_global_cs_ready = 0;
static int s_wndclass_registered = 0;

static void hlg_destroy_internal(hlg_ctx *c);

/* GL function cache (process-wide). */
static void *load_gl(const char *name) {
    void *p = (void *)wglGetProcAddress(name);
    if (!p || p == (void *)1 || p == (void *)2 || p == (void *)3 || p == (void *)-1) {
        HMODULE mod = GetModuleHandleA("opengl32.dll");
        if (mod) p = (void *)GetProcAddress(mod, name);
    }
    return p;
}

typedef void  (APIENTRY *PFN_GENFB)(GLsizei, GLuint *);
typedef void  (APIENTRY *PFN_BINDFB)(GLenum, GLuint);
typedef void  (APIENTRY *PFN_DELFB)(GLsizei, const GLuint *);
typedef void  (APIENTRY *PFN_GENRB)(GLsizei, GLuint *);
typedef void  (APIENTRY *PFN_BINDRB)(GLenum, GLuint);
typedef void  (APIENTRY *PFN_DELRB)(GLsizei, const GLuint *);
typedef void  (APIENTRY *PFN_RBSTORAGE)(GLenum, GLenum, GLsizei, GLsizei);
typedef void  (APIENTRY *PFN_FBRB)(GLenum, GLenum, GLenum, GLuint);
typedef GLenum(APIENTRY *PFN_CHECKFB)(GLenum);

static PFN_GENFB     p_GenFB;
static PFN_BINDFB    p_BindFB;
static PFN_DELFB     p_DelFB;
static PFN_GENRB     p_GenRB;
static PFN_BINDRB    p_BindRB;
static PFN_DELRB     p_DelRB;
static PFN_RBSTORAGE p_RBStorage;
static PFN_FBRB      p_FBRB;
static PFN_CHECKFB   p_CheckFB;
static int s_fbo_funcs_ready = 0;
static void (*real_glBindFramebuffer)(unsigned int, unsigned int);

typedef void  (APIENTRY *PFN_GENBUF)(GLsizei, GLuint *);
typedef void  (APIENTRY *PFN_BINDBUF)(GLenum, GLuint);
typedef void  (APIENTRY *PFN_BUFDATA)(GLenum, ptrdiff_t, const void *, GLenum);
typedef void  (APIENTRY *PFN_DELBUF)(GLsizei, const GLuint *);
typedef void *(APIENTRY *PFN_MAPBUF)(GLenum, GLenum);
typedef GLboolean (APIENTRY *PFN_UNMAPBUF)(GLenum);

static PFN_GENBUF   p_GenBuf;
static PFN_BINDBUF  p_BindBuf;
static PFN_BUFDATA  p_BufData;
static PFN_DELBUF   p_DelBuf;
static PFN_MAPBUF   p_MapBuf;
static PFN_UNMAPBUF p_UnmapBuf;
static int s_pbo_funcs_ready = 0;

static int ensure_fbo_funcs(void) {
    if (s_fbo_funcs_ready) return 1;
    EnterCriticalSection(&s_global_cs);
    if (!s_fbo_funcs_ready) {
        p_GenFB     = (PFN_GENFB)    load_gl("glGenFramebuffers");
        p_BindFB    = (PFN_BINDFB)   load_gl("glBindFramebuffer");
        p_DelFB     = (PFN_DELFB)    load_gl("glDeleteFramebuffers");
        p_GenRB     = (PFN_GENRB)    load_gl("glGenRenderbuffers");
        p_BindRB    = (PFN_BINDRB)   load_gl("glBindRenderbuffer");
        p_DelRB     = (PFN_DELRB)    load_gl("glDeleteRenderbuffers");
        p_RBStorage = (PFN_RBSTORAGE)load_gl("glRenderbufferStorage");
        p_FBRB      = (PFN_FBRB)     load_gl("glFramebufferRenderbuffer");
        p_CheckFB   = (PFN_CHECKFB)  load_gl("glCheckFramebufferStatus");
        s_fbo_funcs_ready = (p_GenFB && p_BindFB && p_DelFB && p_GenRB && p_BindRB &&
                             p_DelRB && p_RBStorage && p_FBRB && p_CheckFB) ? 1 : 0;
        if (!s_fbo_funcs_ready)
            fprintf(stderr, "[hlg-win] FBO functions unavailable — falling back to back buffer\n");
    }
    LeaveCriticalSection(&s_global_cs);
    return s_fbo_funcs_ready;
}

static int ensure_pbo_funcs(void) {
    if (s_pbo_funcs_ready) return 1;
    EnterCriticalSection(&s_global_cs);
    if (!s_pbo_funcs_ready) {
        p_GenBuf   = (PFN_GENBUF)  load_gl("glGenBuffers");
        p_BindBuf  = (PFN_BINDBUF) load_gl("glBindBuffer");
        p_BufData  = (PFN_BUFDATA) load_gl("glBufferData");
        p_DelBuf   = (PFN_DELBUF)  load_gl("glDeleteBuffers");
        p_MapBuf   = (PFN_MAPBUF)  load_gl("glMapBuffer");
        p_UnmapBuf = (PFN_UNMAPBUF)load_gl("glUnmapBuffer");
        s_pbo_funcs_ready = (p_GenBuf && p_BindBuf && p_BufData && p_DelBuf &&
                             p_MapBuf && p_UnmapBuf) ? 1 : 0;
        if (!s_pbo_funcs_ready)
            fprintf(stderr, "[hlg-win] PBO functions unavailable — sync readback\n");
    }
    LeaveCriticalSection(&s_global_cs);
    return s_pbo_funcs_ready;
}

/* Offscreen FBO (per-instance). */
static int create_offscreen_fbo(hlg_ctx *c, int w, int h) {
    if (!ensure_fbo_funcs()) return 0;
    if (!c->fbo)      p_GenFB(1, &c->fbo);
    if (!c->color_rb) p_GenRB(1, &c->color_rb);
    if (!c->depth_rb) p_GenRB(1, &c->depth_rb);

    p_BindFB(HLG_GL_FRAMEBUFFER, c->fbo);

    p_BindRB(HLG_GL_RENDERBUFFER, c->color_rb);
    p_RBStorage(HLG_GL_RENDERBUFFER, HLG_GL_RGBA8, w, h);
    p_FBRB(HLG_GL_FRAMEBUFFER, HLG_GL_COLOR_ATTACHMENT0, HLG_GL_RENDERBUFFER, c->color_rb);

    p_BindRB(HLG_GL_RENDERBUFFER, c->depth_rb);
    p_RBStorage(HLG_GL_RENDERBUFFER, HLG_GL_DEPTH24_STENCIL8, w, h);
    p_FBRB(HLG_GL_FRAMEBUFFER, HLG_GL_DEPTH_STENCIL_ATTACHMENT, HLG_GL_RENDERBUFFER, c->depth_rb);

    GLenum st = p_CheckFB(HLG_GL_FRAMEBUFFER);
    p_BindFB(HLG_GL_FRAMEBUFFER, c->fbo);
    c->fbo_ready = (st == HLG_GL_FRAMEBUFFER_COMPLETE);
    if (!c->fbo_ready)
        fprintf(stderr, "[hlg-win] FBO incomplete (0x%x) — using back buffer\n", st);
    else
        fprintf(stderr, "[hlg-win] ctx %p: offscreen FBO %u ready (%dx%d)\n",
                (void *)c, c->fbo, w, h);
    return c->fbo_ready;
}

static void resize_offscreen_fbo(hlg_ctx *c, int w, int h) {
    if (!c->fbo_ready) return;
    p_BindRB(HLG_GL_RENDERBUFFER, c->color_rb);
    p_RBStorage(HLG_GL_RENDERBUFFER, HLG_GL_RGBA8, w, h);
    p_BindRB(HLG_GL_RENDERBUFFER, c->depth_rb);
    p_RBStorage(HLG_GL_RENDERBUFFER, HLG_GL_DEPTH24_STENCIL8, w, h);
    p_BindFB(HLG_GL_FRAMEBUFFER, c->fbo);
}

static void destroy_offscreen_fbo(hlg_ctx *c) {
    if (!s_fbo_funcs_ready) { c->fbo = c->color_rb = c->depth_rb = 0; c->fbo_ready = 0; return; }
    if (c->color_rb) p_DelRB(1, &c->color_rb);
    if (c->depth_rb) p_DelRB(1, &c->depth_rb);
    if (c->fbo)      p_DelFB(1, &c->fbo);
    c->fbo = c->color_rb = c->depth_rb = 0;
    c->fbo_ready = 0;
}

static void destroy_pbos(hlg_ctx *c) {
    if (!s_pbo_funcs_ready || !c->pbo[0]) {
        c->pbo[0] = c->pbo[1] = 0;
        c->pbo_w[0] = c->pbo_w[1] = 0;
        c->pbo_h[0] = c->pbo_h[1] = 0;
        c->pbo_cap_w = c->pbo_cap_h = 0;
        c->pbo_idx = 0;
        return;
    }
    p_DelBuf(2, c->pbo);
    c->pbo[0] = c->pbo[1] = 0;
    c->pbo_w[0] = c->pbo_w[1] = 0;
    c->pbo_h[0] = c->pbo_h[1] = 0;
    c->pbo_cap_w = c->pbo_cap_h = 0;
    c->pbo_idx = 0;
}

static int ensure_pbo_buffers(hlg_ctx *c, int w, int h) {
    if (!ensure_pbo_funcs() || w <= 0 || h <= 0) return 0;
    if (!c->pbo[0]) {
        p_GenBuf(2, c->pbo);
        c->pbo_idx = 0;
        c->pbo_w[0] = c->pbo_w[1] = 0;
        c->pbo_h[0] = c->pbo_h[1] = 0;
        c->pbo_cap_w = c->pbo_cap_h = 0;
    }
    if (w > c->pbo_cap_w || h > c->pbo_cap_h) {
        size_t bytes = (size_t)w * (size_t)h * 4;
        for (int i = 0; i < 2; ++i) {
            p_BindBuf(HLG_GL_PIXEL_PACK_BUFFER, c->pbo[i]);
            p_BufData(HLG_GL_PIXEL_PACK_BUFFER, (ptrdiff_t)bytes, NULL, HLG_GL_STREAM_READ);
        }
        p_BindBuf(HLG_GL_PIXEL_PACK_BUFFER, 0);
        c->pbo_cap_w = w;
        c->pbo_cap_h = h;
        c->pbo_w[0] = c->pbo_w[1] = 0;
        c->pbo_h[0] = c->pbo_h[1] = 0;
        c->pbo_idx = 0;
    }
    return 1;
}

static void copy_flipped_bgra(void *dst, const void *src, int w, int h) {
    const unsigned char *s = (const unsigned char *)src;
    unsigned char *d = (unsigned char *)dst;
    size_t stride = (size_t)w * 4;
    for (int y = 0; y < h; ++y)
        memcpy(d + (size_t)y * stride, s + (size_t)(h - 1 - y) * stride, stride);
}

static void flip_rows_inplace(unsigned char *px, int w, int h) {
    size_t stride = (size_t)w * 4;
    unsigned char *tmp = (unsigned char *)malloc(stride);
    if (!tmp) return;
    for (int y = 0; y < h / 2; ++y) {
        unsigned char *a = px + (size_t)y * stride;
        unsigned char *b = px + (size_t)(h - 1 - y) * stride;
        memcpy(tmp, a, stride);
        memcpy(a, b, stride);
        memcpy(b, tmp, stride);
    }
    free(tmp);
}

/* VEH isolation (Flycast/PCSX2), crash forensics, PCSX2 shmem hook. */
static LPTOP_LEVEL_EXCEPTION_FILTER s_jvm_filter = NULL;
typedef PVOID   (WINAPI *AddVEH_t)(ULONG, PVECTORED_EXCEPTION_HANDLER);
typedef ULONG   (WINAPI *RemoveVEH_t)(PVOID);

static void *s_add_fn = NULL;   static BYTE s_add_orig[14];
static void *s_rem_fn = NULL;   static BYTE s_rem_orig[14];

static CRITICAL_SECTION s_veh_cs;
static int   s_veh_hooked = 0;

/* Flycast: core_* = BASE..ARAM; route_* includes DC mirrors for should_route. */
typedef struct {
    PVECTORED_EXCEPTION_HANDLER h;
    HMODULE mod;
    ULONG_PTR core_lo, core_hi;
    ULONG_PTR route_lo, route_hi;
    int used;
} fly_entry;
static fly_entry s_fly[8];
static PVOID s_dispatcher = NULL;
static int   s_dispatch_log_left = 5;
static HLG_TLS int t_fly_slot = -1;
static HLG_TLS ULONG_PTR t_pending_core_lo = 0, t_pending_core_hi = 0;
static HLG_TLS ULONG_PTR t_pending_route_lo = 0, t_pending_route_hi = 0;

typedef struct {
    PVECTORED_EXCEPTION_HANDLER h;
    HMODULE mod;
    ULONG_PTR lo, hi;
    unsigned gen;
    int used;
} ps2_entry;
static ps2_entry s_ps2[8];
static HLG_TLS int t_ps2_slot = -1;
static HLG_TLS ULONG_PTR t_pending_ps2_lo = 0, t_pending_ps2_hi = 0;

static HLG_TLS int      t_bound_ps2 = -1;
static HLG_TLS unsigned t_bound_gen = 0;
static HLG_TLS int      t_in_dispatch = 0;

#define HLG_AV_RING 256
enum {
    AV_FASTMEM = 0,
    AV_BOUND = 1,
    AV_TRYALL = 2,
    AV_FLYCAST = 3,
    AV_NESTED_SKIP = 4
};
typedef struct {
    DWORD tid;
    void *rip;
    void *addr;
    int   slot;
    LONG  result;
    int   kind;
} hlg_av_rec;
static hlg_av_rec    s_av_ring[HLG_AV_RING];
static volatile LONG s_av_seq = 0;

static void av_record(void *rip, void *addr, int slot, LONG result, int kind)
{
    LONG i = InterlockedIncrement(&s_av_seq) - 1;
    hlg_av_rec *r = &s_av_ring[i & (HLG_AV_RING - 1)];
    r->tid    = GetCurrentThreadId();
    r->rip    = rip;
    r->addr   = addr;
    r->slot   = slot;
    r->result = result;
    r->kind   = kind;
}

static void hlg_dump_av_ring(void);

/* stderr + hlg_crash_pidN.txt (Gradle pipe may drop stderr on instant exit). */
static HANDLE s_clog = INVALID_HANDLE_VALUE;

static void hlg_clog(const char *fmt, ...)
{
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (n <= 0) return;
    if (n > (int)sizeof(buf) - 1) n = (int)sizeof(buf) - 1;
    fwrite(buf, 1, (size_t)n, stderr);
    fflush(stderr);
    if (s_clog == INVALID_HANDLE_VALUE) {
        char path[MAX_PATH];
        snprintf(path, sizeof(path), "hlg_crash_pid%lu.txt",
                 (unsigned long)GetCurrentProcessId());
        s_clog = CreateFileA(path, FILE_APPEND_DATA, FILE_SHARE_READ, NULL,
                             OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    }
    if (s_clog != INVALID_HANDLE_VALUE) {
        DWORD w;
        WriteFile(s_clog, buf, (DWORD)n, &w, NULL);
        FlushFileBuffers(s_clog);
    }
}

static void hlg_dump_av_ring(void)
{
    static const char *kinds[] =
        { "fastmem", "bound", "tryall", "flycast", "nested-skip" };
    LONG total = s_av_seq;
    LONG count = total < HLG_AV_RING ? total : HLG_AV_RING;
    hlg_clog("[hlg-win] ==== AV ring: %ld dispatches total, last %ld ====\n",
            (long)total, (long)count);
    for (LONG i = total - count; i < total; ++i) {
        hlg_av_rec *r = &s_av_ring[i & (HLG_AV_RING - 1)];
        hlg_clog("[hlg-win]  #%ld tid=%lu rip=%p addr=%p slot=%d %s (%s)\n",
                (long)i, (unsigned long)r->tid, r->rip, r->addr, r->slot,
                r->result == EXCEPTION_CONTINUE_EXECUTION ? "CONT" : "SEARCH",
                (r->kind >= 0 && r->kind <= 4) ? kinds[r->kind] : "?");
    }
    for (int i = 0; i < 8; ++i) {
        if (!s_ps2[i].used) continue;
        hlg_clog("[hlg-win]  ps2 slot=%d mod=%p fastmem=%p-%p gen=%u\n",
                i, (void *)s_ps2[i].mod, (void *)s_ps2[i].lo,
                (void *)s_ps2[i].hi, s_ps2[i].gen);
    }
    fflush(stderr);
}

/* Minidump + crash UEF (dbghelp loaded lazily). */
typedef struct {
    DWORD ThreadId;
    PEXCEPTION_POINTERS ExceptionPointers;
    BOOL ClientPointers;
} hlg_minidump_exc_info;

typedef BOOL (WINAPI *MiniDumpWriteDump_t)(HANDLE, DWORD, HANDLE, int,
                                           void *, void *, void *);

typedef struct {
    PEXCEPTION_POINTERS ep;
    DWORD tid;
} hlg_md_ctx;

/* Dumper thread parked on event — no CreateThread from crash UEF. */
static HANDLE s_md_go = NULL, s_md_done = NULL;
static hlg_md_ctx s_md_ctx;

static DWORD WINAPI hlg_minidump_thread(LPVOID arg)
{
    (void)arg;
    for (;;) {
        if (WaitForSingleObject(s_md_go, INFINITE) != WAIT_OBJECT_0) return 0;
        HMODULE dbg = LoadLibraryA("dbghelp.dll");
        MiniDumpWriteDump_t mdwd = dbg
            ? (MiniDumpWriteDump_t)GetProcAddress(dbg, "MiniDumpWriteDump") : NULL;
        if (!mdwd) { hlg_clog("[hlg-win] dbghelp unavailable\n"); SetEvent(s_md_done); continue; }
        char path[MAX_PATH];
        snprintf(path, sizeof(path), "hlg_crash_pid%lu.dmp",
                 (unsigned long)GetCurrentProcessId());
        HANDLE f = CreateFileA(path, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS,
                               FILE_ATTRIBUTE_NORMAL, NULL);
        if (f == INVALID_HANDLE_VALUE) { SetEvent(s_md_done); continue; }
        hlg_minidump_exc_info mei;
        mei.ThreadId = s_md_ctx.tid;
        mei.ExceptionPointers = s_md_ctx.ep;
        mei.ClientPointers = FALSE;
        /* MiniDumpWithDataSegs | MiniDumpWithIndirectlyReferencedMemory */
        BOOL ok = mdwd(GetCurrentProcess(), GetCurrentProcessId(), f,
                       0x1 | 0x40, &mei, NULL, NULL);
        if (!ok) {
            hlg_clog("[hlg-win] minidump extended FAILED (err=%08lX), retry normal\n",
                     GetLastError());
            SetFilePointer(f, 0, NULL, FILE_BEGIN);
            SetEndOfFile(f);
            ok = mdwd(GetCurrentProcess(), GetCurrentProcessId(), f,
                      0x0, &mei, NULL, NULL);
            if (!ok)
                hlg_clog("[hlg-win] minidump normal FAILED (err=%08lX)\n",
                         GetLastError());
        }
        CloseHandle(f);
        hlg_clog("[hlg-win] minidump %s: %s\n", path, ok ? "written" : "FAILED");
        SetEvent(s_md_done);
    }
}

static void hlg_write_minidump(PEXCEPTION_POINTERS ep)
{
    if (!s_md_go || !s_md_done) { hlg_clog("[hlg-win] dumper not ready\n"); return; }
    s_md_ctx.ep = ep;
    s_md_ctx.tid = GetCurrentThreadId();
    SetEvent(s_md_go);
    WaitForSingleObject(s_md_done, 30000);
}

typedef BOOL (WINAPI *EnumMods_t)(HANDLE, HMODULE *, DWORD, LPDWORD);
typedef struct { LPVOID base; DWORD size; LPVOID entry; } hlg_modinfo;
typedef BOOL (WINAPI *GetModInfo_t)(HANDLE, HMODULE, hlg_modinfo *, DWORD);

static void hlg_dump_modules(const char *tag)
{
    HMODULE k32 = GetModuleHandleA("kernel32.dll");
    EnumMods_t em = k32 ? (EnumMods_t)GetProcAddress(k32, "K32EnumProcessModules") : NULL;
    GetModInfo_t gmi = k32 ? (GetModInfo_t)GetProcAddress(k32, "K32GetModuleInformation") : NULL;
    if (!em || !gmi) return;
    static HMODULE mods[1024];
    DWORD need = 0;
    if (!em(GetCurrentProcess(), mods, sizeof(mods), &need)) return;
    DWORD n = need / sizeof(HMODULE);
    if (n > 1024) n = 1024;
    hlg_clog("[hlg-win] ==== module map (%s): %lu modules ====\n",
             tag, (unsigned long)n);
    for (DWORD i = 0; i < n; ++i) {
        hlg_modinfo mi = { 0 };
        char name[MAX_PATH] = "?";
        if (!gmi(GetCurrentProcess(), mods[i], &mi, sizeof(mi))) continue;
        GetModuleFileNameA(mods[i], name, MAX_PATH);
        hlg_clog("[hlg-win]  mod %p-%p %s\n", mi.base,
                 (void *)((ULONG_PTR)mi.base + mi.size), name);
    }
}

static LONG WINAPI hlg_crash_uef(EXCEPTION_POINTERS *ep)
{
    static volatile LONG once = 0;
    DWORD code = (ep && ep->ExceptionRecord) ? ep->ExceptionRecord->ExceptionCode : 0;
    ULONG_PTR fault_addr = 0;
    if (code == EXCEPTION_ACCESS_VIOLATION
            && ep->ExceptionRecord->NumberParameters >= 2)
        fault_addr = (ULONG_PTR)ep->ExceptionRecord->ExceptionInformation[1];

    /* HotSpot NPE: skip one-shot dump; JVM filter handles it. */
    if (code == EXCEPTION_ACCESS_VIOLATION && fault_addr < 0x10000
            && s_jvm_filter
            && s_jvm_filter != (LPTOP_LEVEL_EXCEPTION_FILTER)hlg_crash_uef)
        return s_jvm_filter(ep);

    if (InterlockedExchange(&once, 1) == 0) {
        void *xip = (ep && ep->ExceptionRecord)
                  ? ep->ExceptionRecord->ExceptionAddress : NULL;
        hlg_clog("[hlg-win] FATAL: unhandled exception code=%08lX addr=%p tid=%lu\n",
                code, xip, GetCurrentThreadId());
        {
            HMODULE m = NULL;
            char name[MAX_PATH];
            if (xip)
                GetModuleHandleExA(0x4 /*FROM_ADDRESS*/ | 0x2 /*UNCHANGED_REFCOUNT*/,
                                   (LPCSTR)xip, &m);
            if (m && GetModuleFileNameA(m, name, MAX_PATH))
                hlg_clog("[hlg-win] FATAL rip in %s +0x%llX\n", name,
                        (unsigned long long)((ULONG_PTR)xip - (ULONG_PTR)m));
            else
                hlg_clog("[hlg-win] FATAL rip not in any module (JIT/heap?)\n");
        }
        hlg_dump_modules("fatal");
        hlg_dump_av_ring();
        hlg_write_minidump(ep);
    }
    if (s_jvm_filter && s_jvm_filter != (LPTOP_LEVEL_EXCEPTION_FILTER)hlg_crash_uef) {
        LONG r = s_jvm_filter(ep);
        if (r == EXCEPTION_CONTINUE_EXECUTION)
            InterlockedExchange(&once, 0);
        return r;
    }
    return EXCEPTION_CONTINUE_SEARCH;
}

static PVOID WINAPI hook_AddVEH(ULONG First, PVECTORED_EXCEPTION_HANDLER Handler);
static ULONG WINAPI hook_RemoveVEH(PVOID Handle);
static LONG CALLBACK hlg_dispatch_veh(PEXCEPTION_POINTERS ep);

static HMODULE module_of(void *p)
{
    HMODULE hm = NULL;
    if (!p) return NULL;
    GetModuleHandleExA(0x4 /*FROM_ADDRESS*/ | 0x2 /*UNCHANGED_REFCOUNT*/,
                       (LPCSTR)p, &hm);
    return hm;
}

static LPTOP_LEVEL_EXCEPTION_FILTER peek_uef(void)
{
    LPTOP_LEVEL_EXCEPTION_FILTER cur = SetUnhandledExceptionFilter(NULL);
    SetUnhandledExceptionFilter(cur);
    return cur;
}

static void write_jmp(void *at, void *target)
{
    BYTE patch[14] = { 0xFF, 0x25, 0,0,0,0 };
    memcpy(patch + 6, &target, sizeof(target));
    DWORD old;
    VirtualProtect(at, sizeof(patch), PAGE_EXECUTE_READWRITE, &old);
    memcpy(at, patch, sizeof(patch));
    VirtualProtect(at, sizeof(patch), old, &old);
    FlushInstructionCache(GetCurrentProcess(), at, sizeof(patch));
}

static PVOID real_add_veh(ULONG First, PVECTORED_EXCEPTION_HANDLER H)
{
    DWORD old;
    VirtualProtect(s_add_fn, 14, PAGE_EXECUTE_READWRITE, &old);
    memcpy(s_add_fn, s_add_orig, 14);
    FlushInstructionCache(GetCurrentProcess(), s_add_fn, 14);
    PVOID h = ((AddVEH_t)s_add_fn)(First, H);
    write_jmp(s_add_fn, (void *)hook_AddVEH);
    VirtualProtect(s_add_fn, 14, old, &old);
    return h;
}

static ULONG real_remove_veh(PVOID Handle)
{
    DWORD old;
    VirtualProtect(s_rem_fn, 14, PAGE_EXECUTE_READWRITE, &old);
    memcpy(s_rem_fn, s_rem_orig, 14);
    FlushInstructionCache(GetCurrentProcess(), s_rem_fn, 14);
    ULONG r = ((RemoveVEH_t)s_rem_fn)(Handle);
    write_jmp(s_rem_fn, (void *)hook_RemoveVEH);
    VirtualProtect(s_rem_fn, 14, old, &old);
    return r;
}

/* PCSX2: pcsx2_<pid> shmem name -> pcsx2_<pid>_sN per .slotN.dll (do not patch "pcsx2" in DLL). */
typedef HANDLE (WINAPI *CreateFileMappingW_t)(HANDLE, LPSECURITY_ATTRIBUTES,
                                             DWORD, DWORD, DWORD, LPCWSTR);
static void *s_cfm_fn = NULL;
static BYTE s_cfm_orig[14];
static int  s_cfm_hooked = 0;
static int  s_cfm_log_left = 4;

static HLG_TLS wchar_t t_cfm_rewritten[160];

static HANDLE WINAPI hook_CreateFileMappingW(HANDLE, LPSECURITY_ATTRIBUTES,
        DWORD, DWORD, DWORD, LPCWSTR);

static int pcsx2_slot_from_module(HMODULE hm)
{
    char path[MAX_PATH];
    if (!hm || !GetModuleFileNameA(hm, path, MAX_PATH))
        return -1;
    for (char *p = path; *p; ++p)
        *p = (char)tolower((unsigned char)*p);
    if (!strstr(path, "pcsx2"))
        return -1;
    const char *sl = strstr(path, ".slot");
    if (!sl)
        return 0;
    int n = atoi(sl + 5);
    return (n >= 0 && n < 8) ? n : -1;
}

static int pcsx2_slot_from_rip(void *rip)
{
    return pcsx2_slot_from_module(module_of(rip));
}

static int wcs_ends_with_slot_tag(LPCWSTR name)
{
    size_t len = wcslen(name);
    if (len < 4)
        return 0;
    if (name[len - 3] != L'_' || name[len - 2] != L's')
        return 0;
    wchar_t c = name[len - 1];
    return c >= L'0' && c <= L'7';
}

static HANDLE real_create_file_mapping_w(HANDLE hFile, LPSECURITY_ATTRIBUTES sa,
        DWORD prot, DWORD hi, DWORD lo, LPCWSTR name)
{
    DWORD old;
    VirtualProtect(s_cfm_fn, 14, PAGE_EXECUTE_READWRITE, &old);
    memcpy(s_cfm_fn, s_cfm_orig, 14);
    FlushInstructionCache(GetCurrentProcess(), s_cfm_fn, 14);
    HANDLE h = ((CreateFileMappingW_t)s_cfm_fn)(hFile, sa, prot, hi, lo, name);
    write_jmp(s_cfm_fn, (void *)hook_CreateFileMappingW);
    VirtualProtect(s_cfm_fn, 14, old, &old);
    return h;
}

static HANDLE WINAPI hook_CreateFileMappingW(HANDLE hFile, LPSECURITY_ATTRIBUTES sa,
        DWORD prot, DWORD hi, DWORD lo, LPCWSTR lpName)
{
    LPCWSTR use = lpName;
#if defined(__GNUC__) || defined(__clang__)
    void *ret = __builtin_return_address(0);
#else
    void *ret = _ReturnAddress();
#endif
    if (lpName && lpName[0] && wcsncmp(lpName, L"pcsx2_", 6) == 0
            && !wcs_ends_with_slot_tag(lpName)) {
        int slot = pcsx2_slot_from_rip(ret);
        if (slot >= 0) {
            if (swprintf(t_cfm_rewritten, 160, L"%s_s%d", lpName, slot) > 0)
                use = t_cfm_rewritten;
            if (s_cfm_log_left > 0) {
                fprintf(stderr, "[hlg-win] PS2 shmem slot=%d name rewritten\n", slot);
                fflush(stderr);
                s_cfm_log_left--;
            }
        }
    }
    return real_create_file_mapping_w(hFile, sa, prot, hi, lo, use);
}

static void hook_create_file_mapping(void)
{
    if (s_cfm_hooked)
        return;
    HMODULE k32 = GetModuleHandleA("kernel32.dll");
    void *cfm = k32 ? (void *)GetProcAddress(k32, "CreateFileMappingW") : NULL;
    if (!cfm) {
        fprintf(stderr, "[hlg-win] CreateFileMappingW not found\n");
        return;
    }
    s_cfm_fn = cfm;
    memcpy(s_cfm_orig, cfm, 14);
    write_jmp(cfm, (void *)hook_CreateFileMappingW);
    s_cfm_hooked = 1;
    fprintf(stderr, "[hlg-win] CreateFileMappingW hooked (PS2 shmem uniquify)\n");
}

/** Flycast VEH/AV nvmem — isolate handlers from JVM chain. */
static int is_isolated_core_addr(void *p)
{
    HMODULE hm = NULL;
    if (GetModuleHandleExA(0x4 | 0x2, (LPCSTR)p, &hm) && hm) {
        char name[MAX_PATH];
        if (GetModuleFileNameA(hm, name, MAX_PATH)) {
            for (char *s = name; *s; ++s) *s = (char)tolower((unsigned char)*s);
            if (strstr(name, "flycast")) return 1;
        }
    }
    return 0;
}

static int is_pcsx2_core_addr(void *p)
{
    HMODULE hm = NULL;
    if (GetModuleHandleExA(0x4 | 0x2, (LPCSTR)p, &hm) && hm) {
        char name[MAX_PATH];
        if (GetModuleFileNameA(hm, name, MAX_PATH)) {
            for (char *s = name; *s; ++s) *s = (char)tolower((unsigned char)*s);
            if (strstr(name, "pcsx2")) return 1;
        }
    }
    return 0;
}

static void stamp_ps2_bounds(HMODULE m, ULONG_PTR lo, ULONG_PTR hi)
{
    if (!m) return;
    for (int i = 0; i < 8; ++i) {
        if (!s_ps2[i].used) continue;
        if (s_ps2[i].mod == m) {
            s_ps2[i].lo = lo;
            s_ps2[i].hi = hi;
        }
    }
}

static void assign_ps2_bounds(ULONG_PTR lo, ULONG_PTR hi, const char *tag)
{
    if (!(lo < hi)) return;
    int slot = -1;
    EnterCriticalSection(&s_veh_cs);
    /* Fastmem may log before AddVEH — use pending bounds until slot registers. */
    if (t_ps2_slot >= 0 && t_ps2_slot < 8 && s_ps2[t_ps2_slot].used) {
        slot = t_ps2_slot;
    } else {
        for (int i = 7; i >= 0; --i) {
            if (s_ps2[i].used && !(s_ps2[i].lo < s_ps2[i].hi)) {
                slot = i;
                break;
            }
        }
    }
    if (slot >= 0) {
        HMODULE m = s_ps2[slot].mod;
        if (m) stamp_ps2_bounds(m, lo, hi);
        else {
            s_ps2[slot].lo = lo;
            s_ps2[slot].hi = hi;
        }
        LeaveCriticalSection(&s_veh_cs);
        fprintf(stderr, "[hlg-win] %s bounds slot=%d mod=%p range=%p-%p\n",
                tag, slot, (void *)m, (void *)lo, (void *)hi);
    } else {
        LeaveCriticalSection(&s_veh_cs);
        t_pending_ps2_lo = lo;
        t_pending_ps2_hi = hi;
        fprintf(stderr, "[hlg-win] %s bounds PENDING: %p-%p\n",
                tag, (void *)lo, (void *)hi);
    }
    fflush(stderr);
}

static int is_isolated_core_ctx(PEXCEPTION_POINTERS ep)
{
    if (!ep || !ep->ContextRecord) return 0;
#if defined(_M_X64) || defined(__x86_64__)
    return is_isolated_core_addr((void *)ep->ContextRecord->Rip);
#elif defined(_M_IX86) || defined(__i386__)
    return is_isolated_core_addr((void *)ep->ContextRecord->Eip);
#else
    return 0;
#endif
}

static void stamp_bounds(int slot, ULONG_PTR core_lo, ULONG_PTR core_hi,
                         ULONG_PTR route_lo, ULONG_PTR route_hi)
{
    HMODULE m = s_fly[slot].mod;
    for (int i = 0; i < 8; ++i) {
        if (!s_fly[i].used) continue;
        if (i == slot || (m && s_fly[i].mod == m)) {
            s_fly[i].core_lo = core_lo;
            s_fly[i].core_hi = core_hi;
            s_fly[i].route_lo = route_lo;
            s_fly[i].route_hi = route_hi;
        }
    }
}

static void assign_slot_bounds(ULONG_PTR core_lo, ULONG_PTR core_hi,
                               ULONG_PTR route_lo, ULONG_PTR route_hi, const char *tag)
{
    if (!(core_lo < core_hi) || !(route_lo < route_hi)) return;
    int slot = -1;
    EnterCriticalSection(&s_veh_cs);
    if (t_fly_slot >= 0 && t_fly_slot < 8 && s_fly[t_fly_slot].used) {
        slot = t_fly_slot;
    } else {
        for (int i = 7; i >= 0; --i) {
            if (s_fly[i].used && !(s_fly[i].core_lo < s_fly[i].core_hi)) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            for (int i = 7; i >= 0; --i) {
                if (s_fly[i].used) { slot = i; break; }
            }
        }
    }
    if (slot >= 0) {
        HMODULE m = s_fly[slot].mod;
        stamp_bounds(slot, core_lo, core_hi, route_lo, route_hi);
        LeaveCriticalSection(&s_veh_cs);
        fprintf(stderr, "[hlg-win] %s bounds slot=%d mod=%p core=%p-%p route=%p-%p\n",
                tag, slot, (void *)m, (void *)core_lo, (void *)core_hi,
                (void *)route_lo, (void *)route_hi);
    } else {
        LeaveCriticalSection(&s_veh_cs);
        t_pending_core_lo = core_lo; t_pending_core_hi = core_hi;
        t_pending_route_lo = route_lo; t_pending_route_hi = route_hi;
        fprintf(stderr, "[hlg-win] %s bounds PENDING: core=%p-%p\n",
                tag, (void *)core_lo, (void *)core_hi);
    }
    fflush(stderr);
}

static void parse_vmem_bounds(const char *msg)
{
    const char *fm = strstr(msg, "Fastmem area:");
    if (fm) {
        unsigned long long lo = 0, hi = 0;
        const char *p = fm + strlen("Fastmem area:");
        while (*p == ' ' || *p == '\t') ++p;
        if (sscanf(p, "0x%llx - 0x%llx", &lo, &hi) >= 2
                || sscanf(p, "%llx - %llx", &lo, &hi) >= 2) {
            assign_ps2_bounds((ULONG_PTR)lo, (ULONG_PTR)(hi + 1), "fastmem");
        }
        return;
    }

    const char *vmem = strstr(msg, "BASE ");
    if (!vmem) return;
    unsigned long long base = 0, ram = 0, vram = 0, aram = 0;
    unsigned ram_mb = 0, vram_mb = 0, aram_mb = 0;
    if (sscanf(vmem,
               "BASE %llx RAM(%u MB) %llx VRAM64(%u MB) %llx ARAM(%u MB) %llx",
               &base, &ram_mb, &ram, &vram_mb, &vram, &aram_mb, &aram) < 7)
        return;
    ULONG_PTR core_lo = (ULONG_PTR)base;
    ULONG_PTR core_hi = (ULONG_PTR)aram + (ULONG_PTR)aram_mb * 1024 * 1024;
    ULONG_PTR ram_end  = (ULONG_PTR)ram  + (ULONG_PTR)ram_mb  * 1024 * 1024;
    ULONG_PTR vram_end = (ULONG_PTR)vram + (ULONG_PTR)vram_mb * 1024 * 1024;
    if ((ULONG_PTR)ram  < core_lo) core_lo = (ULONG_PTR)ram;
    if ((ULONG_PTR)vram < core_lo) core_lo = (ULONG_PTR)vram;
    if ((ULONG_PTR)aram < core_lo) core_lo = (ULONG_PTR)aram;
    if (ram_end  > core_hi) core_hi = ram_end;
    if (vram_end > core_hi) core_hi = vram_end;
    core_hi += 0x100000ULL;

    ULONG_PTR route_lo = core_lo;
    ULONG_PTR route_hi = core_hi + 0x1000000ULL;
    if (route_lo > 0x81000000ULL) route_lo -= 0x81000000ULL;

    assign_slot_bounds(core_lo, core_hi, route_lo, route_hi, "nvmem");
}

/** True if addr falls in any live session's ROUTE range (lock held by caller). */
static int is_nvmem_addr_locked(ULONG_PTR addr)
{
    for (int i = 0; i < 8; ++i) {
        if (!s_fly[i].used) continue;
        if (s_fly[i].route_lo < s_fly[i].route_hi
                && addr >= s_fly[i].route_lo && addr < s_fly[i].route_hi)
            return 1;
    }
    return 0;
}

static int is_nvmem_addr(ULONG_PTR addr)
{
    EnterCriticalSection(&s_veh_cs);
    int hit = is_nvmem_addr_locked(addr);
    LeaveCriticalSection(&s_veh_cs);
    if (hit) return 1;
    /* Heuristic when BASE not logged yet. */
    if (addr < 0x00007FF400000000ULL || addr >= 0x00007FF500000000ULL)
        return 0;
    MEMORY_BASIC_INFORMATION mbi;
    if (VirtualQuery((LPCVOID)addr, &mbi, sizeof(mbi)) != sizeof(mbi))
        return 1;
    if (mbi.Type == MEM_MAPPED) return 1;
    if (mbi.State == MEM_FREE || mbi.State == MEM_RESERVE) return 1;
    return 0;
}

static int should_route_isolated(PEXCEPTION_POINTERS ep)
{
    if (is_isolated_core_ctx(ep)) return 1;
    ULONG_PTR addr = (ULONG_PTR)ep->ExceptionRecord->ExceptionInformation[1];
    return is_nvmem_addr(addr);
}

/** PS2 Fastmem owner; binds thread to slot on hit. */
static int dispatch_ps2_fastmem(PEXCEPTION_POINTERS ep, ULONG_PTR addr, LONG *out)
{
    PVECTORED_EXCEPTION_HANDLER h = NULL;
    int slot = -1;
    unsigned gen = 0;
    EnterCriticalSection(&s_veh_cs);
    for (int i = 0; i < 8; ++i) {
        if (!s_ps2[i].used || !s_ps2[i].h) continue;
        if (s_ps2[i].lo < s_ps2[i].hi
                && addr >= s_ps2[i].lo && addr < s_ps2[i].hi) {
            h = s_ps2[i].h;
            slot = i;
            gen = s_ps2[i].gen;
            break;
        }
    }
    LeaveCriticalSection(&s_veh_cs);
    if (!h) return 0;
    t_bound_ps2 = slot;
    t_bound_gen = gen;
    *out = h(ep);
    return 1;
}

/** PS2 outside Fastmem: bound slot, or one-time try-all with bind. */
static LONG dispatch_ps2_nonfastmem(PEXCEPTION_POINTERS ep, ULONG_PTR addr,
                                    void *rip, int nested)
{
    if (addr < 0x10000)
        return EXCEPTION_CONTINUE_SEARCH;

    PVECTORED_EXCEPTION_HANDLER h = NULL;
    int bound = t_bound_ps2;
    EnterCriticalSection(&s_veh_cs);
    if (bound >= 0 && bound < 8 && s_ps2[bound].used
            && s_ps2[bound].gen == t_bound_gen)
        h = s_ps2[bound].h;
    LeaveCriticalSection(&s_veh_cs);
    if (h) {
        LONG r = h(ep);
        av_record(rip, (void *)addr, bound, r, AV_BOUND);
        return r;
    }
    if (bound >= 0) t_bound_ps2 = -1;

    if (nested) {
        av_record(rip, (void *)addr, -1, EXCEPTION_CONTINUE_SEARCH, AV_NESTED_SKIP);
        return EXCEPTION_CONTINUE_SEARCH;
    }

    {
        HMODULE m = module_of(rip);
        if (m && !is_pcsx2_core_addr(rip))
            return EXCEPTION_CONTINUE_SEARCH;
    }

    PVECTORED_EXCEPTION_HANDLER hs[8];
    int slots[8];
    unsigned gens[8];
    int n = 0;
    EnterCriticalSection(&s_veh_cs);
    for (int i = 0; i < 8; ++i) {
        if (s_ps2[i].used && s_ps2[i].h) {
            hs[n] = s_ps2[i].h;
            slots[n] = i;
            gens[n] = s_ps2[i].gen;
            n++;
        }
    }
    LeaveCriticalSection(&s_veh_cs);
    for (int i = n - 1; i >= 0; --i) {
        LONG r = hs[i](ep);
        if (r == EXCEPTION_CONTINUE_EXECUTION) {
            t_bound_ps2 = slots[i];
            t_bound_gen = gens[i];
            av_record(rip, (void *)addr, slots[i], r, AV_TRYALL);
            return r;
        }
    }
    av_record(rip, (void *)addr, -1, EXCEPTION_CONTINUE_SEARCH, AV_TRYALL);
    return EXCEPTION_CONTINUE_SEARCH;
}

static int any_ps2_used(void)
{
    for (int i = 0; i < 8; ++i)
        if (s_ps2[i].used) return 1;
    return 0;
}

static LONG CALLBACK hlg_dispatch_veh(PEXCEPTION_POINTERS ep)
{
    EXCEPTION_RECORD *er = ep->ExceptionRecord;
    if (er->ExceptionCode != EXCEPTION_ACCESS_VIOLATION)
        return EXCEPTION_CONTINUE_SEARCH;
    ULONG_PTR addr = (ULONG_PTR)er->ExceptionInformation[1];
#if defined(_M_X64) || defined(__x86_64__)
    void *rip = (void *)ep->ContextRecord->Rip;
#else
    void *rip = (void *)ep->ContextRecord->Eip;
#endif

    int nested = t_in_dispatch > 0;
    t_in_dispatch++;
    LONG result = EXCEPTION_CONTINUE_SEARCH;

    if (s_dispatch_log_left > 0) {
        fprintf(stderr, "[hlg-win] AV dispatch: tid=%lu rip=%p addr=%p nested=%d\n",
                GetCurrentThreadId(), rip, (void *)addr, nested);
        fflush(stderr);
        s_dispatch_log_left--;
    }

    {
        LONG ps2r;
        if (dispatch_ps2_fastmem(ep, addr, &ps2r)) {
            av_record(rip, (void *)addr, t_bound_ps2, ps2r, AV_FASTMEM);
            result = ps2r;
            goto done;
        }
    }

    if (should_route_isolated(ep)) {
        HMODULE rip_mod = module_of(rip);
        PVECTORED_EXCEPTION_HANDLER same_mod[8];
        PVECTORED_EXCEPTION_HANDLER in_core[8];
        PVECTORED_EXCEPTION_HANDLER in_route[8];
        int n_same = 0, n_core = 0, n_route = 0;

        EnterCriticalSection(&s_veh_cs);
        for (int i = 0; i < 8; ++i) {
            if (!s_fly[i].used || !s_fly[i].h) continue;
            int same = rip_mod && s_fly[i].mod && s_fly[i].mod == rip_mod;
            int core = s_fly[i].core_lo < s_fly[i].core_hi
                    && addr >= s_fly[i].core_lo && addr < s_fly[i].core_hi;
            int routed = s_fly[i].route_lo < s_fly[i].route_hi
                    && addr >= s_fly[i].route_lo && addr < s_fly[i].route_hi;
            if (same) same_mod[n_same++] = s_fly[i].h;
            else if (core) in_core[n_core++] = s_fly[i].h;
            else if (routed) in_route[n_route++] = s_fly[i].h;
        }
        LeaveCriticalSection(&s_veh_cs);

        for (int i = n_same - 1; i >= 0; --i) {
            LONG r = same_mod[i](ep);
            if (r == EXCEPTION_CONTINUE_EXECUTION) {
                av_record(rip, (void *)addr, -1, r, AV_FLYCAST);
                result = r;
                goto done;
            }
        }
        for (int i = n_core - 1; i >= 0; --i) {
            LONG r = in_core[i](ep);
            if (r == EXCEPTION_CONTINUE_EXECUTION) {
                av_record(rip, (void *)addr, -1, r, AV_FLYCAST);
                result = r;
                goto done;
            }
        }
        for (int i = n_route - 1; i >= 0; --i) {
            LONG r = in_route[i](ep);
            if (r == EXCEPTION_CONTINUE_EXECUTION) {
                av_record(rip, (void *)addr, -1, r, AV_FLYCAST);
                result = r;
                goto done;
            }
        }
    }

    if (any_ps2_used())
        result = dispatch_ps2_nonfastmem(ep, addr, rip, nested);

done:
    t_in_dispatch--;
    return result;
}

static PVOID WINAPI hook_AddVEH(ULONG First, PVECTORED_EXCEPTION_HANDLER Handler)
{
    if (is_isolated_core_addr((void *)Handler)) {
        PVOID handle = NULL;
        int slot = -1;
        HMODULE mod = module_of((void *)Handler);
        EnterCriticalSection(&s_veh_cs);
        for (int i = 0; i < 8; ++i) {
            if (!s_fly[i].used) {
                s_fly[i].h = Handler;
                s_fly[i].mod = mod;
                s_fly[i].core_lo = s_fly[i].core_hi = 0;
                s_fly[i].route_lo = s_fly[i].route_hi = 0;
                s_fly[i].used = 1;
                handle = (PVOID)&s_fly[i];
                slot = i;
                break;
            }
        }
        if (!s_dispatcher)
            s_dispatcher = real_add_veh(1, hlg_dispatch_veh);
        if (slot >= 0 && t_pending_core_lo < t_pending_core_hi) {
            stamp_bounds(slot, t_pending_core_lo, t_pending_core_hi,
                         t_pending_route_lo, t_pending_route_hi);
            fprintf(stderr, "[hlg-win] applied PENDING bounds to slot=%d\n", slot);
            t_pending_core_lo = t_pending_core_hi = 0;
            t_pending_route_lo = t_pending_route_hi = 0;
        }
        LeaveCriticalSection(&s_veh_cs);
        if (slot >= 0) t_fly_slot = slot;
        if (!handle)
            fprintf(stderr, "[hlg-win] WARNING: AV-core table FULL — handler %p NOT registered!\n",
                    (void *)Handler);
        fprintf(stderr, "[hlg-win] AV-core VEH %p ISOLATED (slot=%d mod=%p handle=%p)\n",
                (void *)Handler, slot, (void *)mod, handle);
        fflush(stderr);
        return handle ? handle : (PVOID)Handler;
    }

    if (is_pcsx2_core_addr((void *)Handler)) {
        PVOID handle = NULL;
        int slot = -1;
        unsigned gen = 0;
        HMODULE mod = module_of((void *)Handler);
        EnterCriticalSection(&s_veh_cs);
        for (int i = 0; i < 8; ++i) {
            if (!s_ps2[i].used) {
                s_ps2[i].h = Handler;
                s_ps2[i].mod = mod;
                s_ps2[i].lo = s_ps2[i].hi = 0;
                s_ps2[i].gen++;
                s_ps2[i].used = 1;
                gen = s_ps2[i].gen;
                handle = (PVOID)&s_ps2[i];
                slot = i;
                break;
            }
        }
        if (!s_dispatcher)
            s_dispatcher = real_add_veh(1, hlg_dispatch_veh);
        if (slot >= 0 && t_pending_ps2_lo < t_pending_ps2_hi) {
            stamp_ps2_bounds(mod, t_pending_ps2_lo, t_pending_ps2_hi);
            fprintf(stderr, "[hlg-win] applied PENDING fastmem to ps2 slot=%d\n", slot);
            t_pending_ps2_lo = t_pending_ps2_hi = 0;
        }
        s_dispatch_log_left = 32;
        LeaveCriticalSection(&s_veh_cs);
        if (slot >= 0) {
            t_ps2_slot = slot;
            t_bound_ps2 = slot;
            t_bound_gen = gen;
        }
        if (!handle) {
            fprintf(stderr, "[hlg-win] WARNING: PS2 table FULL — passthrough %p\n",
                    (void *)Handler);
            fflush(stderr);
            EnterCriticalSection(&s_veh_cs);
            handle = real_add_veh(First, Handler);
            LeaveCriticalSection(&s_veh_cs);
            return handle;
        }
        fprintf(stderr, "[hlg-win] PS2 VEH %p ISOLATED (slot=%d gen=%u mod=%p handle=%p)\n",
                (void *)Handler, slot, gen, (void *)mod, handle);
        fflush(stderr);
        hlg_dump_modules("ps2 register");
        return handle;
    }

    EnterCriticalSection(&s_veh_cs);
    PVOID h = real_add_veh(First, Handler);
    LeaveCriticalSection(&s_veh_cs);
    return h;
}

static ULONG WINAPI hook_RemoveVEH(PVOID Handle)
{
    if ((ULONG_PTR)Handle >= (ULONG_PTR)&s_fly[0] &&
        (ULONG_PTR)Handle <= (ULONG_PTR)&s_fly[7]) {
        EnterCriticalSection(&s_veh_cs);
        fly_entry *e = (fly_entry *)Handle;
        int slot = (int)(e - s_fly);
        e->used = 0;
        e->h = NULL;
        e->mod = NULL;
        e->core_lo = e->core_hi = 0;
        e->route_lo = e->route_hi = 0;
        LeaveCriticalSection(&s_veh_cs);
        if (t_fly_slot == slot) t_fly_slot = -1;
        fprintf(stderr, "[hlg-win] AV-core VEH removed (slot=%d handle=%p)\n", slot, Handle);
        return 1;
    }

    if ((ULONG_PTR)Handle >= (ULONG_PTR)&s_ps2[0] &&
        (ULONG_PTR)Handle <= (ULONG_PTR)&s_ps2[7]) {
        EnterCriticalSection(&s_veh_cs);
        ps2_entry *e = (ps2_entry *)Handle;
        int slot = (int)(e - s_ps2);
        e->used = 0;
        e->h = NULL;
        e->mod = NULL;
        e->lo = e->hi = 0;
        e->gen++;
        LeaveCriticalSection(&s_veh_cs);
        if (t_ps2_slot == slot) t_ps2_slot = -1;
        if (t_bound_ps2 == slot) t_bound_ps2 = -1;
        fprintf(stderr, "[hlg-win] PS2 VEH removed (slot=%d handle=%p)\n", slot, Handle);
        fflush(stderr);
        return 1;
    }

    EnterCriticalSection(&s_veh_cs);
    ULONG r = real_remove_veh(Handle);
    LeaveCriticalSection(&s_veh_cs);
    return r;
}

__declspec(dllexport) void hlg_capture_jvm_filter(void)
{
    LPTOP_LEVEL_EXCEPTION_FILTER cur = peek_uef();
    if (cur != NULL && cur != (LPTOP_LEVEL_EXCEPTION_FILTER)hlg_crash_uef)
        s_jvm_filter = cur;
    fprintf(stderr, "[hlg-win] captured JVM UEF = %p\n", (void *)s_jvm_filter);
}

__declspec(dllexport) void hlg_hook_addveh(void)
{
    if (s_veh_hooked) return;
    HMODULE nt = GetModuleHandleA("ntdll.dll");
    if (!nt) { fprintf(stderr, "[hlg-win] ntdll not found\n"); return; }
    void *add = (void *)GetProcAddress(nt, "RtlAddVectoredExceptionHandler");
    void *rem = (void *)GetProcAddress(nt, "RtlRemoveVectoredExceptionHandler");
    if (!add || !rem) { fprintf(stderr, "[hlg-win] RtlAdd/RemoveVEH not found\n"); return; }
    InitializeCriticalSection(&s_veh_cs);
    s_add_fn = add; memcpy(s_add_orig, add, 14);
    s_rem_fn = rem; memcpy(s_rem_orig, rem, 14);
    write_jmp(add, (void *)hook_AddVEH);
    write_jmp(rem, (void *)hook_RemoveVEH);
    s_veh_hooked = 1;
    fprintf(stderr, "[hlg-win] RtlAdd/RemoveVectoredExceptionHandler hooked\n");
    hook_create_file_mapping();
    s_md_go = CreateEventA(NULL, FALSE, FALSE, NULL);
    s_md_done = CreateEventA(NULL, FALSE, FALSE, NULL);
    if (s_md_go && s_md_done)
        CloseHandle(CreateThread(NULL, 0, hlg_minidump_thread, NULL, 0, NULL));
    SetUnhandledExceptionFilter((LPTOP_LEVEL_EXCEPTION_FILTER)hlg_crash_uef);
    fprintf(stderr, "[hlg-win] crash UEF installed (chain -> JVM %p)\n",
            (void *)s_jvm_filter);
    fflush(stderr);
}

__declspec(dllexport) void hlg_reset_veh_session(void)
{
    if (!s_veh_hooked) return;
    EnterCriticalSection(&s_veh_cs);
    memset(s_fly, 0, sizeof(s_fly));
    s_dispatch_log_left = 5;
    if (s_dispatcher && !any_ps2_used()) {
        real_remove_veh(s_dispatcher);
        s_dispatcher = NULL;
    }
    LeaveCriticalSection(&s_veh_cs);
    t_fly_slot = -1;
    fprintf(stderr, "[hlg-win] Flycast VEH session reset (PS2 isolation kept)\n");
    fflush(stderr);
}

/* Context lifecycle. */
static int ensure_current(hlg_ctx *c) {
    if (!c || !c->initialized || !c->hdc || !c->hglrc) return 0;
    if (wglGetCurrentContext() == c->hglrc) { t_cur = c; return 1; }
    if (!wglMakeCurrent(c->hdc, c->hglrc)) {
        fprintf(stderr, "[hlg-win] ctx %p: wglMakeCurrent FAILED (err=%lu, tid=%lu)\n",
                (void *)c, GetLastError(), GetCurrentThreadId());
        return 0;
    }
    t_cur = c;
    return 1;
}

static LRESULT CALLBACK hidden_wnd_proc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    return DefWindowProcA(hwnd, msg, wp, lp);
}

static int create_hidden_window(hlg_ctx *c) {
    EnterCriticalSection(&s_global_cs);
    if (!s_wndclass_registered) {
        WNDCLASSA wc = {0};
        wc.lpfnWndProc   = hidden_wnd_proc;
        wc.hInstance     = GetModuleHandleA(NULL);
        wc.lpszClassName = "RetroConsoleHeadlessGL";
        if (RegisterClassA(&wc)) s_wndclass_registered = 1;
    }
    int reg = s_wndclass_registered;
    LeaveCriticalSection(&s_global_cs);
    if (!reg) return 0;

    c->hwnd = CreateWindowExA(0, "RetroConsoleHeadlessGL", "hlg",
            WS_OVERLAPPEDWINDOW, 0, 0, c->w, c->h, NULL, NULL,
            GetModuleHandleA(NULL), NULL);
    if (!c->hwnd) return 0;
    ShowWindow(c->hwnd, SW_HIDE);
    c->hdc = GetDC(c->hwnd);
    return c->hdc != NULL;
}

static void log_gpu_identity(hlg_ctx *c) {
    if (c->gpu_info[0]) return;
    typedef const unsigned char *(*glGetString_t)(unsigned int);
    glGetString_t gs = (glGetString_t)load_gl("glGetString");
    const char *vendor   = gs ? (const char *)gs(HLG_GL_VENDOR)   : "(null)";
    const char *renderer = gs ? (const char *)gs(HLG_GL_RENDERER) : "(null)";
    const char *version  = gs ? (const char *)gs(HLG_GL_VERSION)  : "(null)";
    snprintf(c->gpu_info, sizeof(c->gpu_info), "%s / %s (%s)",
             renderer ? renderer : "?", vendor ? vendor : "?", version ? version : "?");
    fprintf(stderr, "[hlg-win] ctx %p GPU: %s\n", (void *)c, c->gpu_info);
}

static int create_gl_context(hlg_ctx *c, int major, int minor, int compat) {
    PIXELFORMATDESCRIPTOR pfd = {0};
    pfd.nSize = sizeof(pfd);
    pfd.nVersion = 1;
    pfd.dwFlags = PFD_DRAW_TO_WINDOW | PFD_SUPPORT_OPENGL | PFD_DOUBLEBUFFER;
    pfd.iPixelType = PFD_TYPE_RGBA;
    pfd.cColorBits = 32;
    pfd.cDepthBits = 24;
    pfd.cStencilBits = 8;

    int pf = ChoosePixelFormat(c->hdc, &pfd);
    if (!pf || !SetPixelFormat(c->hdc, pf, &pfd)) return 0;

    HGLRC tmp = wglCreateContext(c->hdc);
    if (!tmp || !wglMakeCurrent(c->hdc, tmp)) return 0;

    PFNWGLCREATECONTEXTATTRIBSARBPROC createAttribs =
        (PFNWGLCREATECONTEXTATTRIBSARBPROC)wglGetProcAddress("wglCreateContextAttribsARB");
    if (createAttribs) {
        int profile = compat ? WGL_CONTEXT_COMPATIBILITY_PROFILE_BIT_ARB
                             : WGL_CONTEXT_CORE_PROFILE_BIT_ARB;
        int attribs[] = {
            WGL_CONTEXT_MAJOR_VERSION_ARB, major,
            WGL_CONTEXT_MINOR_VERSION_ARB, minor,
            WGL_CONTEXT_PROFILE_MASK_ARB,  profile,
            0
        };
        c->hglrc = createAttribs(c->hdc, 0, attribs);
        if (!c->hglrc && !compat) {
            attribs[5] = WGL_CONTEXT_COMPATIBILITY_PROFILE_BIT_ARB;
            c->hglrc = createAttribs(c->hdc, 0, attribs);
            if (c->hglrc) c->compat_profile = 1;
        }
    }

    if (!c->hglrc) {
        c->hglrc = tmp;
        tmp = NULL;
    } else if (tmp) {
        wglMakeCurrent(NULL, NULL);
        wglDeleteContext(tmp);
        tmp = NULL;
    }

    if (!wglMakeCurrent(c->hdc, c->hglrc)) return 0;
    t_cur = c;

    create_offscreen_fbo(c, c->w, c->h);

    typedef void (*glViewport_t)(int, int, int, int);
    glViewport_t gv = (glViewport_t)load_gl("glViewport");
    if (gv) gv(0, 0, c->w, c->h);
    return 1;
}

/* Exported instance API. */
__declspec(dllexport) void *hlg_create(void) {
    hlg_ctx *c = (hlg_ctx *)calloc(1, sizeof(hlg_ctx));
    if (!c) return NULL;
    c->w = 640;
    c->h = 480;
    InitializeCriticalSection(&c->cs);
    fprintf(stderr, "[hlg-win] hlg_create -> %p\n", (void *)c);
    return c;
}

__declspec(dllexport) void hlg_free(void *h) {
    hlg_ctx *c = (hlg_ctx *)h;
    if (!c) return;
    hlg_destroy_internal(c);
    DeleteCriticalSection(&c->cs);
    fprintf(stderr, "[hlg-win] hlg_free(%p)\n", (void *)c);
    free(c);
}

__declspec(dllexport) int hlg_init_ex(void *h, int api, int major, int minor, int flags) {
    hlg_ctx *c = (hlg_ctx *)h;
    if (!c) return 0;
    int compat = flags & 1;
    if (c->initialized) {
        int same = (c->api_mode == (api ? 1 : 0) && c->gl_major == major &&
                    c->gl_minor == minor && c->compat_profile == compat);
        if (same && c->hdc && c->hglrc && wglMakeCurrent(c->hdc, c->hglrc)) {
            t_cur = c;
            return 1;
        }
        fprintf(stderr, "[hlg-win] ctx %p reinit: same=%d err=%lu tid=%lu — recreating\n",
                (void *)c, same, GetLastError(), GetCurrentThreadId());
        hlg_destroy_internal(c);
    }
    c->api_mode       = api ? 1 : 0;
    c->compat_profile = compat;
    c->gl_major       = major;
    c->gl_minor       = minor;

    if (c->api_mode == 1) {
        fprintf(stderr, "[hlg-win] GLES not supported on Windows WGL — use desktop GL\n");
        return 0;
    }
    if (!create_hidden_window(c)) {
        fprintf(stderr, "[hlg-win] hidden window creation failed (err=%lu)\n", GetLastError());
        return 0;
    }
    if (!create_gl_context(c, major, minor, compat)) {
        fprintf(stderr, "[hlg-win] GL %d.%d context creation failed (err=%lu)\n",
                major, minor, GetLastError());
        hlg_destroy_internal(c);
        return 0;
    }
    c->initialized = 1;
    log_gpu_identity(c);
    fprintf(stderr, "[hlg-win] ctx %p init OK: GL %d.%d %s %dx%d (fbo=%d, tid=%lu)\n",
            (void *)c, c->gl_major, c->gl_minor, c->compat_profile ? "Compat" : "Core",
            c->w, c->h, c->fbo_ready, GetCurrentThreadId());
    return 1;
}

static void hlg_destroy_internal(hlg_ctx *c) {
    if (c->hglrc) {
        if (wglMakeCurrent(c->hdc, c->hglrc)) {
            destroy_pbos(c);
            destroy_offscreen_fbo(c);
            wglMakeCurrent(NULL, NULL);
            if (!wglDeleteContext(c->hglrc))
                fprintf(stderr, "[hlg-win] wglDeleteContext failed (err=%lu) — abandoning\n",
                        GetLastError());
        } else {
            fprintf(stderr, "[hlg-win] ctx %p current in another thread (err=%lu) — abandoning HGLRC %p\n",
                    (void *)c, GetLastError(), (void *)c->hglrc);
        }
        c->hglrc = NULL;
    }
    if (c->hdc && c->hwnd) { ReleaseDC(c->hwnd, c->hdc); c->hdc = NULL; }
    if (c->hwnd) { DestroyWindow(c->hwnd); c->hwnd = NULL; }
    c->fbo = c->color_rb = c->depth_rb = 0;
    c->fbo_ready = 0;
    c->pbo[0] = c->pbo[1] = 0;
    c->pbo_w[0] = c->pbo_w[1] = 0;
    c->pbo_h[0] = c->pbo_h[1] = 0;
    c->pbo_cap_w = c->pbo_cap_h = 0;
    c->pbo_idx = 0;
    c->initialized = 0;
    c->gpu_info[0] = '\0';
    c->last_fbo = 0;
    c->read_count = 0;
    if (t_cur == c) t_cur = NULL;
}

__declspec(dllexport) void hlg_destroy(void *h) {
    hlg_ctx *c = (hlg_ctx *)h;
    if (c) hlg_destroy_internal(c);
}

__declspec(dllexport) void hlg_release(void *h) {
    hlg_ctx *c = (hlg_ctx *)h;
    if (!c || !c->initialized) return;
    EnterCriticalSection(&c->cs);
    if (wglGetCurrentContext() == c->hglrc) wglMakeCurrent(NULL, NULL);
    if (t_cur == c) t_cur = NULL;
    LeaveCriticalSection(&c->cs);
    fprintf(stderr, "[hlg-win] ctx %p release: detached from thread %lu\n",
            (void *)c, GetCurrentThreadId());
}

__declspec(dllexport) int hlg_make_current(void *h) {
    hlg_ctx *c = (hlg_ctx *)h;
    if (!c || !c->initialized) return 0;
    EnterCriticalSection(&c->cs);
    int ok = ensure_current(c);
    LeaveCriticalSection(&c->cs);
    return ok;
}

__declspec(dllexport) int hlg_resize(void *h, int w, int ht) {
    hlg_ctx *c = (hlg_ctx *)h;
    if (!c || !c->initialized || w <= 0 || ht <= 0) return 0;
    if (w == c->w && ht == c->h) return 1;
    c->w = w;
    c->h = ht;
    if (c->hwnd) SetWindowPos(c->hwnd, NULL, 0, 0, w, ht,
                              SWP_NOMOVE | SWP_NOZORDER | SWP_NOACTIVATE);
    EnterCriticalSection(&c->cs);
    if (ensure_current(c)) {
        resize_offscreen_fbo(c, c->w, c->h);
        typedef void (*glViewport_t)(int, int, int, int);
        glViewport_t gv = (glViewport_t)load_gl("glViewport");
        if (gv) gv(0, 0, c->w, c->h);
    }
    LeaveCriticalSection(&c->cs);
    return 1;
}

/* Core callbacks (no handle — dispatch via t_cur). */
static void tracked_glBindFramebuffer(unsigned int target, unsigned int fbo) {
    hlg_ctx *c = t_cur;
    if (!c || !ensure_current(c)) return;
    if (!real_glBindFramebuffer)
        real_glBindFramebuffer = (void (*)(unsigned int, unsigned int))load_gl("glBindFramebuffer");
    unsigned int eff = fbo;
    if (fbo == 0 && c->fbo_ready) eff = c->fbo;
    if (real_glBindFramebuffer)
        real_glBindFramebuffer(target, eff);
    if (target == HLG_GL_DRAW_FRAMEBUFFER || target == HLG_GL_READ_FRAMEBUFFER ||
        target == HLG_GL_FRAMEBUFFER)
        c->last_fbo = (int)eff;
}

__declspec(dllexport) void *hlg_get_proc_address(const char *sym) {
    if (!sym) return NULL;
    hlg_ctx *c = t_cur;
    if (c) ensure_current(c);
    if (strcmp(sym, "glBindFramebuffer") == 0 || strcmp(sym, "glBindFramebufferEXT") == 0) {
        if (!real_glBindFramebuffer)
            real_glBindFramebuffer = (void (*)(unsigned int, unsigned int))load_gl("glBindFramebuffer");
        return (void *)tracked_glBindFramebuffer;
    }
    void *p = load_gl(sym);
    if (!p)
        fprintf(stderr, "[hlg-win] get_proc_address: %s -> NULL (tid=%lu t_cur=%p)\n",
                sym, GetCurrentThreadId(), (void *)t_cur);
    return p;
}

__declspec(dllexport) unsigned long hlg_get_framebuffer(void) {
    hlg_ctx *c = t_cur;
    return (unsigned long)((c && c->fbo_ready) ? c->fbo : 0);
}

__declspec(dllexport) void *hlg_get_framebuffer_ptr(void)  { return (void *)hlg_get_framebuffer; }
__declspec(dllexport) void *hlg_get_proc_address_ptr(void) { return (void *)hlg_get_proc_address; }

/* Readback (per-instance, double PBO). */
__declspec(dllexport) void hlg_read_pixels(void *h, int *viewport_out, void *pixels_out,
                                           int max_pixels, int req_w, int req_h) {
    hlg_ctx *c = (hlg_ctx *)h;
    if (!c || !viewport_out || !pixels_out) return;
    EnterCriticalSection(&c->cs);
    if (!ensure_current(c)) { LeaveCriticalSection(&c->cs); return; }
    typedef void (*glGetIntegerv_t)(unsigned int, int *);
    typedef void (*glReadPixels_t)(int, int, int, int, unsigned int, unsigned int, void *);
    typedef void (*glReadBuffer_t)(unsigned int);
    typedef void (*glPixelStorei_t)(unsigned int, int);
    glGetIntegerv_t gi  = (glGetIntegerv_t)load_gl("glGetIntegerv");
    glReadPixels_t  rp  = (glReadPixels_t)load_gl("glReadPixels");
    glReadBuffer_t  rb  = (glReadBuffer_t)load_gl("glReadBuffer");
    glPixelStorei_t ps  = (glPixelStorei_t)load_gl("glPixelStorei");
    if (!gi || !rp) { LeaveCriticalSection(&c->cs); return; }

    int prev_read = -1;
    if (c->fbo_ready && ensure_fbo_funcs()) {
        gi(HLG_GL_READ_FB_BINDING, &prev_read);
        p_BindFB(HLG_GL_READ_FRAMEBUFFER, c->fbo);
        if (rb) rb(HLG_GL_COLOR_ATTACHMENT0);
    } else {
        if (rb) rb(HLG_GL_BACK);
    }
    int vp[4] = {0};
    gi(HLG_GL_VIEWPORT, vp);
    int gl_w = vp[2] > 0 ? vp[2] : c->w;
    int gl_h = vp[3] > 0 ? vp[3] : c->h;
    if (gl_w <= 0 || gl_h <= 0) { gl_w = c->w; gl_h = c->h; }
    int read_w = (req_w > 0) ? req_w : gl_w;
    int read_h = (req_h > 0) ? req_h : gl_h;
    if (c->fbo_ready) {
        if (read_w > c->w) read_w = c->w;
        if (read_h > c->h) read_h = c->h;
    }
    if (max_pixels > 0 && read_w > 0 && (long long)read_w * read_h > max_pixels)
        read_h = max_pixels / read_w;
    if (read_w <= 0 || read_h <= 0) {
        if (c->fbo_ready && s_fbo_funcs_ready && prev_read >= 0) {
            unsigned int restore = prev_read != 0 ? (unsigned int)prev_read : c->fbo;
            p_BindFB(HLG_GL_READ_FRAMEBUFFER, restore);
        }
        LeaveCriticalSection(&c->cs);
        return;
    }
    if (ps) ps(HLG_GL_PACK_ALIGNMENT, 4);

    int use_pbo = ensure_pbo_buffers(c, read_w, read_h);
    if (use_pbo) {
        int idx = c->pbo_idx;
        int prev = idx ^ 1;
        int out_w = read_w;
        int out_h = read_h;

        p_BindBuf(HLG_GL_PIXEL_PACK_BUFFER, c->pbo[idx]);
        rp(0, 0, read_w, read_h, HLG_GL_BGRA, HLG_GL_UNSIGNED_BYTE, 0);
        c->pbo_w[idx] = read_w;
        c->pbo_h[idx] = read_h;

        if (c->pbo_w[prev] > 0 && c->pbo_h[prev] > 0) {
            out_w = c->pbo_w[prev];
            out_h = c->pbo_h[prev];
            p_BindBuf(HLG_GL_PIXEL_PACK_BUFFER, c->pbo[prev]);
            void *src = p_MapBuf(HLG_GL_PIXEL_PACK_BUFFER, HLG_GL_READ_ONLY);
            if (src) {
                copy_flipped_bgra(pixels_out, src, out_w, out_h);
                p_UnmapBuf(HLG_GL_PIXEL_PACK_BUFFER);
            }
        } else {
            memset(pixels_out, 0, (size_t)out_w * (size_t)out_h * 4);
        }

        p_BindBuf(HLG_GL_PIXEL_PACK_BUFFER, 0);
        c->pbo_idx = prev;

        viewport_out[0] = 0;
        viewport_out[1] = 0;
        viewport_out[2] = out_w;
        viewport_out[3] = out_h;
    } else {
        viewport_out[0] = 0;
        viewport_out[1] = 0;
        viewport_out[2] = read_w;
        viewport_out[3] = read_h;
        rp(0, 0, read_w, read_h, HLG_GL_BGRA, HLG_GL_UNSIGNED_BYTE, pixels_out);
        flip_rows_inplace((unsigned char *)pixels_out, read_w, read_h);
    }

    if (c->fbo_ready && s_fbo_funcs_ready && prev_read >= 0) {
        unsigned int restore = prev_read != 0 ? (unsigned int)prev_read : c->fbo;
        p_BindFB(HLG_GL_READ_FRAMEBUFFER, restore);
    }
    c->read_count++;
    LeaveCriticalSection(&c->cs);
}

__declspec(dllexport) void hlg_debug_fbo(void *h) {
    hlg_ctx *c = (hlg_ctx *)h;
    if (!c || !ensure_current(c)) return;
    typedef void (*glGetIntegerv_t)(unsigned int, int *);
    glGetIntegerv_t gi = (glGetIntegerv_t)load_gl("glGetIntegerv");
    if (!gi) return;
    int drawFbo = 0;
    gi(HLG_GL_DRAW_FRAMEBUFFER, &drawFbo);
    fprintf(stderr, "[hlg-win] ctx %p debug_fbo: drawFbo=%d lastFbo=%d ourFbo=%u\n",
            (void *)c, drawFbo, c->last_fbo, c->fbo);
}

__declspec(dllexport) const char *hlg_get_gpu_info(void *h) {
    hlg_ctx *c = (hlg_ctx *)h;
    return (c && c->gpu_info[0]) ? c->gpu_info : "GPU info not yet available";
}

__declspec(dllexport) void hlg_dump_hw_render(const void *data, int size)
{
    fprintf(stderr, "[hlg-win] HW RENDER DUMP (%d bytes)\n", size);
    const unsigned char *p = (const unsigned char *)data;
    if (!p) return;
    for (int i = 0; i < size; i += 8) {
        fprintf(stderr, "  +%2d: ", i);
        for (int j = 0; j < 8 && (i + j) < size; j++)
            fprintf(stderr, "%02x ", p[i + j]);
        fprintf(stderr, "\n");
    }
}

__declspec(dllexport) void hlg_log_cb(int level, const char *fmt, ...)
{
    char buf[2048];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt ? fmt : "(null)", ap);
    va_end(ap);
    parse_vmem_bounds(buf);
    fprintf(stderr, "[hlg-win][%d] %s\n", level, buf);
}

__declspec(dllexport) void *hlg_get_log_cb_ptr(void) { return (void *)hlg_log_cb; }

BOOL WINAPI DllMain(HINSTANCE hinst, DWORD reason, LPVOID reserved) {
    (void)reserved;
    if (reason == DLL_PROCESS_ATTACH) {
        DisableThreadLibraryCalls(hinst);
        InitializeCriticalSection(&s_global_cs);
        s_global_cs_ready = 1;
        s_jvm_filter = peek_uef();
    } else if (reason == DLL_PROCESS_DETACH && s_global_cs_ready) {
        DeleteCriticalSection(&s_global_cs);
        s_global_cs_ready = 0;
    }
    return TRUE;
}
