/*
 * headless_gl.c — Headless EGL OpenGL contexts for libretro (Linux/WSL2, Mesa).
 *
 * Ревизия (multi-instance): синглтон убран, API выровнен с headless_gl_win.c.
 *  - hlg_create()/hlg_free() создают/уничтожают независимые инстансы:
 *    свой EGL-контекст (+пул shared-контекстов для GL Compat), свой PBuffer,
 *    свой offscreen FBO (color+depth/stencil) и двойной PBO на каждый.
 *  - Все контекстные функции принимают handle первым параметром.
 *  - get_current_framebuffer / get_proc_address у libretro НЕ имеют user-data,
 *    поэтому диспетчеризация идёт через thread-local t_cur; для worker-потоков
 *    ядер (PPSSPP), которые зовут GL, не пройдя hlg_make_current, есть
 *    атомарный fallback s_fallback (последний инстанс, сделавший make_current).
 *  - Контракт: инстанс живёт строго на своём retro-core-потоке;
 *    hlg_free(h) — только после полной остановки потоков ядра.
 *
 * Технические отличия от WGL-версии:
 *  - EGLDisplay — процессный ресурс: eglTerminate убивает контексты ВСЕХ
 *    инстансов, поэтому display рефкаунтится (s_display_refs) и терминируется
 *    только при освобождении последнего инстанса.
 *  - Выбор драйвера (HLG_GL_DRIVER: d3d12|zink|llvmpipe|auto) действует только
 *    ДО первой инициализации Mesa в процессе; повторный init с другим
 *    драйвером логирует предупреждение и продолжает на старом.
 *  - GLES поддерживается (api=1): вызовы сериализуются мьютексом инстанса
 *    через обёртки get_proc_address (PPSSPP-мультитрединг).
 *  - Desktop GL Compat (GLEW/dlsym в обход обёрток) получает пул shared-
 *    контекстов; Core — один master-контекст (одно-поточный контракт).
 *  - Если драйвер не даёт pbuffer-конфиг — фолбэк на
 *    EGL_KHR_surfaceless_context (рендер уходит в наш FBO, см. перехват
 *    glBindFramebuffer(…, 0) ниже).
 *
 * Readback: RGBA, без Y-flip (как ждёт Java-сторона Linux-бриджа),
 * асинхронно через двойной PBO (отдаётся кадр N-1) с фолбэком на
 * синхронное чтение и авто-переоткрытием FBO-источника.
 *
 * Логи: подавляются HLG_QUIET=1.
 *
 * Build:
 *   gcc -shared -fPIC -O2 -o .libheadless_gl.so headless_gl.c -lEGL -ldl -lpthread
 *   strip .libheadless_gl.so
 *   (точка в имени намеренная: jar-лоадер извлекает файл как скрытый;
 *    -lGL больше не нужен — все GL-символы грузятся динамически)
 */

 #include <EGL/egl.h>
 #include <EGL/eglext.h>
 #include <stdio.h>
 #include <stdlib.h>
 #include <string.h>
 #include <stddef.h>
 #include <stdarg.h>
 #include <stdatomic.h>
 #include <dlfcn.h>
 #include <pthread.h>
 #include <unistd.h>
 
 /* ---- EGL fallback defines ---- */
 #ifndef EGL_CONTEXT_MAJOR_VERSION_KHR
 #define EGL_CONTEXT_MAJOR_VERSION_KHR 0x3098
 #define EGL_CONTEXT_MINOR_VERSION_KHR 0x30FB
 #endif
 #ifndef EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR
 #define EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR 0x30FD
 #define EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT_KHR 0x00000001
 #define EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT_KHR 0x00000002
 #endif
 #ifndef EGL_OPENGL_ES3_BIT_KHR
 #define EGL_OPENGL_ES3_BIT_KHR 0x00000040
 #endif
 #ifndef EGL_PLATFORM_SURFACELESS_MESA
 #define EGL_PLATFORM_SURFACELESS_MESA 0x31DD
 #endif
 
 /* ---- GL enums (свои — GL/gl.h не нужен) ---- */
 #define HLG_GL_VENDOR                   0x1F00
 #define HLG_GL_RENDERER                 0x1F01
 #define HLG_GL_VERSION                  0x1F02
 #define HLG_GL_VIEWPORT                 0x0BA2
 #define HLG_GL_RGBA                     0x1908
 #define HLG_GL_UNSIGNED_BYTE            0x1401
 #define HLG_GL_BACK                     0x0405
 #define HLG_GL_FRONT                    0x0404
 #define HLG_GL_PACK_ALIGNMENT           0x0D05
 #define HLG_GL_FRAMEBUFFER              0x8D40
 #define HLG_GL_READ_FRAMEBUFFER         0x8CA8
 #define HLG_GL_DRAW_FRAMEBUFFER         0x8CA9
 /* ВНИМАНИЕ: раньше draw-binding запрашивался pname=0x8CA9 (это target, не
  * pname!) — glGetIntegerv давал INVALID_ENUM и молча возвращал мусор. */
 #define HLG_GL_FRAMEBUFFER_BINDING      0x8CA6 /* == DRAW_FRAMEBUFFER_BINDING */
 #define HLG_GL_READ_FRAMEBUFFER_BINDING 0x8CAA
 #define HLG_GL_COLOR_ATTACHMENT0        0x8CE0
 #define HLG_GL_RENDERBUFFER             0x8D41
 #define HLG_GL_RGBA8                    0x8058
 #define HLG_GL_DEPTH24_STENCIL8         0x88F0
 #define HLG_GL_DEPTH_STENCIL_ATTACHMENT 0x821A
 #define HLG_GL_FRAMEBUFFER_COMPLETE     0x8CD5
 #define HLG_GL_PIXEL_PACK_BUFFER        0x88EB
 #define HLG_GL_STREAM_READ              0x88E8
 #define HLG_GL_READ_ONLY                0x88B8
 #define HLG_GL_MAP_READ_BIT             0x0001
 
 /* ---- логирование ---- */
 static int log_enabled(void) {
     static int v = -1;
     if (v < 0) {
         const char *e = getenv("HLG_QUIET");
         v = !(e && e[0] == '1');
     }
     return v;
 }
 #define HLG_LOG(...) do { if (log_enabled()) fprintf(stderr, "[hlg] " __VA_ARGS__); } while (0)
 
 /* =========================================================================
  *  Инстанс
  * ========================================================================= */
 #define HLG_CTX_POOL   16
 #define HLG_MAX_TSURF  32
 
 typedef struct hlg_ctx {
     /* конфигурация */
     int api_mode;          /* 0 = desktop GL, 1 = GLES */
     int compat_profile;
     int gl_major, gl_minor;
     int serial_mode;       /* один master-контекст (GLES и GL Core) */
     int initialized;
     int has_display_ref;
     int w, h;
 
     /* EGL */
     EGLConfig  config;
     EGLContext context;                 /* master */
     EGLSurface surface;                 /* master pbuffer / EGL_NO_SURFACE */
     int        surfaceless;
 
     /* пул shared-контекстов (только desktop GL Compat: GLEW/dlsym мимо обёрток) */
     EGLContext pool[HLG_CTX_POOL];
     int pool_size, pool_next;
 
     /* per-thread поверхности пула — регистрируются, чтобы не утекали на destroy */
     EGLSurface tsurf[HLG_MAX_TSURF];
     int tsurf_n;
 
     /* offscreen FBO: color RGBA8 + DEPTH24_STENCIL8 (renderbuffers) */
     unsigned fbo, color_rb, depth_rb;
     int fbo_ready, fbo_w, fbo_h;
 
     /* readback: двойной PBO + запомненный источник */
     unsigned pbo[2];
     int pbo_idx, pbo_w[2], pbo_h[2], pbo_cap_w, pbo_cap_h;
     unsigned src_fbo;
     int src_valid, src_zero_frames;
     void  *scratch;
     size_t scratch_cap;
 
     /* прочее */
     pthread_mutex_t lock;  /* рекурсивный */
     unsigned epoch;        /* инвалидация TLS-привязок после destroy/reinit */
     char gpu_info[1024];
     int  gpu_logged;
     _Atomic int last_fbo;
     _Atomic int call_count;
     int read_count, gf_logs, mc_logs;
 } hlg_ctx;
 
 /* TLS-привязка потока к инстансу. tl_* валидны только пока
  * (tl_owner, tl_epoch) совпадают с живым инстансом. */
 static __thread hlg_ctx   *t_cur      = NULL;
 static __thread hlg_ctx   *tl_owner   = NULL;
 static __thread unsigned   tl_epoch   = 0;
 static __thread EGLContext tl_ctx     = EGL_NO_CONTEXT;
 static __thread EGLSurface tl_surface = EGL_NO_SURFACE;
 
 /* Fallback для worker-потоков ядра, не проходивших hlg_make_current. */
 static _Atomic(hlg_ctx *) s_fallback = NULL;
 static _Atomic unsigned   s_epoch_gen = 1;
 
 /* =========================================================================
  *  Процессные глобалы: display (refcount) и выбор драйвера
  * ========================================================================= */
 static pthread_mutex_t s_display_lock = PTHREAD_MUTEX_INITIALIZER;
 static EGLDisplay s_display = EGL_NO_DISPLAY;
 static int  s_display_refs = 0;
 static char s_active_driver[32] = "";
 
 static const char *requested_driver(void) {
     const char *d = getenv("HLG_GL_DRIVER");
     return (d && d[0]) ? d : "d3d12";
 }
 
 /* Работает только ДО первой инициализации Mesa в процессе. */
 static void apply_driver_env(const char *drv) {
     if (strcmp(drv, "llvmpipe") == 0) {
         setenv("LIBGL_ALWAYS_SOFTWARE", "1", 1);
         unsetenv("GALLIUM_DRIVER");
     } else if (strcmp(drv, "zink") == 0) {
         unsetenv("LIBGL_ALWAYS_SOFTWARE");
         setenv("GALLIUM_DRIVER", "zink", 1);
     } else if (strcmp(drv, "auto") == 0) {
         unsetenv("LIBGL_ALWAYS_SOFTWARE");
         unsetenv("GALLIUM_DRIVER");
     } else { /* d3d12 */
         unsetenv("LIBGL_ALWAYS_SOFTWARE");
         setenv("GALLIUM_DRIVER", "d3d12", 1);
         setenv("LIBGL_DRI3_DISABLE", "1", 0);
     }
     HLG_LOG("GL driver: %s\n", drv);
 }
 
 static EGLDisplay acquire_display(void) {
     pthread_mutex_lock(&s_display_lock);
     const char *drv = requested_driver();
     if (!s_active_driver[0]) {
         apply_driver_env(drv);
         snprintf(s_active_driver, sizeof(s_active_driver), "%s", drv);
     } else if (strcmp(drv, s_active_driver) != 0) {
         HLG_LOG("WARNING: HLG_GL_DRIVER=%s игнорируется — Mesa уже"
                 " инициализирована с '%s' (смена требует рестарта процесса)\n",
                 drv, s_active_driver);
     }
     if (s_display == EGL_NO_DISPLAY) {
         s_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
         if (s_display == EGL_NO_DISPLAY) {
             /* headless-фолбэк: surfaceless-платформа Mesa */
             PFNEGLGETPLATFORMDISPLAYEXTPROC gpd = (PFNEGLGETPLATFORMDISPLAYEXTPROC)
                 eglGetProcAddress("eglGetPlatformDisplayEXT");
             if (gpd)
                 s_display = gpd(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, NULL);
         }
         if (s_display == EGL_NO_DISPLAY) {
             HLG_LOG("eglGetDisplay FAILED (driver=%s)\n", s_active_driver);
             pthread_mutex_unlock(&s_display_lock);
             return EGL_NO_DISPLAY;
         }
         if (!eglInitialize(s_display, NULL, NULL)) {
             HLG_LOG("eglInitialize FAILED (driver=%s) err=0x%x\n",
                     s_active_driver, eglGetError());
             s_display = EGL_NO_DISPLAY;
             pthread_mutex_unlock(&s_display_lock);
             return EGL_NO_DISPLAY;
         }
     }
     s_display_refs++;
     EGLDisplay d = s_display;
     pthread_mutex_unlock(&s_display_lock);
     return d;
 }
 
 static void release_display(void) {
     pthread_mutex_lock(&s_display_lock);
     if (s_display_refs > 0 && --s_display_refs == 0 && s_display != EGL_NO_DISPLAY) {
         eglTerminate(s_display);
         s_display = EGL_NO_DISPLAY;
         HLG_LOG("display terminated (last instance freed)\n");
     }
     pthread_mutex_unlock(&s_display_lock);
 }
 
 static const char *egl_err_str(EGLint err) {
     switch (err) {
         case EGL_SUCCESS:       return "SUCCESS";
         case EGL_BAD_MATCH:     return "BAD_MATCH";
         case EGL_BAD_ALLOC:     return "BAD_ALLOC";
         case EGL_BAD_CONTEXT:   return "BAD_CONTEXT";
         case EGL_BAD_CONFIG:    return "BAD_CONFIG";
         case EGL_BAD_ATTRIBUTE: return "BAD_ATTRIBUTE";
         case EGL_BAD_ACCESS:    return "BAD_ACCESS";
         case EGL_BAD_SURFACE:   return "BAD_SURFACE";
         case EGL_BAD_DISPLAY:   return "BAD_DISPLAY";
         default:                return "OTHER";
     }
 }
 
 /* =========================================================================
  *  Загрузка GL-функций (кэш процессный; указатели eglGetProcAddress
  *  контекстонезависимы, dl-хендлы НИКОГДА не закрываются — ими может
  *  пользоваться другой живой инстанс/поток)
  * ========================================================================= */
 static void *load_gl(const char *name) {
     void *p = (void *)eglGetProcAddress(name);
     if (p) return p;
     static void *h_gl, *h_gles;
     static pthread_mutex_t m = PTHREAD_MUTEX_INITIALIZER;
     pthread_mutex_lock(&m);
     if (!h_gl)   h_gl   = dlopen("libGL.so.1", RTLD_LAZY);
     if (!h_gl)   h_gl   = dlopen("libGL.so", RTLD_LAZY);
     if (!h_gles) h_gles = dlopen("libGLESv2.so.2", RTLD_LAZY);
     if (!h_gles) h_gles = dlopen("libGLESv2.so", RTLD_LAZY);
     pthread_mutex_unlock(&m);
     if (h_gl)          p = dlsym(h_gl, name);
     if (!p && h_gles)  p = dlsym(h_gles, name);
     return p;
 }
 
 /* Сырые GL-точки для служебных нужд (FBO/PBO/readback) — процессный кэш. */
 static struct {
     void (*GenFramebuffers)(int, unsigned *);
     void (*BindFramebuffer)(unsigned, unsigned);
     void (*DeleteFramebuffers)(int, const unsigned *);
     void (*GenRenderbuffers)(int, unsigned *);
     void (*BindRenderbuffer)(unsigned, unsigned);
     void (*DeleteRenderbuffers)(int, const unsigned *);
     void (*RenderbufferStorage)(unsigned, unsigned, int, int);
     void (*FramebufferRenderbuffer)(unsigned, unsigned, unsigned, unsigned);
     unsigned (*CheckFramebufferStatus)(unsigned);
     void (*GenBuffers)(int, unsigned *);
     void (*BindBuffer)(unsigned, unsigned);
     void (*BufferData)(unsigned, ptrdiff_t, const void *, unsigned);
     void (*DeleteBuffers)(int, const unsigned *);
     void *(*MapBuffer)(unsigned, unsigned);
     void *(*MapBufferRange)(unsigned, ptrdiff_t, ptrdiff_t, unsigned);
     unsigned char (*UnmapBuffer)(unsigned);
     void (*GetIntegerv)(unsigned, int *);
     void (*ReadPixels)(int, int, int, int, unsigned, unsigned, void *);
     void (*ReadBuffer)(unsigned);
     void (*PixelStorei)(unsigned, int);
     void (*Viewport)(int, int, int, int);
     void (*Flush)(void);
     void (*Finish)(void);
     const unsigned char *(*GetString)(unsigned);
 } GL;
 static int s_base_ok, s_fbo_ok, s_pbo_ok;
 static pthread_mutex_t s_procs_lock = PTHREAD_MUTEX_INITIALIZER;
 
 static int ensure_base_procs(void) {
     if (s_base_ok) return 1;
     pthread_mutex_lock(&s_procs_lock);
     if (!s_base_ok) {
         GL.GetIntegerv = (void (*)(unsigned, int *))load_gl("glGetIntegerv");
         GL.ReadPixels  = (void (*)(int, int, int, int, unsigned, unsigned, void *))load_gl("glReadPixels");
         GL.ReadBuffer  = (void (*)(unsigned))load_gl("glReadBuffer"); /* нет в GLES2 — опционально */
         GL.PixelStorei = (void (*)(unsigned, int))load_gl("glPixelStorei");
         GL.Viewport    = (void (*)(int, int, int, int))load_gl("glViewport");
         GL.Flush       = (void (*)(void))load_gl("glFlush");
         GL.Finish      = (void (*)(void))load_gl("glFinish");
         GL.GetString   = (const unsigned char *(*)(unsigned))load_gl("glGetString");
         s_base_ok = (GL.GetIntegerv && GL.ReadPixels && GL.Viewport) ? 1 : 0;
         if (!s_base_ok) HLG_LOG("base GL functions unavailable\n");
     }
     pthread_mutex_unlock(&s_procs_lock);
     return s_base_ok;
 }
 
 static int ensure_fbo_procs(void) {
     if (s_fbo_ok) return 1;
     pthread_mutex_lock(&s_procs_lock);
     if (!s_fbo_ok) {
         GL.GenFramebuffers         = (void (*)(int, unsigned *))load_gl("glGenFramebuffers");
         GL.BindFramebuffer         = (void (*)(unsigned, unsigned))load_gl("glBindFramebuffer");
         GL.DeleteFramebuffers      = (void (*)(int, const unsigned *))load_gl("glDeleteFramebuffers");
         GL.GenRenderbuffers        = (void (*)(int, unsigned *))load_gl("glGenRenderbuffers");
         GL.BindRenderbuffer        = (void (*)(unsigned, unsigned))load_gl("glBindRenderbuffer");
         GL.DeleteRenderbuffers     = (void (*)(int, const unsigned *))load_gl("glDeleteRenderbuffers");
         GL.RenderbufferStorage     = (void (*)(unsigned, unsigned, int, int))load_gl("glRenderbufferStorage");
         GL.FramebufferRenderbuffer = (void (*)(unsigned, unsigned, unsigned, unsigned))load_gl("glFramebufferRenderbuffer");
         GL.CheckFramebufferStatus  = (unsigned (*)(unsigned))load_gl("glCheckFramebufferStatus");
         s_fbo_ok = (GL.GenFramebuffers && GL.BindFramebuffer && GL.DeleteFramebuffers &&
                     GL.GenRenderbuffers && GL.BindRenderbuffer && GL.DeleteRenderbuffers &&
                     GL.RenderbufferStorage && GL.FramebufferRenderbuffer &&
                     GL.CheckFramebufferStatus) ? 1 : 0;
         if (!s_fbo_ok) HLG_LOG("FBO functions unavailable — falling back to default FB\n");
     }
     pthread_mutex_unlock(&s_procs_lock);
     return s_fbo_ok;
 }
 
 static int ensure_pbo_procs(void) {
     if (s_pbo_ok) return 1;
     pthread_mutex_lock(&s_procs_lock);
     if (!s_pbo_ok) {
         GL.GenBuffers     = (void (*)(int, unsigned *))load_gl("glGenBuffers");
         GL.BindBuffer     = (void (*)(unsigned, unsigned))load_gl("glBindBuffer");
         GL.BufferData     = (void (*)(unsigned, ptrdiff_t, const void *, unsigned))load_gl("glBufferData");
         GL.DeleteBuffers  = (void (*)(int, const unsigned *))load_gl("glDeleteBuffers");
         GL.MapBuffer      = (void *(*)(unsigned, unsigned))load_gl("glMapBuffer");
         GL.MapBufferRange = (void *(*)(unsigned, ptrdiff_t, ptrdiff_t, unsigned))load_gl("glMapBufferRange");
         GL.UnmapBuffer    = (unsigned char (*)(unsigned))load_gl("glUnmapBuffer");
         s_pbo_ok = (GL.GenBuffers && GL.BindBuffer && GL.BufferData && GL.DeleteBuffers &&
                     (GL.MapBuffer || GL.MapBufferRange) && GL.UnmapBuffer) ? 1 : 0;
         if (!s_pbo_ok) HLG_LOG("PBO functions unavailable — sync readback\n");
     }
     pthread_mutex_unlock(&s_procs_lock);
     return s_pbo_ok;
 }
 
 /* =========================================================================
  *  Привязка контекста к потоку (вызывать ТОЛЬКО под c->lock)
  * ========================================================================= */
 static int register_thread_surface(hlg_ctx *c, EGLSurface s) {
     if (c->tsurf_n < HLG_MAX_TSURF) { c->tsurf[c->tsurf_n++] = s; return 1; }
     return 0;
 }
 
 static int ensure_current(hlg_ctx *c) {
     if (!c->initialized) return 0;
     /* быстрый путь: этот поток уже корректно привязан */
     if (tl_owner == c && tl_epoch == c->epoch && tl_ctx != EGL_NO_CONTEXT &&
         eglGetCurrentContext() == tl_ctx) {
         t_cur = c;
         return 1;
     }
     /* устаревшая привязка (умерший/переинициализированный инстанс):
        хендлы уже уничтожены вместе с ним — просто забываем */
     if (tl_owner && (tl_owner != c || tl_epoch != c->epoch)) {
         tl_ctx = EGL_NO_CONTEXT;
         tl_surface = EGL_NO_SURFACE;
         tl_owner = NULL;
     }
 
     eglMakeCurrent(s_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
 
     if (c->serial_mode) {
         tl_ctx = c->context;
         tl_surface = c->surface;
     } else {
         /* пул: своя поверхность + свой shared-контекст на поток */
         if (tl_surface == EGL_NO_SURFACE && !c->surfaceless) {
             EGLint pa[] = { EGL_WIDTH, c->w, EGL_HEIGHT, c->h, EGL_NONE };
             tl_surface = eglCreatePbufferSurface(s_display, c->config, pa);
             if (tl_surface == EGL_NO_SURFACE) {
                 HLG_LOG("ctx %p: per-thread pbuffer FAILED err=0x%x\n",
                         (void *)c, eglGetError());
                 return 0;
             }
             if (!register_thread_surface(c, tl_surface))
                 HLG_LOG("ctx %p: tsurf table full — поверхность утечёт на destroy\n", (void *)c);
         }
         if (tl_ctx == EGL_NO_CONTEXT) {
             if (c->pool_next < c->pool_size)
                 tl_ctx = c->pool[c->pool_next++];
             else {
                 EGLint inherit[] = { EGL_NONE };
                 tl_ctx = eglCreateContext(s_display, c->config, c->context, inherit);
                 if (tl_ctx == EGL_NO_CONTEXT) {
                     EGLint err = eglGetError();
                     HLG_LOG("ctx %p: shared context FAILED 0x%x %s — reuse master\n",
                             (void *)c, err, egl_err_str(err));
                     tl_ctx = c->context;
                 }
             }
         }
     }
 
     EGLSurface surf = c->serial_mode ? c->surface : tl_surface;
     if (c->surfaceless) surf = EGL_NO_SURFACE;
     if (!eglMakeCurrent(s_display, surf, surf, tl_ctx)) {
         EGLint err = eglGetError();
         if (c->mc_logs < 20) {
             HLG_LOG("ctx %p: eglMakeCurrent FAILED 0x%x %s\n", (void *)c, err, egl_err_str(err));
             c->mc_logs++;
         }
         tl_ctx = EGL_NO_CONTEXT;
         return 0;
     }
     tl_owner = c;
     tl_epoch = c->epoch;
     t_cur = c;
     atomic_store(&s_fallback, c);
     if (c->mc_logs < 4) {
         HLG_LOG("ctx %p: bound to thread (%s, %dx%d)\n", (void *)c,
                 c->serial_mode ? "serial" : "pool", c->w, c->h);
         c->mc_logs++;
     }
     return 1;
 }
 
 /* wgl_begin/wgl_end — единая точка входа для всех GL-операций слоя.
  * Контекст остаётся current на потоке после wgl_end — быстрый путь
  * для следующего вызова. Мьютекс рекурсивный. */
 static hlg_ctx *wgl_begin(void) {
     hlg_ctx *c = t_cur ? t_cur : atomic_load(&s_fallback);
     if (!c) return NULL;
     pthread_mutex_lock(&c->lock);
     if (!c->initialized || !ensure_current(c)) {
         pthread_mutex_unlock(&c->lock);
         return NULL;
     }
     return c;
 }
 static void wgl_end(hlg_ctx *c) {
     pthread_mutex_unlock(&c->lock);
 }
 
 /* =========================================================================
  *  Offscreen FBO: color RGBA8 + DEPTH24_STENCIL8 (под lock, контекст current)
  * ========================================================================= */
 static int create_offscreen_fbo(hlg_ctx *c, int w, int h) {
     if (!ensure_fbo_procs()) return 0;
     if (!c->fbo)      GL.GenFramebuffers(1, &c->fbo);
     if (!c->color_rb) GL.GenRenderbuffers(1, &c->color_rb);
     if (!c->depth_rb) GL.GenRenderbuffers(1, &c->depth_rb);
     GL.BindFramebuffer(HLG_GL_FRAMEBUFFER, c->fbo);
     GL.BindRenderbuffer(HLG_GL_RENDERBUFFER, c->color_rb);
     GL.RenderbufferStorage(HLG_GL_RENDERBUFFER, HLG_GL_RGBA8, w, h);
     GL.FramebufferRenderbuffer(HLG_GL_FRAMEBUFFER, HLG_GL_COLOR_ATTACHMENT0,
                                HLG_GL_RENDERBUFFER, c->color_rb);
     GL.BindRenderbuffer(HLG_GL_RENDERBUFFER, c->depth_rb);
     GL.RenderbufferStorage(HLG_GL_RENDERBUFFER, HLG_GL_DEPTH24_STENCIL8, w, h);
     GL.FramebufferRenderbuffer(HLG_GL_FRAMEBUFFER, HLG_GL_DEPTH_STENCIL_ATTACHMENT,
                                HLG_GL_RENDERBUFFER, c->depth_rb);
     unsigned st = GL.CheckFramebufferStatus(HLG_GL_FRAMEBUFFER);
     c->fbo_ready = (st == HLG_GL_FRAMEBUFFER_COMPLETE);
     c->fbo_w = w;
     c->fbo_h = h;
     if (!c->fbo_ready)
         HLG_LOG("ctx %p: FBO incomplete (0x%x) — using default FB\n", (void *)c, st);
     else
         HLG_LOG("ctx %p: offscreen FBO %u ready (%dx%d)\n", (void *)c, c->fbo, w, h);
     /* рендер по умолчанию — в наш FBO (важно для surfaceless-режима) */
     GL.BindFramebuffer(HLG_GL_FRAMEBUFFER, c->fbo_ready ? c->fbo : 0);
     return c->fbo_ready;
 }
 
 static void resize_offscreen_fbo(hlg_ctx *c, int w, int h) {
     if (!c->fbo_ready || (w == c->fbo_w && h == c->fbo_h)) return;
     GL.BindRenderbuffer(HLG_GL_RENDERBUFFER, c->color_rb);
     GL.RenderbufferStorage(HLG_GL_RENDERBUFFER, HLG_GL_RGBA8, w, h);
     GL.BindRenderbuffer(HLG_GL_RENDERBUFFER, c->depth_rb);
     GL.RenderbufferStorage(HLG_GL_RENDERBUFFER, HLG_GL_DEPTH24_STENCIL8, w, h);
     GL.BindFramebuffer(HLG_GL_FRAMEBUFFER, c->fbo);
     c->fbo_w = w;
     c->fbo_h = h;
     HLG_LOG("ctx %p: FBO resized %dx%d\n", (void *)c, w, h);
 }
 
 static void destroy_offscreen_fbo(hlg_ctx *c) {
     if (s_fbo_ok) {
         if (c->color_rb) GL.DeleteRenderbuffers(1, &c->color_rb);
         if (c->depth_rb) GL.DeleteRenderbuffers(1, &c->depth_rb);
         if (c->fbo)      GL.DeleteFramebuffers(1, &c->fbo);
     }
     c->fbo = c->color_rb = c->depth_rb = 0;
     c->fbo_ready = c->fbo_w = c->fbo_h = 0;
 }
 
 static void destroy_pbos(hlg_ctx *c) {
     if (s_pbo_ok && c->pbo[0]) GL.DeleteBuffers(2, c->pbo);
     c->pbo[0] = c->pbo[1] = 0;
     c->pbo_w[0] = c->pbo_w[1] = c->pbo_h[0] = c->pbo_h[1] = 0;
     c->pbo_cap_w = c->pbo_cap_h = 0;
     c->pbo_idx = 0;
 }
 
 static int ensure_pbo_buffers(hlg_ctx *c, int w, int h) {
     if (!ensure_pbo_procs() || w <= 0 || h <= 0) return 0;
     if (!c->pbo[0]) {
         GL.GenBuffers(2, c->pbo);
         if (!c->pbo[0]) return 0;
         c->pbo_idx = 0;
         c->pbo_w[0] = c->pbo_w[1] = c->pbo_h[0] = c->pbo_h[1] = 0;
         c->pbo_cap_w = c->pbo_cap_h = 0;
     }
     if (w > c->pbo_cap_w || h > c->pbo_cap_h) {
         size_t bytes = (size_t)w * (size_t)h * 4;
         for (int i = 0; i < 2; ++i) {
             GL.BindBuffer(HLG_GL_PIXEL_PACK_BUFFER, c->pbo[i]);
             GL.BufferData(HLG_GL_PIXEL_PACK_BUFFER, (ptrdiff_t)bytes, NULL, HLG_GL_STREAM_READ);
         }
         GL.BindBuffer(HLG_GL_PIXEL_PACK_BUFFER, 0);
         c->pbo_cap_w = w;
         c->pbo_cap_h = h;
         c->pbo_w[0] = c->pbo_w[1] = c->pbo_h[0] = c->pbo_h[1] = 0;
         c->pbo_idx = 0;
     }
     return 1;
 }
 
 static void *map_pack_buffer(size_t bytes) {
     if (GL.MapBuffer)      /* desktop GL */
         return GL.MapBuffer(HLG_GL_PIXEL_PACK_BUFFER, HLG_GL_READ_ONLY);
     if (GL.MapBufferRange) /* GLES3 */
         return GL.MapBufferRange(HLG_GL_PIXEL_PACK_BUFFER, 0,
                                  (ptrdiff_t)bytes, HLG_GL_MAP_READ_BIT);
     return NULL;
 }
 
 static void *ensure_scratch(hlg_ctx *c, size_t bytes) {
     if (c->scratch_cap < bytes) {
         void *nb = realloc(c->scratch, bytes);
         if (!nb) return NULL;
         c->scratch = nb;
         c->scratch_cap = bytes;
     }
     return c->scratch;
 }
 
 /* =========================================================================
  *  GPU identity (под lock, контекст current)
  * ========================================================================= */
 static int is_software_renderer(const char *renderer) {
     if (!renderer || !renderer[0]) return 1;
     return strstr(renderer, "llvmpipe") != NULL
         || strstr(renderer, "softpipe") != NULL
         || strstr(renderer, "lavapipe") != NULL
         || strstr(renderer, "SwiftShader") != NULL
         || strstr(renderer, "Software") != NULL
         || strstr(renderer, "software") != NULL;
 }
 
 static void log_gpu_identity(hlg_ctx *c) {
     if (c->gpu_logged || !GL.GetString) return;
     const char *vendor   = (const char *)GL.GetString(HLG_GL_VENDOR);
     const char *renderer = (const char *)GL.GetString(HLG_GL_RENDERER);
     const char *version  = (const char *)GL.GetString(HLG_GL_VERSION);
     if (!vendor)   vendor   = "(null)";
     if (!renderer) renderer = "(null)";
     if (!version)  version  = "(null)";
     const char *gallium = getenv("GALLIUM_DRIVER");
     const char *sw_env  = getenv("LIBGL_ALWAYS_SOFTWARE");
     int dxg = access("/dev/dxg", F_OK) == 0;
     int software = is_software_renderer(renderer) || (sw_env && sw_env[0] == '1');
     HLG_LOG("========== GPU IDENTITY (ctx %p) ==========\n", (void *)c);
     HLG_LOG("driver=%s GALLIUM_DRIVER=%s LIBGL_ALWAYS_SOFTWARE=%s /dev/dxg=%s\n",
             s_active_driver, gallium ? gallium : "(unset)",
             sw_env ? sw_env : "(unset)", dxg ? "present" : "missing");
     HLG_LOG("GL_VENDOR:   %s\n", vendor);
     HLG_LOG("GL_RENDERER: %s\n", renderer);
     HLG_LOG("GL_VERSION:  %s\n", version);
     if (software) {
         HLG_LOG(">>> SOFTWARE RENDERER — NOT using discrete GPU <<<\n");
         snprintf(c->gpu_info, sizeof(c->gpu_info), "SOFTWARE (%s) — NOT discrete GPU", renderer);
     } else {
         HLG_LOG(">>> HARDWARE GPU — rendering on: %s <<<\n", renderer);
         snprintf(c->gpu_info, sizeof(c->gpu_info), "HARDWARE GPU: %s (%s)", renderer, vendor);
     }
     c->gpu_logged = 1;
 }
 
 /* =========================================================================
  *  GLES-обёртки (сериализация PPSSPP-мультитрединга).
  *  Генерируются X-макросами (было ~90 ручных + gles_wrappers.inc).
  *  Используются только при api_mode == 1: desktop-ядра получают сырые
  *  указатели (мьютекс на каждый draw дорог, а GLEW/dlsym всё равно
  *  обходит get_proc_address — им пул контекстов, см. init_ctx_pool).
  * ========================================================================= */
 #define HLG_WRAPS_V(V) \
     V(glGetIntegerv, (unsigned pname, int *data), (pname, data)) \
     V(glViewport, (int x, int y, int w, int h), (x, y, w, h)) \
     V(glClear, (unsigned mask), (mask)) \
     V(glClearColor, (float r, float g, float b, float a), (r, g, b, a)) \
     V(glEnable, (unsigned cap), (cap)) \
     V(glDisable, (unsigned cap), (cap)) \
     V(glGenTextures, (int n, unsigned *ids), (n, ids)) \
     V(glBindTexture, (unsigned t, unsigned id), (t, id)) \
     V(glTexParameteri, (unsigned t, unsigned p, int v), (t, p, v)) \
     V(glTexImage2D, (unsigned t, int l, int ifmt, int w, int h, int b, unsigned f, unsigned ty, const void *px), (t, l, ifmt, w, h, b, f, ty, px)) \
     V(glTexSubImage2D, (unsigned t, int l, int x, int y, int w, int h, unsigned f, unsigned ty, const void *px), (t, l, x, y, w, h, f, ty, px)) \
     V(glActiveTexture, (unsigned u), (u)) \
     V(glShaderSource, (unsigned s, int n, const char *const *src, const int *len), (s, n, src, len)) \
     V(glCompileShader, (unsigned s), (s)) \
     V(glAttachShader, (unsigned p, unsigned s), (p, s)) \
     V(glLinkProgram, (unsigned p), (p)) \
     V(glUseProgram, (unsigned p), (p)) \
     V(glGetShaderiv, (unsigned s, unsigned pn, int *pr), (s, pn, pr)) \
     V(glGetProgramiv, (unsigned p, unsigned pn, int *pr), (p, pn, pr)) \
     V(glGetShaderInfoLog, (unsigned s, int cap, int *len, char *lg), (s, cap, len, lg)) \
     V(glGetProgramInfoLog, (unsigned p, int cap, int *len, char *lg), (p, cap, len, lg)) \
     V(glBindAttribLocation, (unsigned p, unsigned i, const char *nm), (p, i, nm)) \
     V(glGenBuffers, (int n, unsigned *ids), (n, ids)) \
     V(glBindBuffer, (unsigned t, unsigned b), (t, b)) \
     V(glBufferData, (unsigned t, ptrdiff_t sz, const void *d, unsigned u), (t, sz, d, u)) \
     V(glBufferSubData, (unsigned t, ptrdiff_t off, ptrdiff_t sz, const void *d), (t, off, sz, d)) \
     V(glGenVertexArrays, (int n, unsigned *ids), (n, ids)) \
     V(glBindVertexArray, (unsigned a), (a)) \
     V(glEnableVertexAttribArray, (unsigned i), (i)) \
     V(glDisableVertexAttribArray, (unsigned i), (i)) \
     V(glVertexAttribPointer, (unsigned i, int sz, unsigned t, unsigned char nrm, int str, const void *p), (i, sz, t, nrm, str, p)) \
     V(glUniform1i, (int l, int v), (l, v)) \
     V(glUniform1f, (int l, float v), (l, v)) \
     V(glUniform2f, (int l, float a, float b), (l, a, b)) \
     V(glUniform4f, (int l, float a, float b, float c2, float d), (l, a, b, c2, d)) \
     V(glUniform4fv, (int l, int n, const float *v), (l, n, v)) \
     V(glUniformMatrix4fv, (int l, int n, unsigned char tr, const float *v), (l, n, tr, v)) \
     V(glDrawArrays, (unsigned m, int f, int n), (m, f, n)) \
     V(glDrawElements, (unsigned m, int n, unsigned t, const void *i), (m, n, t, i)) \
     V(glFlush, (void), ()) \
     V(glFinish, (void), ()) \
     V(glBlitFramebuffer, (int a, int b, int c2, int d, int e, int f, int g, int h2, unsigned m, unsigned fl), (a, b, c2, d, e, f, g, h2, m, fl)) \
     V(glGenFramebuffers, (int n, unsigned *ids), (n, ids)) \
     V(glFramebufferTexture2D, (unsigned t, unsigned at, unsigned tt, unsigned tex, int l), (t, at, tt, tex, l)) \
     V(glFramebufferTexture, (unsigned t, unsigned at, unsigned tex, int l), (t, at, tex, l)) \
     V(glFramebufferRenderbuffer, (unsigned t, unsigned at, unsigned rt, unsigned rb), (t, at, rt, rb)) \
     V(glGenRenderbuffers, (int n, unsigned *ids), (n, ids)) \
     V(glBindRenderbuffer, (unsigned t, unsigned rb), (t, rb)) \
     V(glRenderbufferStorage, (unsigned t, unsigned f, int w, int h), (t, f, w, h)) \
     V(glDeleteTextures, (int n, const unsigned *ids), (n, ids)) \
     V(glDeleteShader, (unsigned s), (s)) \
     V(glDeleteProgram, (unsigned p), (p)) \
     V(glDeleteBuffers, (int n, const unsigned *ids), (n, ids)) \
     V(glDeleteFramebuffers, (int n, const unsigned *ids), (n, ids)) \
     V(glDeleteVertexArrays, (int n, const unsigned *ids), (n, ids)) \
     V(glDeleteRenderbuffers, (int n, const unsigned *ids), (n, ids)) \
     V(glGenerateMipmap, (unsigned t), (t)) \
     V(glBlendFunc, (unsigned s, unsigned d), (s, d)) \
     V(glBlendFuncSeparate, (unsigned a, unsigned b, unsigned c2, unsigned d), (a, b, c2, d)) \
     V(glDepthFunc, (unsigned f), (f)) \
     V(glDepthMask, (unsigned char f), (f)) \
     V(glScissor, (int x, int y, int w, int h), (x, y, w, h)) \
     V(glPixelStorei, (unsigned p, int v), (p, v)) \
     V(glCullFace, (unsigned m), (m)) \
     V(glBindBufferBase, (unsigned t, unsigned i, unsigned b), (t, i, b)) \
     V(glDrawBuffer, (unsigned b), (b)) \
     V(glReadBuffer, (unsigned b), (b)) \
     V(glHint, (unsigned t, unsigned m), (t, m)) \
     V(glPolygonMode, (unsigned f, unsigned m), (f, m)) \
     V(glPointSize, (float s), (s)) \
     V(glColorMask, (unsigned char r, unsigned char g, unsigned char b, unsigned char a), (r, g, b, a)) \
     V(glDepthRange, (double n, double f), (n, f)) \
     V(glFrontFace, (unsigned m), (m)) \
     V(glLineWidth, (float w), (w)) \
     V(glStencilFunc, (unsigned f, int r, unsigned m), (f, r, m)) \
     V(glStencilMask, (unsigned m), (m)) \
     V(glStencilOp, (unsigned a, unsigned b, unsigned c2), (a, b, c2)) \
     V(glReadPixels, (int x, int y, int w, int h, unsigned f, unsigned t, void *d), (x, y, w, h, f, t, d))
 
 #define HLG_WRAPS_R(R) \
     R(unsigned, 0, glGetError, (void), ()) \
     R(unsigned, 0, glCreateShader, (unsigned t), (t)) \
     R(unsigned, 0, glCreateProgram, (void), ()) \
     R(int, -1, glGetAttribLocation, (unsigned p, const char *n), (p, n)) \
     R(int, -1, glGetUniformLocation, (unsigned p, const char *n), (p, n)) \
     R(void *, NULL, glMapBufferRange, (unsigned t, ptrdiff_t off, ptrdiff_t len, unsigned acc), (t, off, len, acc)) \
     R(unsigned char, 0, glUnmapBuffer, (unsigned t), (t)) \
     R(unsigned, 0, glCheckFramebufferStatus, (unsigned t), (t))
 
 #define HLG_DEF_WRAP_V(name, PARAMS, ARGS) \
     static void (*real_##name) PARAMS; \
     static void wrap_##name PARAMS { \
         hlg_ctx *c = wgl_begin(); \
         if (!c) return; \
         if (real_##name) real_##name ARGS; \
         wgl_end(c); \
     }
 HLG_WRAPS_V(HLG_DEF_WRAP_V)
 #undef HLG_DEF_WRAP_V
 
 #define HLG_DEF_WRAP_R(RET, ZERO, name, PARAMS, ARGS) \
     static RET (*real_##name) PARAMS; \
     static RET wrap_##name PARAMS { \
         hlg_ctx *c = wgl_begin(); \
         if (!c) return ZERO; \
         RET r = real_##name ? real_##name ARGS : ZERO; \
         wgl_end(c); \
         return r; \
     }
 HLG_WRAPS_R(HLG_DEF_WRAP_R)
 #undef HLG_DEF_WRAP_R
 
 /* glGetString/glGetStringi: указатель драйвера может протухнуть после
  * смены контекста другим потоком, а GL_EXTENSIONS бывает >10КБ —
  * динамическое TLS-кольцо из 4 буферов (без обрезания, как было с 512б). */
 static const unsigned char *copy_to_tls(const unsigned char *r) {
     static __thread char  *buf[4];
     static __thread size_t cap[4];
     static __thread int    slot;
     if (!r) return NULL;
     size_t need = strlen((const char *)r) + 1;
     int i = slot = (slot + 1) & 3;
     if (need > cap[i]) {
         char *nb = (char *)realloc(buf[i], need);
         if (!nb) return r; /* лучше рискованный указатель, чем обрезание */
         buf[i] = nb;
         cap[i] = need;
     }
     memcpy(buf[i], r, need);
     return (const unsigned char *)buf[i];
 }
 
 static const unsigned char *(*real_glGetString2)(unsigned);
 static const unsigned char *wrap_glGetString(unsigned name) {
     hlg_ctx *c = wgl_begin();
     if (!c) return NULL;
     const unsigned char *r = real_glGetString2 ? real_glGetString2(name) : NULL;
     r = copy_to_tls(r);
     wgl_end(c);
     return r;
 }
 
 static const unsigned char *(*real_glGetStringi2)(unsigned, unsigned);
 static const unsigned char *wrap_glGetStringi(unsigned name, unsigned index) {
     hlg_ctx *c = wgl_begin();
     if (!c) return NULL;
     const unsigned char *r = real_glGetStringi2 ? real_glGetStringi2(name, index) : NULL;
     r = copy_to_tls(r);
     wgl_end(c);
     return r;
 }
 
 /* glBindFramebuffer: трекинг + перехват default FB (0 → наш offscreen FBO).
  * Единственная обёртка, которую получают ВСЕ режимы (libretro HW-render
  * гарантированно берёт её через get_proc_address). Благодаря этому
  * рендер детерминированно попадает в наш FBO даже в surfaceless. */
 static void (*real_glBindFramebuffer2)(unsigned, unsigned);
 static void wrap_glBindFramebuffer(unsigned target, unsigned fbo) {
     hlg_ctx *c = wgl_begin();
     if (!c) return;
     unsigned eff = fbo;
     if (fbo == 0 && c->fbo_ready) eff = c->fbo;
     if (!real_glBindFramebuffer2)
         real_glBindFramebuffer2 = (void (*)(unsigned, unsigned))load_gl("glBindFramebuffer");
     if (real_glBindFramebuffer2) real_glBindFramebuffer2(target, eff);
     if (target == HLG_GL_FRAMEBUFFER || target == HLG_GL_DRAW_FRAMEBUFFER ||
         target == HLG_GL_READ_FRAMEBUFFER)
         atomic_store(&c->last_fbo, (int)eff);
     wgl_end(c);
 }
 
 /* таблица имя → (обёртка, слот real-указателя) — генерируется из тех же X-макросов */
 typedef struct { const char *name; void *wrap; void **real; } wrap_ent;
 static const wrap_ent s_wrap_table[] = {
 #define HLG_ENT_V(name, PARAMS, ARGS) { #name, (void *)wrap_##name, (void **)&real_##name },
     HLG_WRAPS_V(HLG_ENT_V)
 #undef HLG_ENT_V
 #define HLG_ENT_R(RET, ZERO, name, PARAMS, ARGS) { #name, (void *)wrap_##name, (void **)&real_##name },
     HLG_WRAPS_R(HLG_ENT_R)
 #undef HLG_ENT_R
     { "glGetString",  (void *)wrap_glGetString,  (void **)&real_glGetString2  },
     { "glGetStringi", (void *)wrap_glGetStringi, (void **)&real_glGetStringi2 },
 };
 
 static void *gles_wrap_sym(const char *sym, void *real) {
     for (size_t i = 0; i < sizeof(s_wrap_table) / sizeof(s_wrap_table[0]); ++i) {
         if (strcmp(sym, s_wrap_table[i].name) == 0) {
             *s_wrap_table[i].real = real;
             return s_wrap_table[i].wrap;
         }
     }
     static _Atomic int raw_logs;
     if (atomic_fetch_add(&raw_logs, 1) < 100)
         HLG_LOG("GLES RAW (unwrapped): %s @ %p\n", sym, real);
     return real; /* NULL-стабов нет: заглушки ломают инициализацию PPSSPP */
 }
 
 /* =========================================================================
  *  Коллбеки для ядра — БЕЗ handle, диспетчеризация через t_cur/s_fallback
  * ========================================================================= */
 __attribute__((visibility("default")))
 void *hlg_get_proc_address(const char *sym) {
     if (!sym) return NULL;
     hlg_ctx *c = t_cur ? t_cur : atomic_load(&s_fallback);
     if (c) {
         int n = atomic_fetch_add(&c->call_count, 1) + 1;
         if (n <= 20) HLG_LOG("get_proc_address(\"%s\") call #%d\n", sym, n);
     }
     if (strcmp(sym, "glBindFramebuffer") == 0 || strcmp(sym, "glBindFramebufferEXT") == 0)
         return (void *)wrap_glBindFramebuffer;
     void *p = load_gl(sym);
     if (!p) {
         if (!c || atomic_load(&c->call_count) < 200)
             HLG_LOG("get_proc_address(\"%s\") UNRESOLVED\n", sym);
         return NULL; /* NULL, не заглушка */
     }
     if (c && c->api_mode == 1)
         return gles_wrap_sym(sym, p);
     return p;
 }
 
 __attribute__((visibility("default")))
 unsigned long hlg_get_framebuffer(void) {
     hlg_ctx *c = t_cur ? t_cur : atomic_load(&s_fallback);
     if (!c) return 0;
     unsigned fbo = c->fbo_ready ? c->fbo : 0;
     if (c->gf_logs < 8) {
         HLG_LOG("get_framebuffer() -> %u (ctx %p)\n", fbo, (void *)c);
         c->gf_logs++;
     }
     return (unsigned long)fbo;
 }
 
 __attribute__((visibility("default"))) void *hlg_get_framebuffer_ptr(void)  { return (void *)hlg_get_framebuffer; }
 __attribute__((visibility("default"))) void *hlg_get_proc_address_ptr(void) { return (void *)hlg_get_proc_address; }
 
 /* =========================================================================
  *  Создание контекста
  * ========================================================================= */
 static const struct { int major, minor; } s_gl_versions[] = {
     { 4, 5 }, { 4, 3 }, { 4, 1 }, { 3, 3 }
 };
 
 static void fill_ctx_attribs(hlg_ctx *c, EGLint *out, int major, int minor) {
     int i = 0;
     if (c->api_mode == 1) {
         out[i++] = EGL_CONTEXT_CLIENT_VERSION;
         out[i++] = major;
     } else {
         out[i++] = EGL_CONTEXT_MAJOR_VERSION_KHR;
         out[i++] = major;
         out[i++] = EGL_CONTEXT_MINOR_VERSION_KHR;
         out[i++] = minor;
         /* профили существуют только с GL 3.2 — на 3.0/3.1 маска даёт EGL_BAD_MATCH */
         if (major > 3 || (major == 3 && minor >= 2)) {
             out[i++] = EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR;
             out[i++] = c->compat_profile
                        ? EGL_CONTEXT_OPENGL_COMPATIBILITY_PROFILE_BIT_KHR
                        : EGL_CONTEXT_OPENGL_CORE_PROFILE_BIT_KHR;
         }
     }
     out[i] = EGL_NONE;
 }
 
 static EGLContext try_create_context(hlg_ctx *c, int major, int minor) {
     EGLint attribs[10];
     fill_ctx_attribs(c, attribs, major, minor);
     EGLContext ctx = eglCreateContext(s_display, c->config, EGL_NO_CONTEXT, attribs);
     if (ctx == EGL_NO_CONTEXT) {
         EGLint err = eglGetError();
         HLG_LOG("eglCreateContext(%s %d.%d) failed: 0x%x %s\n",
                 c->api_mode ? "ES" : "GL", major, minor, err, egl_err_str(err));
     }
     return ctx;
 }
 
 static int choose_config(hlg_ctx *c) {
     EGLint n = 0;
     EGLint attribs[] = {
         EGL_SURFACE_TYPE,    EGL_PBUFFER_BIT,
         EGL_RENDERABLE_TYPE, (c->api_mode == 1) ? EGL_OPENGL_ES3_BIT_KHR : EGL_OPENGL_BIT,
         EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
         EGL_DEPTH_SIZE, 24, EGL_STENCIL_SIZE, 8,
         EGL_NONE
     };
     c->surfaceless = 0;
     if (eglChooseConfig(s_display, attribs, &c->config, 1, &n) && n > 0)
         return 1;
     if (c->api_mode == 1) {
         attribs[3] = EGL_OPENGL_ES2_BIT;
         if (eglChooseConfig(s_display, attribs, &c->config, 1, &n) && n > 0)
             return 1;
         attribs[3] = EGL_OPENGL_ES3_BIT_KHR;
     }
     /* pbuffer-конфигов нет — пробуем surfaceless (рендер только в наш FBO) */
     const char *ext = eglQueryString(s_display, EGL_EXTENSIONS);
     if (ext && strstr(ext, "EGL_KHR_surfaceless_context")) {
         attribs[1] = 0; /* EGL_SURFACE_TYPE: без требований */
         if (eglChooseConfig(s_display, attribs, &c->config, 1, &n) && n > 0) {
             c->surfaceless = 1;
             HLG_LOG("no pbuffer configs — using EGL_KHR_surfaceless_context\n");
             return 1;
         }
     }
     HLG_LOG("eglChooseConfig FAILED err=0x%x num=%d\n", eglGetError(), n);
     return 0;
 }
 
 static void init_ctx_pool(hlg_ctx *c) {
     /* Пул shared-контекстов нужен только desktop GL Compat (Flycast/GLEW:
      * dlsym обходит обёртки, сериализовать нельзя — каждому потоку свой
      * контекст). GL Core и GLES — serial-режим: один master-контекст,
      * т.к. на Mesa/d3d12 pooled-контексты получают другой профиль и
      * readback видит пустые FBO. */
     c->pool_size = 0;
     c->pool_next = 0;
     if (c->serial_mode) return;
     EGLint inherit[] = { EGL_NONE };
     for (int i = 0; i < HLG_CTX_POOL; ++i) {
         EGLContext ctx = eglCreateContext(s_display, c->config, c->context, inherit);
         if (ctx == EGL_NO_CONTEXT) break;
         c->pool[c->pool_size++] = ctx;
     }
     HLG_LOG("ctx %p: shared context pool %d/%d (GL Compat)\n",
             (void *)c, c->pool_size, HLG_CTX_POOL);
 }
 
 /* =========================================================================
  *  Экспорты: инстансы
  * ========================================================================= */
 static void hlg_destroy_internal(hlg_ctx *c);
 
 __attribute__((visibility("default")))
 void *hlg_create(void) {
     hlg_ctx *c = (hlg_ctx *)calloc(1, sizeof(hlg_ctx));
     if (!c) return NULL;
     c->w = 640;
     c->h = 480;
     pthread_mutexattr_t attr;
     pthread_mutexattr_init(&attr);
     pthread_mutexattr_settype(&attr, PTHREAD_MUTEX_RECURSIVE);
     pthread_mutex_init(&c->lock, &attr);
     pthread_mutexattr_destroy(&attr);
     HLG_LOG("hlg_create -> %p\n", (void *)c);
     return c;
 }
 
 __attribute__((visibility("default")))
 void hlg_free(void *h) {
     hlg_ctx *c = (hlg_ctx *)h;
     if (!c) return;
     hlg_destroy_internal(c);
     pthread_mutex_destroy(&c->lock);
     HLG_LOG("hlg_free(%p)\n", (void *)c);
     free(c);
 }
 
 __attribute__((visibility("default")))
 int hlg_init_ex(void *h, int api, int major, int minor, int flags) {
     hlg_ctx *c = (hlg_ctx *)h;
     if (!c) return 0;
     int compat = flags & 1;
     pthread_mutex_lock(&c->lock);
     if (c->initialized) {
         int same = (c->api_mode == (api ? 1 : 0) && c->gl_major == major &&
                     c->gl_minor == minor && c->compat_profile == compat);
         if (same && ensure_current(c)) {
             pthread_mutex_unlock(&c->lock);
             return 1;
         }
         HLG_LOG("ctx %p reinit (same=%d) — recreating\n", (void *)c, same);
         hlg_destroy_internal(c);
     }
     c->api_mode       = api ? 1 : 0;
     c->compat_profile = compat;
     c->gl_major       = major;
     c->gl_minor       = minor;
     c->serial_mode    = (c->api_mode == 1) || !compat;
     c->epoch          = atomic_fetch_add(&s_epoch_gen, 1);
 
     if (c->api_mode == 1) {
         /* до acquire_display: setenv после инициализации Mesa бесполезен */
         setenv("MESA_GLTHREAD", "false", 0);
         setenv("MESA_GLES_VERSION_OVERRIDE", "3.0", 0);
     }
 
     if (acquire_display() == EGL_NO_DISPLAY) {
         pthread_mutex_unlock(&c->lock);
         return 0;
     }
     c->has_display_ref = 1;
 
     if (!eglBindAPI(c->api_mode == 1 ? EGL_OPENGL_ES_API : EGL_OPENGL_API)) {
         HLG_LOG("eglBindAPI FAILED err=0x%x\n", eglGetError());
         goto fail;
     }
     if (!choose_config(c)) goto fail;
 
     c->context = try_create_context(c, major, minor);
     if (c->context == EGL_NO_CONTEXT && c->api_mode == 1) {
         c->gl_major = 2;
         c->gl_minor = 0;
         c->context = try_create_context(c, 2, 0);
     }
     if (c->context == EGL_NO_CONTEXT && c->api_mode == 0) {
         for (unsigned i = 0; i < sizeof(s_gl_versions) / sizeof(s_gl_versions[0]); ++i) {
             c->gl_major = s_gl_versions[i].major;
             c->gl_minor = s_gl_versions[i].minor;
             c->context = try_create_context(c, c->gl_major, c->gl_minor);
             if (c->context != EGL_NO_CONTEXT) break;
         }
     }
     if (c->context == EGL_NO_CONTEXT) goto fail;
 
     if (!c->surfaceless) {
         EGLint pa[] = { EGL_WIDTH, c->w, EGL_HEIGHT, c->h, EGL_NONE };
         c->surface = eglCreatePbufferSurface(s_display, c->config, pa);
         if (c->surface == EGL_NO_SURFACE) {
             const char *ext = eglQueryString(s_display, EGL_EXTENSIONS);
             if (ext && strstr(ext, "EGL_KHR_surfaceless_context")) {
                 HLG_LOG("pbuffer FAILED err=0x%x — surfaceless fallback\n", eglGetError());
                 c->surfaceless = 1;
             } else {
                 HLG_LOG("eglCreatePbufferSurface FAILED err=0x%x\n", eglGetError());
                 goto fail;
             }
         }
     }
 
     c->initialized = 1; /* до ensure_current — он проверяет флаг */
     if (!ensure_current(c) || !ensure_base_procs()) {
         c->initialized = 0;
         goto fail;
     }
 
     init_ctx_pool(c);
     create_offscreen_fbo(c, c->w, c->h); /* fbo_ready=0 не фатален: default FB */
     if (c->surfaceless && !c->fbo_ready) {
         HLG_LOG("surfaceless mode requires FBO — init failed\n");
         c->initialized = 0;
         goto fail;
     }
     if (GL.Viewport) GL.Viewport(0, 0, c->w, c->h);
     log_gpu_identity(c);
     HLG_LOG("ctx %p init OK: %s %d.%d %s %dx%d (%s%s, fbo=%d)\n", (void *)c,
             c->api_mode ? "GLES" : "GL", c->gl_major, c->gl_minor,
             c->compat_profile ? "Compat" : "Core", c->w, c->h,
             c->serial_mode ? "serial" : "pool",
             c->surfaceless ? ", surfaceless" : "", c->fbo_ready);
     pthread_mutex_unlock(&c->lock);
     return 1;
 
 fail:
     hlg_destroy_internal(c); /* единый путь очистки — display не течёт */
     pthread_mutex_unlock(&c->lock);
     return 0;
 }
 
 __attribute__((visibility("default")))
 int hlg_init(void *h, int major, int minor) {
     return hlg_init_ex(h, 0, major, minor, 0);
 }
 
 /* Вызывается под c->lock (мьютекс рекурсивный) либо из hlg_free. */
 static void hlg_destroy_internal(hlg_ctx *c) {
     pthread_mutex_lock(&c->lock);
     if (c->context != EGL_NO_CONTEXT) {
         EGLSurface surf = c->surfaceless ? EGL_NO_SURFACE : c->surface;
         if (eglMakeCurrent(s_display, surf, surf, c->context)) {
             destroy_pbos(c);
             destroy_offscreen_fbo(c);
         } else {
             HLG_LOG("ctx %p: current in another thread (0x%x) — abandoning GL objects\n",
                     (void *)c, eglGetError());
         }
         eglMakeCurrent(s_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
         for (int i = 0; i < c->pool_size; ++i)
             if (c->pool[i] != EGL_NO_CONTEXT) eglDestroyContext(s_display, c->pool[i]);
         c->pool_size = c->pool_next = 0;
         for (int i = 0; i < c->tsurf_n; ++i)
             if (c->tsurf[i] != EGL_NO_SURFACE) eglDestroySurface(s_display, c->tsurf[i]);
         c->tsurf_n = 0;
         eglDestroyContext(s_display, c->context);
         c->context = EGL_NO_CONTEXT;
     }
     if (c->surface != EGL_NO_SURFACE) {
         eglDestroySurface(s_display, c->surface);
         c->surface = EGL_NO_SURFACE;
     }
     free(c->scratch);
     c->scratch = NULL;
     c->scratch_cap = 0;
     c->initialized = 0;
     c->fbo_ready = 0;
     c->src_valid = 0;
     c->src_fbo = 0;
     c->src_zero_frames = 0;
     c->gpu_logged = 0;
     c->gpu_info[0] = '\0';
     atomic_store(&c->last_fbo, 0);
     /* инвалидируем TLS-привязки ВСЕХ потоков к этому инстансу (fix
      * dangling-контекстов из singleton-версии): epoch меняется, и
      * ensure_current на любом потоке сбросит своё tl_ctx/tl_surface */
     c->epoch = atomic_fetch_add(&s_epoch_gen, 1);
     if (tl_owner == c) {
         tl_owner = NULL;
         tl_ctx = EGL_NO_CONTEXT;
         tl_surface = EGL_NO_SURFACE;
     }
     if (t_cur == c) t_cur = NULL;
     hlg_ctx *expect = c;
     atomic_compare_exchange_strong(&s_fallback, &expect, NULL);
     if (c->has_display_ref) {
         c->has_display_ref = 0;
         release_display();
     }
     pthread_mutex_unlock(&c->lock);
 }
 
 __attribute__((visibility("default")))
 void hlg_destroy(void *h) {
     hlg_ctx *c = (hlg_ctx *)h;
     if (c) hlg_destroy_internal(c);
 }
 
 __attribute__((visibility("default")))
 void hlg_release(void *h) {
     hlg_ctx *c = (hlg_ctx *)h;
     if (!c || !c->initialized) return;
     pthread_mutex_lock(&c->lock);
     if (tl_owner == c) {
         eglMakeCurrent(s_display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
         eglReleaseThread();
         tl_owner = NULL;
         tl_ctx = EGL_NO_CONTEXT;
         tl_surface = EGL_NO_SURFACE; /* сам surface зарегистрирован в tsurf[] — инстанс уничтожит */
     }
     if (t_cur == c) t_cur = NULL;
     pthread_mutex_unlock(&c->lock);
     HLG_LOG("ctx %p: released from thread\n", (void *)c);
 }
 
 __attribute__((visibility("default")))
 int hlg_make_current(void *h) {
     hlg_ctx *c = (hlg_ctx *)h;
     if (!c) return 0;
     pthread_mutex_lock(&c->lock);
     int ok = c->initialized ? ensure_current(c) : 0;
     pthread_mutex_unlock(&c->lock);
     return ok;
 }
 
 __attribute__((visibility("default")))
 int hlg_resize(void *h, int w, int ht) {
     hlg_ctx *c = (hlg_ctx *)h;
     if (!c || w <= 0 || ht <= 0) return 0;
     pthread_mutex_lock(&c->lock);
     if (!c->initialized || (w == c->w && ht == c->h)) {
         int ok = c->initialized;
         pthread_mutex_unlock(&c->lock);
         return ok;
     }
     HLG_LOG("ctx %p resize: %dx%d -> %dx%d\n", (void *)c, c->w, c->h, w, ht);
     c->w = w;
     c->h = ht;
     if (ensure_current(c)) {
         /* pbuffer НЕ пересоздаём: при рабочем FBO его размер не важен, а
          * пересоздание master-поверхности инвалидировало бы привязки потоков */
         resize_offscreen_fbo(c, w, ht);
         if (GL.Viewport) GL.Viewport(0, 0, w, ht);
         c->src_valid = 0; /* источник readback переоткрыть */
     }
     pthread_mutex_unlock(&c->lock);
     return 1;
 }
 
 /* =========================================================================
  *  Readback
  * ========================================================================= */
 static int count_nonzero_rgba(const void *pixels, int total) {
     const unsigned *px = (const unsigned *)pixels;
     int nz = 0;
     for (int i = 0; i < total; ++i)
         if (px[i] & 0x00FFFFFFu) ++nz;
     return nz;
 }
 
 /* Разреженная проверка (каждый ~4096-й пиксель, ранний выход). */
 static int sample_nonzero_rgba(const void *pixels, int total) {
     const unsigned *px = (const unsigned *)pixels;
     int step = total > 4096 ? total / 4096 : 1;
     for (int i = 0; i < total; i += step)
         if (px[i] & 0x00FFFFFFu) return 1;
     return 0;
 }
 
 /* Прочитать регион кандидата ОДНИМ вызовом (вместо циклов glReadPixels 1×1). */
 static int probe_fbo(hlg_ctx *c, unsigned fbo, int w, int h) {
     if (w > 256) w = 256;
     if (h > 256) h = 256;
     void *buf = ensure_scratch(c, (size_t)w * (size_t)h * 4);
     if (!buf) return -1;
     GL.BindFramebuffer(HLG_GL_READ_FRAMEBUFFER, fbo);
     if (fbo != 0) {
         if (GL.CheckFramebufferStatus &&
             GL.CheckFramebufferStatus(HLG_GL_READ_FRAMEBUFFER) != HLG_GL_FRAMEBUFFER_COMPLETE)
             return -1;
         if (GL.ReadBuffer) GL.ReadBuffer(HLG_GL_COLOR_ATTACHMENT0);
     } else {
         if (GL.ReadBuffer) GL.ReadBuffer(HLG_GL_BACK);
     }
     GL.ReadPixels(0, 0, w, h, HLG_GL_RGBA, HLG_GL_UNSIGNED_BYTE, buf);
     return count_nonzero_rgba(buf, w * h);
 }
 
 /* Найти FBO с контентом: сперва кандидаты, затем скан id 2..64 (эвристика-
  * fallback: id не обязаны быть последовательными, но у Mesa на практике таковы;
  * благодаря перехвату glBindFramebuffer(…,0) обычно срабатывает первый кандидат). */
 static unsigned discover_src_fbo(hlg_ctx *c, int rw, int rh) {
     int draw_fbo = 0;
     GL.GetIntegerv(HLG_GL_FRAMEBUFFER_BINDING, &draw_fbo);
     unsigned cand[4];
     int nc = 0;
     int last = atomic_load(&c->last_fbo);
     if (draw_fbo > 0)                 cand[nc++] = (unsigned)draw_fbo;
     if (last > 0 && last != draw_fbo) cand[nc++] = (unsigned)last;
     if (c->fbo_ready && c->fbo != (unsigned)draw_fbo && c->fbo != (unsigned)last)
         cand[nc++] = c->fbo;
     if (!c->surfaceless)              cand[nc++] = 0;
     for (int i = 0; i < nc; ++i)
         if (probe_fbo(c, cand[i], rw, rh) > 0) return cand[i];
     unsigned best = 0;
     int best_nz = 0;
     for (unsigned fbo = 2; fbo <= 64; ++fbo) {
         int nz = probe_fbo(c, fbo, rw, rh);
         if (nz > best_nz) { best_nz = nz; best = fbo; }
     }
     if (best) return best;
     return c->fbo_ready ? c->fbo : 0;
 }
 
 /* Формат: RGBA, без Y-flip — Java-мост на Linux ждёт именно так
  * (в отличие от win-версии: там BGRA + flip). */
 __attribute__((visibility("default")))
 void hlg_read_pixels(void *h, int *viewport_out, void *pixels_out,
                      int max_pixels, int req_w, int req_h) {
     hlg_ctx *c = (hlg_ctx *)h;
     if (!c || !viewport_out || !pixels_out) return;
     pthread_mutex_lock(&c->lock);
     if (!c->initialized || !ensure_current(c) || !ensure_base_procs() || !ensure_fbo_procs()) {
         pthread_mutex_unlock(&c->lock);
         return;
     }
 
     int vp[4] = {0};
     GL.GetIntegerv(HLG_GL_VIEWPORT, vp);
     int gl_w = vp[2] > 0 ? vp[2] : c->w;
     int gl_h = vp[3] > 0 ? vp[3] : c->h;
 
     /* растим FBO под вьюпорт (не сжимаем — избегаем дёрганья на смене сцен) */
     if (c->fbo_ready && (gl_w > c->fbo_w || gl_h > c->fbo_h))
         resize_offscreen_fbo(c, gl_w > c->fbo_w ? gl_w : c->fbo_w,
                              gl_h > c->fbo_h ? gl_h : c->fbo_h);
 
     int read_w = req_w > 0 ? req_w : gl_w;
     int read_h = req_h > 0 ? req_h : gl_h;
     if (c->fbo_ready) {
         if (read_w > c->fbo_w) read_w = c->fbo_w;
         if (read_h > c->fbo_h) read_h = c->fbo_h;
     }
     if (max_pixels > 0 && read_w > 0 && (long long)read_w * read_h > max_pixels)
         read_h = max_pixels / read_w;
     if (read_w <= 0 || read_h <= 0) {
         pthread_mutex_unlock(&c->lock);
         return;
     }
 
     int prev_read = 0;
     GL.GetIntegerv(HLG_GL_READ_FRAMEBUFFER_BINDING, &prev_read);
 
     /* источник кэшируется между кадрами (не ищем каждый кадр) */
     if (!c->src_valid) {
         c->src_fbo = discover_src_fbo(c, read_w, read_h);
         c->src_valid = 1;
         c->src_zero_frames = 0;
         destroy_pbos(c); /* источник сменился — старые PBO-кадры невалидны */
         HLG_LOG("ctx %p: readback source FBO=%u\n", (void *)c, c->src_fbo);
     }
 
     GL.BindFramebuffer(HLG_GL_READ_FRAMEBUFFER, c->src_fbo);
     if (GL.ReadBuffer)
         GL.ReadBuffer(c->src_fbo ? HLG_GL_COLOR_ATTACHMENT0 : HLG_GL_BACK);
     if (GL.PixelStorei) GL.PixelStorei(HLG_GL_PACK_ALIGNMENT, 4);
 
     int out_w = read_w, out_h = read_h;
     int use_pbo = ensure_pbo_buffers(c, read_w, read_h);
     if (use_pbo) {
         int idx = c->pbo_idx, prev = idx ^ 1;
         /* кадр N: асинхронное чтение в pbo[idx] (GPU не стопорится) */
         GL.BindBuffer(HLG_GL_PIXEL_PACK_BUFFER, c->pbo[idx]);
         GL.ReadPixels(0, 0, read_w, read_h, HLG_GL_RGBA, HLG_GL_UNSIGNED_BYTE, NULL);
         c->pbo_w[idx] = read_w;
         c->pbo_h[idx] = read_h;
         if (GL.Flush) GL.Flush();
         /* кадр N-1 из другого PBO */
         int have_prev = 0;
         if (c->pbo_w[prev] > 0 && c->pbo_h[prev] > 0) {
             out_w = c->pbo_w[prev];
             out_h = c->pbo_h[prev];
             GL.BindBuffer(HLG_GL_PIXEL_PACK_BUFFER, c->pbo[prev]);
             void *src = map_pack_buffer((size_t)out_w * (size_t)out_h * 4);
             if (src) {
                 memcpy(pixels_out, src, (size_t)out_w * (size_t)out_h * 4);
                 GL.UnmapBuffer(HLG_GL_PIXEL_PACK_BUFFER);
                 have_prev = 1;
             }
         }
         if (!have_prev)
             memset(pixels_out, 0, (size_t)out_w * (size_t)out_h * 4);
         GL.BindBuffer(HLG_GL_PIXEL_PACK_BUFFER, 0);
         c->pbo_idx = prev;
         if (have_prev && !sample_nonzero_rgba(pixels_out, out_w * out_h)) {
             if (++c->src_zero_frames >= 8) c->src_valid = 0; /* переоткрыть источник */
         } else if (have_prev) {
             c->src_zero_frames = 0;
         }
     } else {
         /* fallback: синхронный readback */
         GL.ReadPixels(0, 0, read_w, read_h, HLG_GL_RGBA, HLG_GL_UNSIGNED_BYTE, pixels_out);
         if (!sample_nonzero_rgba(pixels_out, read_w * read_h)) {
             if (++c->src_zero_frames >= 8) c->src_valid = 0;
         } else {
             c->src_zero_frames = 0;
         }
     }
 
     viewport_out[0] = 0;
     viewport_out[1] = 0;
     viewport_out[2] = out_w;
     viewport_out[3] = out_h;
 
     GL.BindFramebuffer(HLG_GL_READ_FRAMEBUFFER,
                        prev_read > 0 ? (unsigned)prev_read
                                      : (c->fbo_ready ? c->fbo : 0));
 
     if (c->read_count <= 10 || (c->read_count % 300) == 0)
         HLG_LOG("ctx %p read #%d: src=%u game=%dx%d glvp=%dx%d read=%dx%d out=%dx%d pbo=%d zero=%d\n",
                 (void *)c, c->read_count, c->src_fbo, req_w, req_h,
                 gl_w, gl_h, read_w, read_h, out_w, out_h, use_pbo, c->src_zero_frames);
     c->read_count++;
     pthread_mutex_unlock(&c->lock);
 }
 
 /* =========================================================================
  *  Диагностика и лог
  * ========================================================================= */
 __attribute__((visibility("default")))
 void hlg_debug_fbo(void *h) {
     hlg_ctx *c = (hlg_ctx *)h;
     if (!c) return;
     pthread_mutex_lock(&c->lock);
     if (c->initialized && ensure_current(c) && ensure_base_procs()) {
         int draw = 0, read = 0;
         /* fix: раньше запрашивался pname 0x8CA9 (это target!) — INVALID_ENUM */
         GL.GetIntegerv(HLG_GL_FRAMEBUFFER_BINDING, &draw);
         GL.GetIntegerv(HLG_GL_READ_FRAMEBUFFER_BINDING, &read);
         HLG_LOG("ctx %p debug: drawFbo=%d readFbo=%d lastFbo=%d ourFbo=%u src=%u %dx%d\n",
                 (void *)c, draw, read, atomic_load(&c->last_fbo), c->fbo, c->src_fbo, c->w, c->h);
     }
     pthread_mutex_unlock(&c->lock);
 }
 
 __attribute__((visibility("default")))
 const char *hlg_get_gpu_info(void *h) {
     hlg_ctx *c = (hlg_ctx *)h;
     return (c && c->gpu_info[0]) ? c->gpu_info : "GPU info not yet available";
 }
 
 __attribute__((visibility("default")))
 void hlg_log_gpu_identity(void *h) {
     hlg_ctx *c = (hlg_ctx *)h;
     if (!c) return;
     pthread_mutex_lock(&c->lock);
     if (c->initialized && ensure_current(c) && ensure_base_procs()) {
         c->gpu_logged = 0; /* форсируем повторный лог */
         log_gpu_identity(c);
     }
     pthread_mutex_unlock(&c->lock);
 }
 
 __attribute__((visibility("default")))
 void hlg_dump_hw_render(const void *data, int size) {
     HLG_LOG("=== HW RENDER DUMP (%d bytes) ===\n", size);
     const unsigned char *p = (const unsigned char *)data;
     if (!p || !log_enabled()) return;
     for (int i = 0; i < size; i += 8) {
         fprintf(stderr, "  +%2d: ", i);
         for (int j = 0; j < 8 && (i + j) < size; ++j)
             fprintf(stderr, "%02x ", p[i + j]);
         if (i + 4 <= size) {
             unsigned v;
             memcpy(&v, p + i, 4); /* без невыровненного разыменования */
             fprintf(stderr, " [%u]", v);
         }
         fprintf(stderr, "\n");
     }
     HLG_LOG("=== END DUMP ===\n");
 }
 
 /* Нативный variadic лог-коллбек для RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
  * JNA не умеет variadic callbacks, Java пишет hlg_get_log_cb_ptr()
  * напрямую в retro_log_callback.log. */
 __attribute__((visibility("default")))
 void hlg_log_cb(int level, const char *fmt, ...) {
     char buf[2048];
     va_list ap;
     va_start(ap, fmt);
     vsnprintf(buf, sizeof(buf), fmt ? fmt : "(null)", ap);
     va_end(ap);
     const char *tag = "DBG";
     switch (level) {
         case 0: tag = "DBG"; break;
         case 1: tag = "INF"; break;
         case 2: tag = "WRN"; break;
         case 3: tag = "ERR"; break;
     }
     HLG_LOG("[core %s] %s\n", tag, buf);
 }
 
 __attribute__((visibility("default")))
 void *hlg_get_log_cb_ptr(void) { return (void *)hlg_log_cb; }