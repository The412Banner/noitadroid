// Device smoke test for the Android stack, without the game: what the port relies on, checked inside a real app
// process (SELinux app domain, ART's address space), which an adb-shell test can't show.
//   - guest memory: 4 GB anonymous at MEM_HOST_BASE (runtime/compat/compat.h), touched at both ends
//   - LuaJIT 2.1: JIT on, arm64, a hot loop's speed
//   - SDL2: a GLES 3 context; gl4es: desktop GL 2.1 on it (immediate mode, a GLSL 1.20 shader with fixed-function
//     built-ins, an FBO, glMapBuffer); sync objects straight from GLES3 (gl4es has none)
// Each check is one bar on screen (green ok, red failed), a logcat line (tag noitadroid) and a line in
// <external files dir>/smoke.txt. Tap or press Back to quit.
#include <SDL.h>
#include <android/log.h>
#include <lauxlib.h>
#include <lua.h>
#include <lualib.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>
#include <sys/mman.h>
#include <time.h>

#define GL_GLEXT_PROTOTYPES
#include <GL/gl.h>
#include <GL/glext.h>

void initialize_gl4es(void);

#ifndef MEM_HOST_BASE
#error "build with runtime/compat/compat.h"
#endif

enum { MAX_CHECKS = 32 };
static struct { char line[200]; int ok; } CHECKS[MAX_CHECKS];
static int NCHECKS;
static FILE *REPORT;

static void check(int ok, const char *fmt, ...) {
    char msg[160];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(msg, sizeof msg, fmt, ap);
    va_end(ap);
    __android_log_print(ok ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR, "noitadroid", "%s %s", ok ? "ok  " : "FAIL", msg);
    if (REPORT) fprintf(REPORT, "%s %s\n", ok ? "ok  " : "FAIL", msg), fflush(REPORT);
    if (NCHECKS < MAX_CHECKS) {
        snprintf(CHECKS[NCHECKS].line, sizeof CHECKS[NCHECKS].line, "%s", msg);
        CHECKS[NCHECKS++].ok = ok;
    }
}

static double now(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec + t.tv_nsec * 1e-9;
}

static void guest_memory(void) {
    void *want = (void *)MEM_HOST_BASE;
    void *m = mmap(want, 1ull << 32, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE, -1, 0);
    check(m == want, "guest memory: 4 GB at %p (got %p)", want, m);
    if (m != want) return;
    volatile uint8_t *g = m;
    g[0x400000] = 0x5a, g[0xfffff000u] = 0xa5;
    check(g[0x400000] == 0x5a && g[0xfffff000u] == 0xa5, "guest memory: writable at 0x400000 and 0xfffff000");
}

static void luajit(void) {
    lua_State *L = luaL_newstate();
    luaL_openlibs(L);
    if (luaL_dostring(L, "return tostring(jit.status()) .. ' ' .. jit.arch .. ' ' .. jit.version")) {
        check(0, "luajit: %s", lua_tostring(L, -1));
        lua_close(L);
        return;
    }
    const char *s = lua_tostring(L, -1);
    check(!strncmp(s, "true arm64", 10), "luajit: %s", s);
    lua_pop(L, 1);
    double t0 = now();
    int err = luaL_dostring(L, "local x = 0 for i = 1, 100000000 do x = (x + i) % 1000003 end return x");
    double ns = (now() - t0) * 1e9 / 1e8;
    check(!err && ns < 10, "luajit: hot loop %.2f ns/iteration (jitted: under 10)", ns);
    lua_close(L);
}

static GLuint shader(GLenum type, const char *src) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, NULL);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512] = "";
        glGetShaderInfoLog(s, sizeof log, NULL, log);
        __android_log_print(ANDROID_LOG_ERROR, "noitadroid", "shader: %s", log);
    }
    return ok ? s : 0;
}

