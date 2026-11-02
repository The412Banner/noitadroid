// sysctlbyname: no Darwin names exist here, so every lookup fails (callers fall back, e.g. sched.c's "pcores").
#pragma once
#include <errno.h>
#include <stddef.h>

static inline int sysctlbyname(const char *name, void *old, size_t *oldlen, void *new_, size_t newlen) {
    (void)name, (void)old, (void)oldlen, (void)new_, (void)newlen;
    errno = ENOENT;
    return -1;
}
