// The one CGL call the bridge makes: is a context current on this thread. SDL owns the context here (GLX or EGL,
// or gl4es on Android), so SDL answers.
#pragma once

typedef void *CGLContextObj;
extern void *SDL_GL_GetCurrentContext(void);
#define CGLGetCurrentContext() ((CGLContextObj)SDL_GL_GetCurrentContext())
