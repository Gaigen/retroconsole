/*
 * headless_gl_win.c — Headless WGL OpenGL context for libretro on Windows.
 * Same C API as headless_gl.c (Linux EGL) so LibretroCoreWindows can share
 * the HeadlessGL JNA interface.
 *
 * NEW (Windows fix): hook RtlAddVectoredExceptionHandler to isolate Flycast's
 *   VEH behind our dispatcher. JVM safepoint polls skip Flycast; fast-mem
 *   faults (RIP in flycast DLL or fault addr in nvmem, incl. dynarec JIT) go
 *   to Flycast handlers. Bounds parsed from Flycast VMEM log line.
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
 #define WGL_DRAW_TO_WINDOW_ARB 0x2001
 #define WGL_SUPPORT_OPENGL_ARB 0x2010
 #define WGL_DOUBLE_BUFFER_ARB  0x2011
 #define WGL_PIXEL_TYPE_ARB     0x2013
 #define WGL_TYPE_RGBA_ARB      0x202B
 #define WGL_COLOR_BITS_ARB     0x2014
 #define WGL_DEPTH_BITS_ARB     0x2022
 #define WGL_STENCIL_BITS_ARB   0x2023
 #endif
 
 /* GL enums we use via dynamically loaded pointers */
 #define HLG_GL_VENDOR            0x1F00
 #define HLG_GL_RENDERER          0x1F01
 #define HLG_GL_VERSION           0x1F02
 #define HLG_GL_VIEWPORT          0x0BA2
 #define HLG_GL_RGBA              0x1908
 #define HLG_GL_BGRA              0x80E1
 #define HLG_GL_UNSIGNED_BYTE     0x1401
 #define HLG_GL_BACK              0x0405
 #define HLG_GL_PACK_ALIGNMENT    0x0D05
 #define HLG_GL_DRAW_FRAMEBUFFER  0x8CA9
 #define HLG_GL_READ_FRAMEBUFFER  0x8CA8
 #define HLG_GL_FRAMEBUFFER       0x8D40
 
 typedef HGLRC (WINAPI *PFNWGLCREATECONTEXTATTRIBSARBPROC)(HDC, HGLRC, const int *);
 typedef BOOL  (WINAPI *PFNWGLCHOOSEPIXELFORMATARBPROC)(HDC, const int *, const float *, UINT, int *, UINT *);
 
 static HWND  s_hwnd = NULL;
 static HDC   s_hdc  = NULL;
 static HGLRC s_hglrc = NULL;
 static int   s_initialized = 0;
 static int   s_gl_major = 3;
 static int   s_gl_minor = 1;
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
  *  Flycast VEH isolation hook (Windows fix)
  * ========================================================================= */
 static LPTOP_LEVEL_EXCEPTION_FILTER s_jvm_filter = NULL; /* HotSpot UEF (optional capture) */

 typedef PVOID (WINAPI *AddVEH_t)(ULONG, PVECTORED_EXCEPTION_HANDLER);

 static void* s_veh_fn = NULL;               /* ntdll!RtlAddVectoredExceptionHandler */
 static BYTE  s_veh_orig[14];
 static CRITICAL_SECTION s_veh_cs;
 static int   s_veh_hooked = 0;

 static PVECTORED_EXCEPTION_HANDLER s_fly[8]; /* обработчики Flycast (изъятые) */
 static int   s_fly_count = 0;
 static PVOID s_dispatcher = NULL;            /* наш единственный реальный VEH */
 static int   s_dispatch_log_left = 5;        /* первые N AV — в stderr для отладки */
 static ULONG_PTR s_nvmem_lo = 0;             /* из VMEM-лога Flycast + mirror margin */
 static ULONG_PTR s_nvmem_hi = 0;

 static PVOID WINAPI hook_AddVEH(ULONG First, PVECTORED_EXCEPTION_HANDLER Handler);

 /* Read the current UEF without permanently changing it. */
 static LPTOP_LEVEL_EXCEPTION_FILTER peek_uef(void) {
     LPTOP_LEVEL_EXCEPTION_FILTER cur = SetUnhandledExceptionFilter(NULL);
     SetUnhandledExceptionFilter(cur);
     return cur;
 }

 static void write_jmp(void* at, void* target) {
     BYTE patch[14] = { 0xFF, 0x25, 0,0,0,0 };   /* jmp qword ptr [rip+0] */
     memcpy(patch + 6, &target, sizeof(target));
     DWORD old;
     VirtualProtect(at, sizeof(patch), PAGE_EXECUTE_READWRITE, &old);
     memcpy(at, patch, sizeof(patch));
     VirtualProtect(at, sizeof(patch), old, &old);
     FlushInstructionCache(GetCurrentProcess(), at, sizeof(patch));
 }

 /* вызвать настоящий RtlAddVectoredExceptionHandler (снять патч -> вызвать -> вернуть) */
 static PVOID real_add_veh(ULONG First, PVECTORED_EXCEPTION_HANDLER H) {
     DWORD old;
     VirtualProtect(s_veh_fn, 14, PAGE_EXECUTE_READWRITE, &old);
     memcpy(s_veh_fn, s_veh_orig, 14);
     FlushInstructionCache(GetCurrentProcess(), s_veh_fn, 14);
     PVOID h = ((AddVEH_t)s_veh_fn)(First, H);
     write_jmp(s_veh_fn, (void*)hook_AddVEH);
     VirtualProtect(s_veh_fn, 14, old, &old);
     return h;
 }

 /* принадлежит ли адрес модулю flycast? */
 static int is_flycast_addr(void* p) {
     HMODULE hm = NULL;
     if (GetModuleHandleExA(0x4 /*FROM_ADDRESS*/ | 0x2 /*UNCHANGED_REFCOUNT*/,
                            (LPCSTR)p, &hm) && hm) {
         char name[MAX_PATH];
         if (GetModuleFileNameA(hm, name, MAX_PATH)) {
             for (char* s = name; *s; ++s) *s = (char)tolower((unsigned char)*s);
             if (strstr(name, "flycast")) return 1;
         }
     }
     return 0;
 }

 /* фолт произошёл в коде flycast? (fast-mem load/store) */
 static int is_flycast_ctx(PEXCEPTION_POINTERS ep) {
     if (!ep || !ep->ContextRecord) return 0;
#if defined(_M_X64) || defined(__x86_64__)
     return is_flycast_addr((void*)ep->ContextRecord->Rip);
#elif defined(_M_IX86) || defined(__i386__)
     return is_flycast_addr((void*)ep->ContextRecord->Eip);
#else
     return 0;
#endif
 }

 /* обновить границы nvmem из строки Flycast "BASE ... RAM ... VRAM64 ... ARAM ..." */
 static void parse_vmem_bounds(const char *msg) {
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
     ULONG_PTR ram_end = (ULONG_PTR)ram + (ULONG_PTR)ram_mb * 1024 * 1024;
     ULONG_PTR vram_end = (ULONG_PTR)vram + (ULONG_PTR)vram_mb * 1024 * 1024;
     ULONG_PTR aram_end = hi;
     if ((ULONG_PTR)ram < lo) lo = (ULONG_PTR)ram;
     if ((ULONG_PTR)vram < lo) lo = (ULONG_PTR)vram;
     if ((ULONG_PTR)aram < lo) lo = (ULONG_PTR)aram;
     if (ram_end > hi) hi = ram_end;
     if (vram_end > hi) hi = vram_end;
     if (aram_end > hi) hi = aram_end;
     /* fastmem mirror: фолты бывают ~0x80100000 ниже BASE (dynarec JIT) */
     if (lo > 0x81000000ULL) lo -= 0x81000000ULL;
     hi += 0x1000000ULL;
     s_nvmem_lo = lo;
     s_nvmem_hi = hi;
     fprintf(stderr, "[hlg-win] nvmem bounds: %p - %p\n", (void*)lo, (void*)hi);
     fflush(stderr);
 }

 /* адрес фолта в арене nvmem Flycast? (включая дыры между mapping'ами) */
 static int is_nvmem_addr(ULONG_PTR addr) {
     if (s_nvmem_lo < s_nvmem_hi) {
         if (addr >= s_nvmem_lo && addr < s_nvmem_hi)
             return 1;
         return 0;
     }
     /* до парсинга VMEM — грубая эвристика по старшим битам */
     if (addr < 0x00007FF400000000ULL || addr >= 0x00007FF500000000ULL)
         return 0;
     MEMORY_BASIC_INFORMATION mbi;
     if (VirtualQuery((LPCVOID)addr, &mbi, sizeof(mbi)) != sizeof(mbi))
         return 1;
     if (mbi.Type == MEM_MAPPED) return 1;
     if (mbi.State == MEM_FREE || mbi.State == MEM_RESERVE) return 1;
     return 0;
 }

 static int should_route_flycast(PEXCEPTION_POINTERS ep) {
     if (is_flycast_ctx(ep)) return 1;
     ULONG_PTR addr = (ULONG_PTR)ep->ExceptionRecord->ExceptionInformation[1];
     return is_nvmem_addr(addr);
 }

 /* наш диспетчер: Flycast — если RIP в DLL или адрес в nvmem (dynarec JIT) */
 static LONG CALLBACK hlg_dispatch_veh(PEXCEPTION_POINTERS ep) {
     EXCEPTION_RECORD* er = ep->ExceptionRecord;
     if (er->ExceptionCode != EXCEPTION_ACCESS_VIOLATION)
         return EXCEPTION_CONTINUE_SEARCH;

     int route_fly = should_route_flycast(ep);
     if (s_dispatch_log_left > 0) {
         ULONG_PTR addr = (ULONG_PTR)er->ExceptionInformation[1];
#if defined(_M_X64) || defined(__x86_64__)
         void* rip = (void*)ep->ContextRecord->Rip;
#else
         void* rip = (void*)ep->ContextRecord->Eip;
#endif
         fprintf(stderr, "[hlg-win] AV dispatch: rip=%p addr=%p route_fly=%d\n",
                 rip, (void*)addr, route_fly);
         fflush(stderr);
         s_dispatch_log_left--;
     }

     if (route_fly) {
         for (int i = 0; i < s_fly_count; ++i) {
             LONG r = s_fly[i](ep);
             if (r == EXCEPTION_CONTINUE_EXECUTION) return r;
         }
         return EXCEPTION_CONTINUE_SEARCH;
     }
     /* poll/null-check JVM — HotSpot VEH / frame-SEH */
     return EXCEPTION_CONTINUE_SEARCH;
 }

 static PVOID WINAPI hook_AddVEH(ULONG First, PVECTORED_EXCEPTION_HANDLER Handler) {
     if (is_flycast_addr((void*)Handler)) {
         EnterCriticalSection(&s_veh_cs);
         if (s_fly_count < 8) s_fly[s_fly_count++] = Handler;
         if (!s_dispatcher)
             s_dispatcher = real_add_veh(1 /*head*/, hlg_dispatch_veh);
         LeaveCriticalSection(&s_veh_cs);
         fprintf(stderr, "[hlg-win] Flycast VEH %p ISOLATED (via dispatcher, count=%d)\n",
                 (void*)Handler, s_fly_count);
         fflush(stderr);
         return (PVOID)Handler;                 /* фиктивный, но не-NULL хэндл */
     }
     /* не Flycast — регистрируем как есть */
     EnterCriticalSection(&s_veh_cs);
     PVOID h = real_add_veh(First, Handler);
     LeaveCriticalSection(&s_veh_cs);
     return h;
 }

 /* Call from Java BEFORE loading the Flycast core (optional — DllMain also
  * captures it as a safety net, since this DLL loads before the core). */
 __declspec(dllexport) void hlg_capture_jvm_filter(void) {
     LPTOP_LEVEL_EXCEPTION_FILTER cur = peek_uef();
     if (cur != NULL)
         s_jvm_filter = cur;
     fprintf(stderr, "[hlg-win] captured JVM UEF = %p\n", (void *)s_jvm_filter);
 }

 __declspec(dllexport) void hlg_hook_addveh(void) {
     if (s_veh_hooked) return;
     HMODULE nt = GetModuleHandleA("ntdll.dll");
     void* fn = nt ? (void*)GetProcAddress(nt, "RtlAddVectoredExceptionHandler") : NULL;
     if (!fn) { fprintf(stderr, "[hlg-win] RtlAddVEH not found\n"); fflush(stderr); return; }
     InitializeCriticalSection(&s_veh_cs);
     s_veh_fn = fn;
     memcpy(s_veh_orig, fn, 14);
     write_jmp(fn, (void*)hook_AddVEH);
     s_veh_hooked = 1;
     fprintf(stderr, "[hlg-win] RtlAddVectoredExceptionHandler hooked (Flycast VEH isolated)\n");
     fflush(stderr);
 }

 /* Between core sessions: drop dispatcher + cached Flycast handlers (hook stays). */
 __declspec(dllexport) void hlg_reset_veh_session(void) {
     if (!s_veh_hooked) return;
     EnterCriticalSection(&s_veh_cs);
     if (s_dispatcher) {
         RemoveVectoredExceptionHandler(s_dispatcher);
         s_dispatcher = NULL;
     }
     s_fly_count = 0;
     memset(s_fly, 0, sizeof(s_fly));
     s_nvmem_lo = 0;
     s_nvmem_hi = 0;
     s_dispatch_log_left = 5;
     LeaveCriticalSection(&s_veh_cs);
     fprintf(stderr, "[hlg-win] VEH session reset (dispatcher removed, fly[]=cleared)\n");
     fflush(stderr);
 }
 
 /* =========================================================================
  *  GL context plumbing
  * ========================================================================= */
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
     const char *vendor   = gs ? (const char *)gs(HLG_GL_VENDOR)   : "(null)";
     const char *renderer = gs ? (const char *)gs(HLG_GL_RENDERER) : "(null)";
     const char *version  = gs ? (const char *)gs(HLG_GL_VERSION)  : "(null)";
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
         s_hglrc = tmp;   /* fall back to the legacy context */
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
     s_last_fbo    = 0;
     s_read_count  = 0;
 }
 
 __declspec(dllexport) void hlg_release(void) {
     if (s_initialized && s_cs_ready) {
         EnterCriticalSection(&s_gl_cs);
         wglMakeCurrent(NULL, NULL);
         LeaveCriticalSection(&s_gl_cs);
         fprintf(stderr, "[hlg-win] release: context detached from init thread\n");
     }
 }
 
 __declspec(dllexport) int hlg_make_current(void) {
     if (!s_initialized || !s_cs_ready) return 0;
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
     if (s_hwnd) SetWindowPos(s_hwnd, NULL, 0, 0, w, h,
                              SWP_NOMOVE | SWP_NOZORDER | SWP_NOACTIVATE);
     if (s_cs_ready) EnterCriticalSection(&s_gl_cs);
     if (ensure_current()) {
         typedef void (*glViewport_t)(int, int, int, int);
         glViewport_t gv = (glViewport_t)load_gl("glViewport");
         if (gv) gv(0, 0, s_w, s_h);
     }
     if (s_cs_ready) LeaveCriticalSection(&s_gl_cs);
     return 1;
 }
 
 static void (*real_glBindFramebuffer)(unsigned int, unsigned int);
 static void tracked_glBindFramebuffer(unsigned int target, unsigned int fbo) {
     if (!ensure_current()) return;
     if (!real_glBindFramebuffer)
         real_glBindFramebuffer = (void (*)(unsigned int, unsigned int))load_gl("glBindFramebuffer");
     if (real_glBindFramebuffer)
         real_glBindFramebuffer(target, fbo);
     if (target == HLG_GL_DRAW_FRAMEBUFFER || target == HLG_GL_READ_FRAMEBUFFER ||
         target == HLG_GL_FRAMEBUFFER)
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
 
 /* GL framebuffer NAME (a GLuint), 0 = default FBO. Kept as unsigned long only
  * to match the Linux ABI / shared JNA signature; the value is always a 32-bit
  * GL name, so LLP64 width is irrelevant here. */
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
     gi(HLG_GL_DRAW_FRAMEBUFFER, &drawFbo);
     fprintf(stderr, "[hlg-win] debug_fbo: drawFbo=%d lastFbo=%d\n", drawFbo, s_last_fbo);
 }
 
