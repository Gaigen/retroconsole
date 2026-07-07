/*
 * headless_gl_win.c — Headless WGL OpenGL contexts for libretro on Windows.
 *
 * Ревизия (multi-instance): синглтон убран.
 *  - hlg_create()/hlg_free() создают/уничтожают независимые инстансы:
 *    своё скрытое окно + свой HGLRC + свой offscreen FBO на каждый.
 *  - Все контекстные функции принимают handle первым параметром.
 *  - get_current_framebuffer / get_proc_address у libretro НЕ имеют
 *    user-data, поэтому диспетчеризация идёт через thread-local t_cur:
 *    каждый инстанс живёт строго на своём retro-core-потоке, и
 *    hlg_make_current(h) привязывает инстанс к текущему потоку.
 *  - Глобальными остаются ТОЛЬКО процессные вещи: VEH-хуки ntdll,
 *    JVM UEF, лог-коллбек, регистрация класса окна, кэш GL-функций
 *    (указатели wglGetProcAddress валидны для всех контекстов одного
 *    драйвера — на destroy инстанса их сбрасывать НЕЛЬЗЯ, ими может
 *    пользоваться другой живой инстанс).
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

typedef HGLRC (WINAPI *PFNWGLCREATECONTEXTATTRIBSARBPROC)(HDC, HGLRC, const int *);

/* =========================================================================
 *  Инстанс
 * ========================================================================= */
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
} hlg_ctx;

/* Инстанс, привязанный к ТЕКУЩЕМУ потоку (см. шапку файла). */
#if defined(_MSC_VER)
#define HLG_TLS __declspec(thread)
#else
#define HLG_TLS __thread
#endif
static HLG_TLS hlg_ctx *t_cur = NULL;

/* Процессные глобалы. */
static CRITICAL_SECTION s_global_cs;      /* класс окна + кэш GL-функций */
static int s_global_cs_ready = 0;
static int s_wndclass_registered = 0;

static void hlg_destroy_internal(hlg_ctx *c);

/* =========================================================================
 *  GL function loading (кэш процессный)
 * ========================================================================= */
