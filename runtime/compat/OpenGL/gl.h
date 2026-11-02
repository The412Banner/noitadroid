// Desktop OpenGL headers: Mesa's on Linux, gl4es's on Android. Mesa's gl.h pulls in glext.h itself, so the
// prototypes opengl32.h asks for must be requested before it.
#pragma once
#ifndef GL_GLEXT_PROTOTYPES
#define GL_GLEXT_PROTOTYPES
#endif
#include <GL/gl.h>