__declspec(dllexport) void hlg_read_pixels(int *viewport_out, void *pixels_out,
        int max_pixels, int req_w, int req_h) {
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

    int vp[4] = {0};
    gi(HLG_GL_VIEWPORT, vp);

    /* (3) истина о размере кадра — реальный viewport Flycast, а не хардкод.
       Иначе Java грузит текстуру другой шириной -> диагональный сдвиг. */
    int read_w = vp[2] > 0 ? vp[2] : (req_w > 0 ? req_w : s_w);
    int read_h = vp[3] > 0 ? vp[3] : (req_h > 0 ? req_h : s_h);
    if (max_pixels > 0 && read_w > 0 && (long long)read_w * read_h > max_pixels)
        read_h = max_pixels / read_w;
    if (read_w <= 0 || read_h <= 0) { LeaveCriticalSection(&s_gl_cs); return; }

    viewport_out[0] = vp[0];
    viewport_out[1] = vp[1];
    viewport_out[2] = read_w;   /* отдаём Java точные размеры, что реально прочитали */
    viewport_out[3] = read_h;

    if (rb) rb(HLG_GL_BACK);
    if (ps) ps(HLG_GL_PACK_ALIGNMENT, 4);
    if (fin && (s_read_count % 120) == 0) fin();

    /* (2) BGRA -> little-endian int = 0xAARRGGBB (ARGB), без свопа R/B в Java.
       (3) читаем из vp[0],vp[1], а не из 0,0. */
    rp(vp[0], vp[1], read_w, read_h, HLG_GL_BGRA, HLG_GL_UNSIGNED_BYTE, pixels_out);

    /* (1) GL origin = низ-лево, текстура хочет верх-лево -> переворот строк */
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
 
 __declspec(dllexport) void hlg_dump_hw_render(const void *data, int size) {
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
     parse_vmem_bounds(buf);
     fprintf(stderr, "[hlg-win][%d] %s\n", level, buf);
 }
 
 __declspec(dllexport) void *hlg_get_log_cb_ptr(void) {
     return (void *)hlg_log_cb;
 }
 
 BOOL WINAPI DllMain(HINSTANCE hinst, DWORD reason, LPVOID reserved) {
     (void)reserved;
     if (reason == DLL_PROCESS_ATTACH) {
         DisableThreadLibraryCalls(hinst);
         /* This DLL loads before the Flycast core, so the current UEF is
          * HotSpot's. Capture it now as a safety net. */
         s_jvm_filter = peek_uef();
     } else if (reason == DLL_PROCESS_DETACH && s_cs_ready) {
         DeleteCriticalSection(&s_gl_cs);
         s_cs_ready = 0;
     }
     return TRUE;
 }