static void *load_gl(const char *name) {
    void *p = (void *)wglGetProcAddress(name);
    if (!p) {
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

/* =========================================================================
 *  Offscreen FBO (per-instance)
 * ========================================================================= */
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

/* =========================================================================
 *  Flycast VEH isolation hook — процессный глобал.
 * ========================================================================= */
static LPTOP_LEVEL_EXCEPTION_FILTER s_jvm_filter = NULL;
typedef PVOID   (WINAPI *AddVEH_t)(ULONG, PVECTORED_EXCEPTION_HANDLER);
typedef ULONG   (WINAPI *RemoveVEH_t)(PVOID);

static void *s_add_fn = NULL;   static BYTE s_add_orig[14];
static void *s_rem_fn = NULL;   static BYTE s_rem_orig[14];

static CRITICAL_SECTION s_veh_cs;
static int   s_veh_hooked = 0;

typedef struct { PVECTORED_EXCEPTION_HANDLER h; int used; } fly_entry;
static fly_entry s_fly[8];
static PVOID s_dispatcher = NULL;
static int   s_dispatch_log_left = 5;
static ULONG_PTR s_nvmem_lo = 0;
static ULONG_PTR s_nvmem_hi = 0;

static PVOID WINAPI hook_AddVEH(ULONG First, PVECTORED_EXCEPTION_HANDLER Handler);
static ULONG WINAPI hook_RemoveVEH(PVOID Handle);

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

static int is_flycast_addr(void *p)
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

static int is_flycast_ctx(PEXCEPTION_POINTERS ep)
{
    if (!ep || !ep->ContextRecord) return 0;
#if defined(_M_X64) || defined(__x86_64__)
    return is_flycast_addr((void *)ep->ContextRecord->Rip);
#elif defined(_M_IX86) || defined(__i386__)
    return is_flycast_addr((void *)ep->ContextRecord->Eip);
#else
    return 0;
#endif
}

static void parse_vmem_bounds(const char *msg)
{
    const char *vmem = strstr(msg, "BASE ");
    if (!vmem) return;
    unsigned long long base = 0, ram = 0, vram = 0, aram = 0;
    unsigned ram_mb = 0, vram_mb = 0, aram_mb = 0;
    if (sscanf(vmem,
               "BASE %llx RAM(%u MB) %llx VRAM64(%u MB) %llx ARAM(%u MB) %llx",
               &base, &ram_mb, &ram, &vram_mb, &vram, &aram_mb, &aram) < 7)
        return;
    ULONG_PTR lo = (ULONG_PTR)base;
    ULONG_PTR hi = (ULONG_PTR)aram + (ULONG_PTR)aram_mb * 1024 * 1024;
    ULONG_PTR ram_end  = (ULONG_PTR)ram  + (ULONG_PTR)ram_mb  * 1024 * 1024;
    ULONG_PTR vram_end = (ULONG_PTR)vram + (ULONG_PTR)vram_mb * 1024 * 1024;
    if ((ULONG_PTR)ram  < lo) lo = (ULONG_PTR)ram;
    if ((ULONG_PTR)vram < lo) lo = (ULONG_PTR)vram;
    if ((ULONG_PTR)aram < lo) lo = (ULONG_PTR)aram;
    if (ram_end  > hi) hi = ram_end;
    if (vram_end > hi) hi = vram_end;
    if (lo > 0x81000000ULL) lo -= 0x81000000ULL;
    hi += 0x1000000ULL;
    s_nvmem_lo = lo;
    s_nvmem_hi = hi;
    fprintf(stderr, "[hlg-win] nvmem bounds: %p - %p\n", (void *)lo, (void *)hi);
    fflush(stderr);
}

static int is_nvmem_addr(ULONG_PTR addr)
{
    if (s_nvmem_lo < s_nvmem_hi)
        return (addr >= s_nvmem_lo && addr < s_nvmem_hi) ? 1 : 0;
    if (addr < 0x00007FF400000000ULL || addr >= 0x00007FF500000000ULL)
        return 0;
    MEMORY_BASIC_INFORMATION mbi;
    if (VirtualQuery((LPCVOID)addr, &mbi, sizeof(mbi)) != sizeof(mbi))
        return 1;
    if (mbi.Type == MEM_MAPPED) return 1;
    if (mbi.State == MEM_FREE || mbi.State == MEM_RESERVE) return 1;
    return 0;
}

static int should_route_flycast(PEXCEPTION_POINTERS ep)
{
    if (is_flycast_ctx(ep)) return 1;
    ULONG_PTR addr = (ULONG_PTR)ep->ExceptionRecord->ExceptionInformation[1];
    return is_nvmem_addr(addr);
}

static LONG CALLBACK hlg_dispatch_veh(PEXCEPTION_POINTERS ep)
{
    EXCEPTION_RECORD *er = ep->ExceptionRecord;
    if (er->ExceptionCode != EXCEPTION_ACCESS_VIOLATION)
        return EXCEPTION_CONTINUE_SEARCH;
    int route_fly = should_route_flycast(ep);
    if (s_dispatch_log_left > 0) {
        ULONG_PTR addr = (ULONG_PTR)er->ExceptionInformation[1];
#if defined(_M_X64) || defined(__x86_64__)
        void *rip = (void *)ep->ContextRecord->Rip;
#else
        void *rip = (void *)ep->ContextRecord->Eip;
#endif
        fprintf(stderr, "[hlg-win] AV dispatch: rip=%p addr=%p route_fly=%d\n",
                rip, (void *)addr, route_fly);
        fflush(stderr);
        s_dispatch_log_left--;
    }
    if (route_fly) {
        PVECTORED_EXCEPTION_HANDLER snap[8];
        int n = 0;
        EnterCriticalSection(&s_veh_cs);
        for (int i = 0; i < 8; ++i)
            if (s_fly[i].used && s_fly[i].h) snap[n++] = s_fly[i].h;
        LeaveCriticalSection(&s_veh_cs);
        for (int i = 0; i < n; ++i) {
            LONG r = snap[i](ep);
            if (r == EXCEPTION_CONTINUE_EXECUTION) return r;
        }
        return EXCEPTION_CONTINUE_SEARCH;
    }
    return EXCEPTION_CONTINUE_SEARCH;
}

static PVOID WINAPI hook_AddVEH(ULONG First, PVECTORED_EXCEPTION_HANDLER Handler)
{
    if (is_flycast_addr((void *)Handler)) {
        PVOID handle = NULL;
        EnterCriticalSection(&s_veh_cs);
        for (int i = 0; i < 8; ++i) {
            if (!s_fly[i].used) {
                s_fly[i].h = Handler;
                s_fly[i].used = 1;
                handle = (PVOID)&s_fly[i];
                break;
            }
        }
        if (!s_dispatcher)
            s_dispatcher = real_add_veh(1 /*head*/, hlg_dispatch_veh);
        LeaveCriticalSection(&s_veh_cs);
        if (!handle)
            fprintf(stderr, "[hlg-win] WARNING: fly table FULL — handler %p NOT registered!\n",
                    (void *)Handler);
        fprintf(stderr, "[hlg-win] Flycast VEH %p ISOLATED (handle=%p)\n",
                (void *)Handler, handle);
        fflush(stderr);
        return handle ? handle : (PVOID)Handler;
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
        e->used = 0;
        e->h = NULL;
        LeaveCriticalSection(&s_veh_cs);
        fprintf(stderr, "[hlg-win] Flycast VEH removed (handle=%p)\n", Handle);
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
    if (cur != NULL) s_jvm_filter = cur;
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
    fflush(stderr);
}

__declspec(dllexport) void hlg_reset_veh_session(void)
{
    if (!s_veh_hooked) return;
    EnterCriticalSection(&s_veh_cs);
    if (s_dispatcher) {
        RemoveVectoredExceptionHandler(s_dispatcher);
        s_dispatcher = NULL;
    }
    memset(s_fly, 0, sizeof(s_fly));
    s_nvmem_lo = 0;
    s_nvmem_hi = 0;
    s_dispatch_log_left = 5;
    LeaveCriticalSection(&s_veh_cs);
    fprintf(stderr, "[hlg-win] VEH session reset\n");
    fflush(stderr);
}

/* =========================================================================
 *  Контекст
 * ========================================================================= */
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

/* =========================================================================
 *  Экспорты: инстансы
 * ========================================================================= */
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

/* =========================================================================
 *  Коллбеки для ядра — БЕЗ handle, диспетчеризация через t_cur
 * ========================================================================= */
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
    return load_gl(sym);
}

__declspec(dllexport) unsigned long hlg_get_framebuffer(void) {
    hlg_ctx *c = t_cur;
    return (unsigned long)((c && c->fbo_ready) ? c->fbo : 0);
}

__declspec(dllexport) void *hlg_get_framebuffer_ptr(void)  { return (void *)hlg_get_framebuffer; }
__declspec(dllexport) void *hlg_get_proc_address_ptr(void) { return (void *)hlg_get_proc_address; }

/* =========================================================================
 *  Readback (per-instance)
 * ========================================================================= */
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
    /* Читаем game frame size из video_cb (req_w/req_h), не только viewport. */
    int read_w = (req_w > 0) ? req_w : gl_w;
    int read_h = (req_h > 0) ? req_h : gl_h;
    if (c->fbo_ready) {
        if (read_w > c->w) read_w = c->w;
        if (read_h > c->h) read_h = c->h;
    }
    if (max_pixels > 0 && read_w > 0 && (long long)read_w * read_h > max_pixels)
        read_h = max_pixels / read_w;
    if (read_w <= 0 || read_h <= 0) { LeaveCriticalSection(&c->cs); return; }
    viewport_out[0] = 0;
    viewport_out[1] = 0;
    viewport_out[2] = read_w;
    viewport_out[3] = read_h;
    if (ps) ps(HLG_GL_PACK_ALIGNMENT, 4);
    rp(0, 0, read_w, read_h, HLG_GL_BGRA, HLG_GL_UNSIGNED_BYTE, pixels_out);

    { /* GL origin = низ-лево -> переворот строк */
        unsigned char *px = (unsigned char *)pixels_out;
        size_t stride = (size_t)read_w * 4;
        unsigned char *tmp = (unsigned char *)malloc(stride);
        if (tmp) {
            for (int y = 0; y < read_h / 2; ++y) {
                unsigned char *a = px + (size_t)y * stride;
                unsigned char *b = px + (size_t)(read_h - 1 - y) * stride;
                memcpy(tmp, a, stride);
                memcpy(a, b, stride);
                memcpy(b, tmp, stride);
            }
            free(tmp);
        }
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
