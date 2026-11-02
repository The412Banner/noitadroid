// SDL on GLES (Linux and Android): the game asks SDL for a desktop GL context; it gets a GLES 3.0 one, with gl4es
// providing desktop GL 2.1 on top (the GL the bridge offers is gl4es's, tools/gen_gl.py). Linux uses the same path
// as Android so the desktop build tests it. The build links with -Wl,--wrap for these SDL calls
// (tools/target.py gl_wrap_flags), so the bridge's thunks and the tests reach these first.
#include <SDL.h>
#include <stdio.h>

void initialize_gl4es(void);

int __real_SDL_Init(Uint32 flags);
int __real_SDL_GL_SetAttribute(SDL_GLattr attr, int value);
SDL_GLContext __real_SDL_GL_CreateContext(SDL_Window *window);

int __wrap_SDL_Init(Uint32 flags) {
    SDL_SetHint(SDL_HINT_VIDEO_X11_FORCE_EGL, "1");  // X11: an EGL context (GLES), not GLX
    int r = __real_SDL_Init(flags);
    if (!r && (flags & SDL_INIT_VIDEO)) {
        __real_SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK, SDL_GL_CONTEXT_PROFILE_ES);
        __real_SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, 3);
        __real_SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, 0);
    }
    return r;
}

// The game's context requests (version, profile, flags) are for desktop GL: GLES 3.0 stands.
int __wrap_SDL_GL_SetAttribute(SDL_GLattr attr, int value) {
    switch (attr) {
    case SDL_GL_CONTEXT_PROFILE_MASK:
    case SDL_GL_CONTEXT_MAJOR_VERSION:
    case SDL_GL_CONTEXT_MINOR_VERSION:
    case SDL_GL_CONTEXT_FLAGS:
        return 0;
    default:
        return __real_SDL_GL_SetAttribute(attr, value);
    }
}

SDL_GLContext __wrap_SDL_GL_CreateContext(SDL_Window *window) {
    SDL_GLContext ctx = __real_SDL_GL_CreateContext(window);
    if (ctx) initialize_gl4es();  // once; later calls return at once
    else fprintf(stderr, "[noitadroid] SDL_GL_CreateContext (GLES 3.0): %s\n", SDL_GetError());
    return ctx;
}
