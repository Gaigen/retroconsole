#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <stdio.h>

static void build_attribs(EGLint *out, int maj, int min) {
    out[0] = EGL_CONTEXT_MAJOR_VERSION_KHR;
    out[1] = maj;
    out[2] = EGL_CONTEXT_MINOR_VERSION_KHR;
    out[3] = min;
    out[4] = 0x30FD; /* EGL_CONTEXT_OPENGL_PROFILE_MASK_KHR */
    out[5] = 1;      /* CORE */
    out[6] = EGL_NONE;
}

int main(void) {
    EGLDisplay d = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglInitialize(d, NULL, NULL);
    EGLint ca[] = { EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT, EGL_NONE };
    EGLConfig cfg;
    int n;
    eglChooseConfig(d, ca, &cfg, 1, &n);
    eglBindAPI(EGL_OPENGL_API);

    EGLint a[8];
    build_attribs(a, 3, 1);
    EGLContext master = eglCreateContext(d, cfg, EGL_NO_CONTEXT, a);
    printf("master=%p err=0x%x\n", (void *)master, eglGetError());

    EGLint none[] = { EGL_NONE };
    EGLContext c1 = eglCreateContext(d, cfg, master, none);
    printf("share+NONE=%p err=0x%x\n", (void *)c1, eglGetError());

    EGLContext c2 = eglCreateContext(d, cfg, master, a);
    printf("share+attribs=%p err=0x%x\n", (void *)c2, eglGetError());

    EGLContext c3 = eglCreateContext(d, cfg, EGL_NO_CONTEXT, a);
    printf("indep=%p err=0x%x\n", (void *)c3, eglGetError());
    return 0;
}
