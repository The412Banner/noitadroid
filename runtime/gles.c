// SDL on GLES (Linux and Android): the game asks SDL for a desktop GL context; it gets a GLES 3.0 one, with gl4es
// providing desktop GL 2.1 on top (the GL the bridge offers is gl4es's, tools/gen_gl.py). Linux uses the same path
// as Android so the desktop build tests it. The build links with -Wl,--wrap for these SDL calls
// (tools/target.py gl_wrap_flags), so the bridge's thunks and the tests reach these first.
#include <SDL.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>

void initialize_gl4es(void);

int __real_SDL_Init(Uint32 flags);
int __real_SDL_GL_SetAttribute(SDL_GLattr attr, int value);
SDL_GLContext __real_SDL_GL_CreateContext(SDL_Window *window);
void __real_SDL_GL_SwapWindow(SDL_Window *window);
void rt_fmod_suspend(int on) __attribute__((weak));  // runtime/fmod.c, when it's linked in

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

// Every frame the game shows passes here: it's counted (the in-game menu's FPS display reads noitadroid_frames), and
// while noitadroid_hold(1, ...) is in force the game's thread waits here, at a frame's end, with its sound suspended:
// the game stands still behind the menu. Android's own pause (the app going to the background) is SDL's, in its
// event loop: the menu lifts the hold meanwhile (android/.../NoitaActivity.java).
uint64_t noitadroid_frames;
static pthread_mutex_t hold_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t hold_changed = PTHREAD_COND_INITIALIZER;
static int hold;
static uint64_t hold_from;  // the frame count it waits at: a few frames on, after the app comes back


// after: frames the game may still show first (coming back from the background, it draws its picture again for the
// new surface, else the menu would stand over a black screen).
void noitadroid_hold(int on, int after) {
    pthread_mutex_lock(&hold_lock);
    hold_from = __atomic_load_n(&noitadroid_frames, __ATOMIC_RELAXED) + (uint64_t)after;
    __atomic_store_n(&hold, on, __ATOMIC_RELEASE);
    pthread_cond_broadcast(&hold_changed);
    pthread_mutex_unlock(&hold_lock);
}

void __wrap_SDL_GL_SwapWindow(SDL_Window *window) {
    __real_SDL_GL_SwapWindow(window);
    __atomic_fetch_add(&noitadroid_frames, 1, __ATOMIC_RELAXED);
    if (!__atomic_load_n(&hold, __ATOMIC_ACQUIRE)) return;
    pthread_mutex_lock(&hold_lock);
    int wait = hold && noitadroid_frames >= hold_from;
    pthread_mutex_unlock(&hold_lock);
    if (!wait) return;
    if (rt_fmod_suspend) rt_fmod_suspend(1);
    pthread_mutex_lock(&hold_lock);
    while (hold) pthread_cond_wait(&hold_changed, &hold_lock);
    pthread_mutex_unlock(&hold_lock);
    if (rt_fmod_suspend) rt_fmod_suspend(0);
}
