/*
 * headless_gl_win.c — Headless WGL OpenGL context for libretro on Windows.
 *
 * Изменения этого ревизии:
 *  (A) Рендер в собственный offscreen FBO (color RGBA8 + depth24stencil8),
 *      а не в back buffer скрытого окна. Надёжно на всех драйверах.
 *      hlg_get_framebuffer() отдаёт имя нашего FBO; bind(0) редиректится в него.
 *  (B) hlg_read_pixels честно возвращает реальные размеры viewport в viewport_out
 *      (это уже было) — теперь Java их использует.
 *  (C) VEH-изоляция Flycast: поддержка снятия отдельных хендлеров и hook
 *      RtlRemoveVectoredExceptionHandler -> нет use-after-free внутри сессии.
 *
 * ВНИМАНИЕ: инлайновый патч ntdll оставлен самописным (без внешних зависимостей).
 * Для продакшена рекомендуется MinHook: он делает установку атомарно и с
 * настоящим трамплином. Здесь окно гонки минимизировано, но не устранено на 100%.
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
 
 /* GL enums we use via dynamically loaded pointers */
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
 /* FBO / renderbuffer */
 #define HLG_GL_COLOR_ATTACHMENT0        0x8CE0
 #define HLG_GL_RENDERBUFFER             0x8D41
 #define HLG_GL_RGBA8                    0x8058
 #define HLG_GL_DEPTH24_STENCIL8         0x88F0
 #define HLG_GL_DEPTH_STENCIL_ATTACHMENT 0x821A
 #define HLG_GL_FRAMEBUFFER_COMPLETE     0x8CD5
 
 typedef HGLRC (WINAPI *PFNWGLCREATECONTEXTATTRIBSARBPROC)(HDC, HGLRC, const int *);
 
 static HWND  s_hwnd = NULL;
 static HDC   s_hdc  = NULL;
 static HGLRC s_hglrc = NULL;
 static int   s_initialized = 0;
 static int   s_gl_major = 3;
 static int   s_gl_minor = 3;
 static int   s_api_mode = 0;
 static int   s_compat_profile = 0;
 static int   s_w = 640, s_h = 480;
 static CRITICAL_SECTION s_gl_cs;
 static int   s_cs_ready = 0;
 static char  s_gpu_info[512] = "";
 static int   s_last_fbo = 0;
 static int   s_read_count = 0;
 
 static void hlg_destroy_internal(void);
 
 /* =========================================================================
  *  Offscreen FBO (A)
  * ========================================================================= */
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
 
 static GLuint s_fbo = 0, s_color_rb = 0, s_depth_rb = 0;
 static int    s_fbo_funcs_ready = 0;
 static int    s_fbo_ready = 0;
 
 static void *load_gl(const char *name); /* fwd */
 
 static int ensure_fbo_funcs(void)
 {
     if (s_fbo_funcs_ready) return 1;
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
     return s_fbo_funcs_ready;
 }
 
 static int create_offscreen_fbo(int w, int h)
 {
     if (!ensure_fbo_funcs()) return 0;
     if (!s_fbo)      p_GenFB(1, &s_fbo);
     if (!s_color_rb) p_GenRB(1, &s_color_rb);
     if (!s_depth_rb) p_GenRB(1, &s_depth_rb);
 
     p_BindFB(HLG_GL_FRAMEBUFFER, s_fbo);
 
     p_BindRB(HLG_GL_RENDERBUFFER, s_color_rb);
     p_RBStorage(HLG_GL_RENDERBUFFER, HLG_GL_RGBA8, w, h);
     p_FBRB(HLG_GL_FRAMEBUFFER, HLG_GL_COLOR_ATTACHMENT0, HLG_GL_RENDERBUFFER, s_color_rb);
 
     p_BindRB(HLG_GL_RENDERBUFFER, s_depth_rb);
     p_RBStorage(HLG_GL_RENDERBUFFER, HLG_GL_DEPTH24_STENCIL8, w, h);
     p_FBRB(HLG_GL_FRAMEBUFFER, HLG_GL_DEPTH_STENCIL_ATTACHMENT, HLG_GL_RENDERBUFFER, s_depth_rb);
 
     GLenum st = p_CheckFB(HLG_GL_FRAMEBUFFER);
     /* оставляем наш FBO привязанным как цель по умолчанию */
     p_BindFB(HLG_GL_FRAMEBUFFER, s_fbo);
     s_fbo_ready = (st == HLG_GL_FRAMEBUFFER_COMPLETE);
     if (!s_fbo_ready)
         fprintf(stderr, "[hlg-win] FBO incomplete (0x%x) — using back buffer\n", st);
     else
         fprintf(stderr, "[hlg-win] offscreen FBO %u ready (%dx%d)\n", s_fbo, w, h);
     return s_fbo_ready;
 }
 
 static void resize_offscreen_fbo(int w, int h)
 {
     if (!s_fbo_ready) return;
     p_BindRB(HLG_GL_RENDERBUFFER, s_color_rb);
     p_RBStorage(HLG_GL_RENDERBUFFER, HLG_GL_RGBA8, w, h);
     p_BindRB(HLG_GL_RENDERBUFFER, s_depth_rb);
     p_RBStorage(HLG_GL_RENDERBUFFER, HLG_GL_DEPTH24_STENCIL8, w, h);
     p_BindFB(HLG_GL_FRAMEBUFFER, s_fbo);
 }
 
 static void destroy_offscreen_fbo(void)
 {
     if (!s_fbo_funcs_ready) { s_fbo = s_color_rb = s_depth_rb = 0; s_fbo_ready = 0; return; }
     if (s_color_rb) p_DelRB(1, &s_color_rb);
     if (s_depth_rb) p_DelRB(1, &s_depth_rb);
     if (s_fbo)      p_DelFB(1, &s_fbo);
     s_fbo = s_color_rb = s_depth_rb = 0;
     s_fbo_ready = 0;
 }
 
 /* =========================================================================
  *  Flycast VEH isolation hook (C) — с поддержкой снятия хендлеров
  * ========================================================================= */
 static LPTOP_LEVEL_EXCEPTION_FILTER s_jvm_filter = NULL;
 typedef PVOID   (WINAPI *AddVEH_t)(ULONG, PVECTORED_EXCEPTION_HANDLER);
 typedef ULONG   (WINAPI *RemoveVEH_t)(PVOID);
 
 static void *s_add_fn = NULL;   static BYTE s_add_orig[14];
 static void *s_rem_fn = NULL;   static BYTE s_rem_orig[14];
 
 static CRITICAL_SECTION s_veh_cs;
 static int   s_veh_hooked = 0;
 
 /* Таблица изъятых обработчиков Flycast. Хендл, отдаваемый ядру, = &s_fly[i]. */
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
     BYTE patch[14] = { 0xFF, 0x25, 0,0,0,0 };   /* jmp qword ptr [rip+0] */
     memcpy(patch + 6, &target, sizeof(target));
     DWORD old;
     VirtualProtect(at, sizeof(patch), PAGE_EXECUTE_READWRITE, &old);
     memcpy(at, patch, sizeof(patch));
     VirtualProtect(at, sizeof(patch), old, &old);
     FlushInstructionCache(GetCurrentProcess(), at, sizeof(patch));
 }
 
 /* снять патч -> вызвать реальную -> вернуть патч (под s_veh_cs) */
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
         /* снимок под CS, чтобы не звать снятые между сессиями хендлеры */
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
     /* JVM safepoint poll / null-check — отдаём HotSpot */
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
                 handle = (PVOID)&s_fly[i];   /* уникальный не-NULL хендл */
                 break;
             }
         }
         if (!s_dispatcher)
             s_dispatcher = real_add_veh(1 /*head*/, hlg_dispatch_veh);
         LeaveCriticalSection(&s_veh_cs);
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
     /* наш ли это хендл? (указатель внутрь s_fly[]) */
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
     return real_remove_veh(Handle);
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
  *  GL context plumbing
  * ========================================================================= */
 static void ensure_cs(void)
 {
     if (!s_cs_ready) { InitializeCriticalSection(&s_gl_cs); s_cs_ready = 1; }
 }
 
 static void *load_gl(const char *name)
 {
     void *p = (void *)wglGetProcAddress(name);
     if (!p) {
         HMODULE mod = GetModuleHandleA("opengl32.dll");
         if (mod) p = (void *)GetProcAddress(mod, name);
     }
     return p;
 }
 
 static void log_gpu_identity(void)
 {
     if (s_gpu_info[0]) return;
     typedef const unsigned char *(*glGetString_t)(unsigned int);
     glGetString_t gs = (glGetString_t)load_gl("glGetString");
     const char *vendor   = gs ? (const char *)gs(HLG_GL_VENDOR)   : "(null)";
     const char *renderer = gs ? (const char *)gs(HLG_GL_RENDERER) : "(null)";
     const char *version  = gs ? (const char *)gs(HLG_GL_VERSION)  : "(null)";
     snprintf(s_gpu_info, sizeof(s_gpu_info), "%s / %s (%s)",
              renderer ? renderer : "?", vendor ? vendor : "?", version ? version : "?");
     fprintf(stderr, "[hlg-win] GPU: %s\n", s_gpu_info);
 }
 
 static int ensure_current(void)
 {
     if (!s_initialized || !s_hdc || !s_hglrc) return 0;
     if (wglGetCurrentContext() == s_hglrc) return 1;
     return wglMakeCurrent(s_hdc, s_hglrc) ? 1 : 0;
 }
 
 static LRESULT CALLBACK hidden_wnd_proc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp)
 {
     return DefWindowProcA(hwnd, msg, wp, lp);
 }
 
 static int create_hidden_window(void)
 {
     static int registered = 0;
     if (!registered) {
         WNDCLASSA wc = {0};
         wc.lpfnWndProc   = hidden_wnd_proc;
         wc.hInstance     = GetModuleHandleA(NULL);
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
 
 static int create_gl_context(int major, int minor, int compat)
 {
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
             WGL_CONTEXT_PROFILE_MASK_ARB,  profile,
             0
         };
         s_hglrc = createAttribs(s_hdc, 0, attribs);
         if (!s_hglrc && !compat) {
             attribs[5] = WGL_CONTEXT_COMPATIBILITY_PROFILE_BIT_ARB;
             s_hglrc = createAttribs(s_hdc, 0, attribs);
             if (s_hglrc) s_compat_profile = 1;
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
 
     /* (A) offscreen FBO как основная цель рендера */
     create_offscreen_fbo(s_w, s_h);
 
     typedef void (*glViewport_t)(int, int, int, int);
     glViewport_t gv = (glViewport_t)load_gl("glViewport");
     if (gv) gv(0, 0, s_w, s_h);
     return 1;
 }
 
 __declspec(dllexport) int hlg_init_ex(int api, int major, int minor, int flags)
 {
     int compat = flags & 1;
     if (s_initialized) {
         if (s_api_mode == (api ? 1 : 0) && s_gl_major == major &&
             s_gl_minor == minor && s_compat_profile == compat)
             return 1;
         hlg_destroy_internal();
     }
     s_api_mode       = api ? 1 : 0;
     s_compat_profile = compat;
     s_gl_major       = major;
     s_gl_minor       = minor;
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
     fprintf(stderr, "[hlg-win] init OK: GL %d.%d %s %dx%d (fbo=%d)\n",
             s_gl_major, s_gl_minor, s_compat_profile ? "Compat" : "Core",
             s_w, s_h, s_fbo_ready);
     return 1;
 }
 
 __declspec(dllexport) int hlg_init(int major, int minor)
 {
     return hlg_init_ex(0, major, minor, 0);
 }
 
 __declspec(dllexport) void hlg_destroy(void) { hlg_destroy_internal(); }
 
 static void hlg_destroy_internal(void)
 {
     if (s_hglrc) {
         wglMakeCurrent(s_hdc, s_hglrc);
         destroy_offscreen_fbo();
         wglMakeCurrent(NULL, NULL);
         wglDeleteContext(s_hglrc);
         s_hglrc = NULL;
     }
     if (s_hdc && s_hwnd) { ReleaseDC(s_hwnd, s_hdc); s_hdc = NULL; }
     if (s_hwnd) { DestroyWindow(s_hwnd); s_hwnd = NULL; }
     s_initialized = 0;
     s_gpu_info[0] = '\0';
     s_last_fbo    = 0;
     s_read_count  = 0;
 }
 
 __declspec(dllexport) void hlg_release(void)
 {
     if (s_initialized && s_cs_ready) {
         EnterCriticalSection(&s_gl_cs);
         wglMakeCurrent(NULL, NULL);
         LeaveCriticalSection(&s_gl_cs);
         fprintf(stderr, "[hlg-win] release: context detached from init thread\n");
     }
 }
 
 __declspec(dllexport) int hlg_make_current(void)
 {
     if (!s_initialized || !s_cs_ready) return 0;
     EnterCriticalSection(&s_gl_cs);
     int ok = ensure_current();
     LeaveCriticalSection(&s_gl_cs);
     return ok;
 }
 
 __declspec(dllexport) int hlg_resize(int w, int h)
 {
     if (!s_initialized || w <= 0 || h <= 0) return 0;
     if (w == s_w && h == s_h) return 1;
     s_w = w;
     s_h = h;
     if (s_hwnd) SetWindowPos(s_hwnd, NULL, 0, 0, w, h,
                              SWP_NOMOVE | SWP_NOZORDER | SWP_NOACTIVATE);
     if (s_cs_ready) EnterCriticalSection(&s_gl_cs);
     if (ensure_current()) {
         resize_offscreen_fbo(s_w, s_h);
         typedef void (*glViewport_t)(int, int, int, int);
         glViewport_t gv = (glViewport_t)load_gl("glViewport");
         if (gv) gv(0, 0, s_w, s_h);
     }
     if (s_cs_ready) LeaveCriticalSection(&s_gl_cs);
     return 1;
 }
 
 static void (*real_glBindFramebuffer)(unsigned int, unsigned int);
 static void tracked_glBindFramebuffer(unsigned int target, unsigned int fbo)
 {
     if (!ensure_current()) return;
     if (!real_glBindFramebuffer)
         real_glBindFramebuffer = (void (*)(unsigned int, unsigned int))load_gl("glBindFramebuffer");
     /* (A) редирект «дефолтного» FBO 0 в наш offscreen FBO */
     unsigned int eff = fbo;
     if (fbo == 0 && s_fbo_ready) eff = s_fbo;
     if (real_glBindFramebuffer)
         real_glBindFramebuffer(target, eff);
     if (target == HLG_GL_DRAW_FRAMEBUFFER || target == HLG_GL_READ_FRAMEBUFFER ||
         target == HLG_GL_FRAMEBUFFER)
         s_last_fbo = (int)eff;
 }
 
 __declspec(dllexport) void *hlg_get_proc_address(const char *sym)
 {
     if (!sym) return NULL;
     if (!ensure_current()) return NULL;
     if (strcmp(sym, "glBindFramebuffer") == 0 || strcmp(sym, "glBindFramebufferEXT") == 0) {
         if (!real_glBindFramebuffer)
             real_glBindFramebuffer = (void (*)(unsigned int, unsigned int))load_gl("glBindFramebuffer");
         return (void *)tracked_glBindFramebuffer;
     }
     return load_gl(sym);
 }
 
 /* Имя FBO, в который должно рендерить ядро. Наш offscreen FBO, либо 0. */
 __declspec(dllexport) unsigned long hlg_get_framebuffer(void)
 {
     return (unsigned long)(s_fbo_ready ? s_fbo : 0);
 }
 
 __declspec(dllexport) void *hlg_get_framebuffer_ptr(void)     { return (void *)hlg_get_framebuffer; }
 __declspec(dllexport) void *hlg_get_proc_address_ptr(void)    { return (void *)hlg_get_proc_address; }
 
 __declspec(dllexport) void hlg_debug_fbo(void)
 {
     if (!ensure_current()) return;
     typedef void (*glGetIntegerv_t)(unsigned int, int *);
     glGetIntegerv_t gi = (glGetIntegerv_t)load_gl("glGetIntegerv");
     if (!gi) return;
     int drawFbo = 0;
     gi(HLG_GL_DRAW_FRAMEBUFFER, &drawFbo);
     fprintf(stderr, "[hlg-win] debug_fbo: drawFbo=%d lastFbo=%d ourFbo=%u\n",
             drawFbo, s_last_fbo, s_fbo);
 }
 
 __declspec(dllexport) void hlg_read_pixels(int *viewport_out, void *pixels_out,
        int max_pixels, int req_w, int req_h)
 {
     if (!viewport_out || !pixels_out) return;
     if (!s_cs_ready) return;
     EnterCriticalSection(&s_gl_cs);
     if (!ensure_current()) { LeaveCriticalSection(&s_gl_cs); return; }
     typedef void (*glGetIntegerv_t)(unsigned int, int *);
     typedef void (*glReadPixels_t)(int, int, int, int, unsigned int, unsigned int, void *);
     typedef void (*glReadBuffer_t)(unsigned int);
     typedef void (*glPixelStorei_t)(unsigned int, int);
     typedef void (*glFinish_t)(void);
     glGetIntegerv_t gi  = (glGetIntegerv_t)load_gl("glGetIntegerv");
     glReadPixels_t  rp  = (glReadPixels_t)load_gl("glReadPixels");
     glReadBuffer_t  rb  = (glReadBuffer_t)load_gl("glReadBuffer");
     glPixelStorei_t ps  = (glPixelStorei_t)load_gl("glPixelStorei");
     glFinish_t      fin = (glFinish_t)load_gl("glFinish");
     if (!gi || !rp) { LeaveCriticalSection(&s_gl_cs); return; }
 
     /* (A) читаем из нашего offscreen FBO, если он есть */
     if (s_fbo_ready && ensure_fbo_funcs()) {
         p_BindFB(HLG_GL_READ_FRAMEBUFFER, s_fbo);
         if (rb) rb(HLG_GL_COLOR_ATTACHMENT0);
     } else {
         if (rb) rb(HLG_GL_BACK);
     }
 
     int vp[4] = {0};
     gi(HLG_GL_VIEWPORT, vp);
     int read_w = vp[2] > 0 ? vp[2] : (req_w > 0 ? req_w : s_w);
     int read_h = vp[3] > 0 ? vp[3] : (req_h > 0 ? req_h : s_h);
     if (max_pixels > 0 && read_w > 0 && (long long)read_w * read_h > max_pixels)
         read_h = max_pixels / read_w;
     if (read_w <= 0 || read_h <= 0) { LeaveCriticalSection(&s_gl_cs); return; }
 
     /* (B) отдаём Java РЕАЛЬНЫЕ размеры того, что прочитали */
     viewport_out[0] = vp[0];
     viewport_out[1] = vp[1];
     viewport_out[2] = read_w;
     viewport_out[3] = read_h;
 
     if (ps) ps(HLG_GL_PACK_ALIGNMENT, 4);
     if (fin && (s_read_count % 120) == 0) fin();
 
     rp(vp[0], vp[1], read_w, read_h, HLG_GL_BGRA, HLG_GL_UNSIGNED_BYTE, pixels_out);
 
     /* GL origin = низ-лево -> переворот строк */
     {
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
     s_read_count++;
     LeaveCriticalSection(&s_gl_cs);
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
 
 __declspec(dllexport) const char *hlg_get_gpu_info(void)
 {
     return s_gpu_info[0] ? s_gpu_info : "GPU info not yet available";
 }
 
 __declspec(dllexport) void hlg_log_gpu_identity(void)
 {
     if (s_initialized && ensure_current()) log_gpu_identity();
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
 
 BOOL WINAPI DllMain(HINSTANCE hinst, DWORD reason, LPVOID reserved)
 {
     (void)reserved;
     if (reason == DLL_PROCESS_ATTACH) {
         DisableThreadLibraryCalls(hinst);
         s_jvm_filter = peek_uef();
     } else if (reason == DLL_PROCESS_DETACH && s_cs_ready) {
         DeleteCriticalSection(&s_gl_cs);
         s_cs_ready = 0;
     }
     return TRUE;
 }