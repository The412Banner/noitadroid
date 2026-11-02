// _NSGetExecutablePath from /proc/self/exe. On Android that is app_process64, so callers that look next to the
// executable (fmod.c) need their environment override there.
#pragma once
#include <stdint.h>
#include <unistd.h>

static inline int _NSGetExecutablePath(char *buf, uint32_t *size) {
    ssize_t n = readlink("/proc/self/exe", buf, *size);
    if (n < 0) return -1;
    if ((uint32_t)n >= *size) {
        *size = (uint32_t)n + 1;
        return -1;
    }
    buf[n] = 0;
    return 0;
}
