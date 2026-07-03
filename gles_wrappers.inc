/* Included by headless_gl.c — mutex-wrapped GL entry points for PPSSPP. */

#define GL_WBODY() do { if (!wgl_begin()) return; } while (0)
#define GL_WEND() wgl_end()

#define GL_WRAP_VOID0(fn) \
    static void (*real_##fn)(void); \
    static void wrap_##fn(void) { GL_WBODY(); if (real_##fn) real_##fn(); GL_WEND(); }

#define GL_WRAP_VOID1(fn, t1) \
    static void (*real_##fn)(t1); \
    static void wrap_##fn(t1 a1) { GL_WBODY(); if (real_##fn) real_##fn(a1); GL_WEND(); }

#define GL_WRAP_VOID2(fn, t1, t2) \
    static void (*real_##fn)(t1, t2); \
    static void wrap_##fn(t1 a1, t2 a2) { GL_WBODY(); if (real_##fn) real_##fn(a1, a2); GL_WEND(); }

#define GL_WRAP_VOID3(fn, t1, t2, t3) \
    static void (*real_##fn)(t1, t2, t3); \
    static void wrap_##fn(t1 a1, t2 a2, t3 a3) { GL_WBODY(); if (real_##fn) real_##fn(a1, a2, a3); GL_WEND(); }

#define GL_WRAP_VOID4(fn, t1, t2, t3, t4) \
    static void (*real_##fn)(t1, t2, t3, t4); \
    static void wrap_##fn(t1 a1, t2 a2, t3 a3, t4 a4) { GL_WBODY(); if (real_##fn) real_##fn(a1, a2, a3, a4); GL_WEND(); }

#define GL_WRAP_VOID5(fn, t1, t2, t3, t4, t5) \
    static void (*real_##fn)(t1, t2, t3, t4, t5); \
    static void wrap_##fn(t1 a1, t2 a2, t3 a3, t4 a4, t5 a5) { GL_WBODY(); if (real_##fn) real_##fn(a1, a2, a3, a4, a5); GL_WEND(); }

#define GL_WRAP_VOID6(fn, t1, t2, t3, t4, t5, t6) \
    static void (*real_##fn)(t1, t2, t3, t4, t5, t6); \
    static void wrap_##fn(t1 a1, t2 a2, t3 a3, t4 a4, t5 a5, t6 a6) { GL_WBODY(); if (real_##fn) real_##fn(a1, a2, a3, a4, a5, a6); GL_WEND(); }

#define GL_WRAP_VOID9(fn, t1, t2, t3, t4, t5, t6, t7, t8, t9) \
    static void (*real_##fn)(t1, t2, t3, t4, t5, t6, t7, t8, t9); \
    static void wrap_##fn(t1 a1, t2 a2, t3 a3, t4 a4, t5 a5, t6 a6, t7 a7, t8 a8, t9 a9) { \
        GL_WBODY(); if (real_##fn) real_##fn(a1, a2, a3, a4, a5, a6, a7, a8, a9); GL_WEND(); }

#define GL_WRAP_RET2(fn, rt, t1, t2) \
    static rt (*real_##fn)(t1, t2); \
    static rt wrap_##fn(t1 a1, t2 a2) { \
        if (!wgl_begin()) return (rt)0; \
        rt r = real_##fn ? real_##fn(a1, a2) : (rt)0; \
        wgl_end(); return r; }

#define GL_WRAP_RET3(fn, rt, t1, t2, t3) \
    static rt (*real_##fn)(t1, t2, t3); \
    static rt wrap_##fn(t1 a1, t2 a2, t3 a3) { \
        if (!wgl_begin()) return (rt)0; \
        rt r = real_##fn ? real_##fn(a1, a2, a3) : (rt)0; \
        wgl_end(); return r; }

/* glGetStringi — TLS copy */
static const GLubyte *(*real_glGetStringi)(GLenum, GLuint);
static const GLubyte *wrap_glGetStringi(GLenum name, GLuint index) {
    static __thread char tls_gli[512];
    if (!wgl_begin()) return NULL;
    const GLubyte *r = real_glGetStringi ? real_glGetStringi(name, index) : NULL;
    if (r) { snprintf(tls_gli, sizeof(tls_gli), "%s", (const char *)r); r = (const GLubyte *)tls_gli; }
    wgl_end();
    return r;
}

static void *(*real_glMapBufferRange)(GLenum, GLintptr, GLsizeiptr, GLbitfield);
static void *wrap_glMapBufferRange(GLenum target, GLintptr offset, GLsizeiptr length, GLbitfield access) {
    if (!wgl_begin()) return NULL;
    void *r = real_glMapBufferRange ? real_glMapBufferRange(target, offset, length, access) : NULL;
    wgl_end();
    return r;
}

GL_WRAP_VOID1(glActiveTexture, GLenum)
GL_WRAP_VOID2(glBindAttribLocation, GLuint, GLint)
GL_WRAP_VOID2(glBindRenderbuffer, GLenum, GLuint)
GL_WRAP_VOID2(glBlendFunc, GLenum, GLenum)
GL_WRAP_VOID4(glBlendFuncSeparate, GLenum, GLenum, GLenum, GLenum)
GL_WRAP_VOID4(glBufferSubData, GLenum, GLintptr, GLsizeiptr, const void *)
GL_WRAP_VOID1(glCullFace, GLenum)
GL_WRAP_VOID1(glGenerateMipmap, GLenum)
GL_WRAP_VOID4(glFramebufferTexture, GLenum, GLenum, GLuint, GLint)
GL_WRAP_VOID3(glBindBufferBase, GLenum, GLuint, GLuint)
GL_WRAP_VOID1(glDrawBuffer, GLenum)
GL_WRAP_VOID1(glReadBuffer, GLenum)
GL_WRAP_VOID2(glHint, GLenum, GLenum)
GL_WRAP_VOID2(glPolygonMode, GLenum, GLenum)
GL_WRAP_VOID1(glPointSize, GLfloat)
GL_WRAP_VOID4(glColorMask, GLboolean, GLboolean, GLboolean, GLboolean)
GL_WRAP_VOID2(glDepthRange, GLclampd, GLclampd)
GL_WRAP_VOID2(glDeleteBuffers, GLsizei, const GLuint *)
GL_WRAP_VOID2(glDeleteFramebuffers, GLsizei, const GLuint *)
GL_WRAP_VOID1(glDeleteProgram, GLuint)
GL_WRAP_VOID2(glDeleteRenderbuffers, GLsizei, const GLuint *)
GL_WRAP_VOID1(glDeleteShader, GLuint)
GL_WRAP_VOID2(glDeleteTextures, GLsizei, const GLuint *)
GL_WRAP_VOID2(glDeleteVertexArrays, GLsizei, const GLuint *)
GL_WRAP_VOID1(glDepthFunc, GLenum)
GL_WRAP_VOID1(glDepthMask, GLboolean)
GL_WRAP_VOID1(glDisableVertexAttribArray, GLuint)
GL_WRAP_VOID1(glEnableVertexAttribArray, GLuint)
GL_WRAP_VOID4(glFramebufferRenderbuffer, GLenum, GLenum, GLenum, GLuint)
GL_WRAP_VOID2(glGenRenderbuffers, GLsizei, GLuint *)
GL_WRAP_VOID1(glFrontFace, GLenum)
GL_WRAP_RET2(glGetAttribLocation, GLint, GLuint, const GLchar *)
GL_WRAP_VOID4(glGetProgramInfoLog, GLuint, GLsizei, GLsizei *, GLchar *)
GL_WRAP_VOID4(glGetShaderInfoLog, GLuint, GLsizei, GLsizei *, GLchar *)
GL_WRAP_RET2(glGetUniformLocation, GLint, GLuint, const GLchar *)
GL_WRAP_VOID1(glLineWidth, GLfloat)
GL_WRAP_VOID2(glPixelStorei, GLenum, GLint)
GL_WRAP_VOID4(glRenderbufferStorage, GLenum, GLenum, GLsizei, GLsizei)
GL_WRAP_VOID4(glScissor, GLint, GLint, GLsizei, GLsizei)
GL_WRAP_VOID3(glStencilFunc, GLenum, GLint, GLuint)
GL_WRAP_VOID1(glStencilMask, GLuint)
GL_WRAP_VOID3(glStencilOp, GLenum, GLenum, GLenum)
GL_WRAP_VOID9(glTexImage2D, GLenum, GLint, GLint, GLsizei, GLsizei, GLint, GLenum, GLenum, const void *)
GL_WRAP_VOID9(glTexSubImage2D, GLenum, GLint, GLint, GLint, GLsizei, GLsizei, GLenum, GLenum, const void *)
GL_WRAP_VOID1(glUniform1f, GLfloat)
GL_WRAP_VOID2(glUniform1i, GLint, GLint)
GL_WRAP_VOID2(glUniform2f, GLfloat, GLfloat)
GL_WRAP_VOID4(glUniform4f, GLfloat, GLfloat, GLfloat, GLfloat)
GL_WRAP_VOID3(glUniform4fv, GLint, GLsizei, const GLfloat *)
GL_WRAP_VOID4(glUniformMatrix4fv, GLint, GLsizei, GLboolean, const GLfloat *)
GL_WRAP_VOID1(glUnmapBuffer, GLenum)
GL_WRAP_VOID6(glVertexAttribPointer, GLuint, GLint, GLenum, GLboolean, GLsizei, const void *)

#undef GL_WBODY
#undef GL_WEND