// What Noita's renderer leans on, through gl4es: GLSL 1.10/1.20 with fixed-function built-ins, immediate mode,
// FBOs and mapped buffers.
static GLuint PROGRAM;
static void gl_checks(void) {
    const char *version = (const char *)glGetString(GL_VERSION), *renderer = (const char *)glGetString(GL_RENDERER);
    check(version && strstr(version, "2.1"), "gl4es: GL_VERSION %s", version ? version : "(null)");
    check(renderer != NULL, "gl4es: GL_RENDERER %s", renderer ? renderer : "(null)");

    GLuint vs = shader(GL_VERTEX_SHADER, "#version 120\nvarying vec4 col;\n"
                                         "void main() { col = gl_Color; gl_TexCoord[0] = gl_MultiTexCoord0; gl_Position = ftransform(); }\n");
    GLuint fs = shader(GL_FRAGMENT_SHADER, "#version 120\nvarying vec4 col;\n"
                                           "void main() { gl_FragColor = col; }\n");
    PROGRAM = glCreateProgram();
    if (vs && fs) glAttachShader(PROGRAM, vs), glAttachShader(PROGRAM, fs);
    glLinkProgram(PROGRAM);
    GLint linked = 0;
    glGetProgramiv(PROGRAM, GL_LINK_STATUS, &linked);
    check(vs && fs && linked, "gl4es: GLSL 1.20 program with gl_Color, gl_TexCoord, ftransform");

    GLuint fbo, tex;
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 64, 64, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
    GLenum st = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    check(st == GL_FRAMEBUFFER_COMPLETE, "gl4es: FBO with a texture attachment (status %#x)", st);
    glViewport(0, 0, 64, 64);
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    glUseProgram(PROGRAM);
    glBegin(GL_QUADS);
    glColor4f(0, 1, 0, 1);
    glVertex2f(-1, -1), glVertex2f(1, -1), glVertex2f(1, 1), glVertex2f(-1, 1);
    glEnd();
    uint8_t px[4] = {0};
    glReadPixels(32, 32, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
    check(px[1] > 200 && px[0] < 50, "gl4es: immediate-mode quad into the FBO reads back green (%u,%u,%u)", px[0], px[1], px[2]);
    glUseProgram(0);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    GLuint vbo;
    glGenBuffers(1, &vbo);
    glBindBuffer(GL_ARRAY_BUFFER, vbo);
    glBufferData(GL_ARRAY_BUFFER, 4096, NULL, GL_DYNAMIC_DRAW);
    uint32_t *p = glMapBuffer(GL_ARRAY_BUFFER, GL_WRITE_ONLY);
    if (p) p[0] = 0xdeadbeef, p[1023] = 0x12345678;
    GLboolean unmapped = glUnmapBuffer(GL_ARRAY_BUFFER);
    check(p && unmapped, "gl4es: glMapBuffer/glUnmapBuffer on a VBO");
    glBindBuffer(GL_ARRAY_BUFFER, 0);

    glFlush();  // gl4es batches commands: submit them before a fence the driver sees
    GLsync s = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    GLenum w = s ? glClientWaitSync(s, GL_SYNC_FLUSH_COMMANDS_BIT, 1000000000ull) : 0;
    check(s && (w == GL_ALREADY_SIGNALED || w == GL_CONDITION_SATISFIED), "GLES3: glFenceSync/glClientWaitSync (wait %#x)", w);
    if (s) glDeleteSync(s);
    GLenum e = glGetError();
    check(e == GL_NO_ERROR, "GL: no error after the checks (%#x)", e);
}

static void draw_results(int w, int h) {
    glViewport(0, 0, w, h);
    glClearColor(0.08f, 0.08f, 0.1f, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    glMatrixMode(GL_PROJECTION);
    glLoadIdentity();
    glOrtho(0, 1, NCHECKS, 0, -1, 1);
    glMatrixMode(GL_MODELVIEW);
    glLoadIdentity();
    glBegin(GL_QUADS);
    for (int i = 0; i < NCHECKS; i++) {
        glColor4f(CHECKS[i].ok ? 0.1f : 0.9f, CHECKS[i].ok ? 0.75f : 0.15f, 0.2f, 1);
        float y0 = i + 0.1f, y1 = i + 0.9f;
        glVertex2f(0.05f, y0), glVertex2f(0.95f, y0), glVertex2f(0.95f, y1), glVertex2f(0.05f, y1);
    }
    glEnd();
}

int main(int argc, char *argv[]) {
    (void)argc, (void)argv;
    char path[512];
    const char *dir = SDL_AndroidGetExternalStoragePath();
    snprintf(path, sizeof path, "%s/smoke.txt", dir ? dir : ".");
    REPORT = fopen(path, "w");
    __android_log_print(ANDROID_LOG_INFO, "noitadroid", "smoke test, report %s", path);

    guest_memory();
    luajit();

    if (SDL_Init(SDL_INIT_VIDEO | SDL_INIT_EVENTS)) {
        check(0, "SDL_Init: %s", SDL_GetError());
        return 1;
    }
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK, SDL_GL_CONTEXT_PROFILE_ES);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, 3);
    SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, 0);
    SDL_Window *win = SDL_CreateWindow("noitadroid smoke", 0, 0, 0, 0, SDL_WINDOW_OPENGL | SDL_WINDOW_FULLSCREEN_DESKTOP);
    SDL_GLContext ctx = win ? SDL_GL_CreateContext(win) : NULL;
    check(ctx != NULL, "SDL2: GLES 3.0 context (%s)", ctx ? "created" : SDL_GetError());
    if (!ctx) return 1;
    initialize_gl4es();
    gl_checks();
    int ok = 0;
    for (int i = 0; i < NCHECKS; i++) ok += CHECKS[i].ok;
    __android_log_print(ANDROID_LOG_INFO, "noitadroid", "%d/%d checks ok", ok, NCHECKS);
    if (REPORT) fprintf(REPORT, "%d/%d checks ok\n", ok, NCHECKS), fclose(REPORT);

    for (int running = 1; running;) {
        SDL_Event ev;
        while (SDL_PollEvent(&ev))
            if (ev.type == SDL_QUIT || ev.type == SDL_FINGERDOWN || ev.type == SDL_MOUSEBUTTONDOWN ||
                (ev.type == SDL_KEYDOWN && ev.key.keysym.sym == SDLK_AC_BACK))
                running = 0;
        int w, h;
        SDL_GL_GetDrawableSize(win, &w, &h);
        draw_results(w, h);
        SDL_GL_SwapWindow(win);
    }
    SDL_GL_DeleteContext(ctx);
    SDL_DestroyWindow(win);
    SDL_Quit();
    return 0;
}
