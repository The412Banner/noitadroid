// Linux and Android hosts: the Darwin APIs the runtime calls, so the upstream (macOS) sources build unchanged.
// The build force-includes this file (-include) and puts runtime/compat/ first on the include path, where
// os/lock.h, sys/qos.h, sys/sysctl.h, mach-o/dyld.h and OpenGL/*.h stand in for the Darwin headers.
#pragma once
#ifndef __APPLE__

#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <pthread.h>
#include <sched.h>
#include <stdint.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>

// Guest memory base (cpu.h). Android kernels give user space 39 bits (512 GB), so Darwin's 0x200000004000 is out
// of reach. Same shape as there: a 16 KB-aligned base whose low 32 bits are not zero.
#define MEM_HOST_BASE 0x1000004000ull

// Darwin's nanosecond clock. CLOCK_MONOTONIC stops during suspend, as CLOCK_UPTIME_RAW does.
#define CLOCK_UPTIME_RAW CLOCK_MONOTONIC
static inline uint64_t clock_gettime_nsec_np(clockid_t id) {
    struct timespec t;
    clock_gettime(id, &t);
    return (uint64_t)t.tv_sec * 1000000000u + (uint64_t)t.tv_nsec;
}

// struct stat timestamps under their BSD names. Linux keeps no birth time there; the modification time stands
// in for the creation time Windows reports.
#define st_atimespec st_atim
#define st_mtimespec st_mtim
#define st_birthtimespec st_mtim

// Darwin's pthread_setname_np names the calling thread. Linux takes the thread and at most 15 bytes.
static inline int compat_setname_self(const char *name) {
    char n[16];
    strncpy(n, name, sizeof n - 1);
    n[sizeof n - 1] = 0;
    return pthread_setname_np(pthread_self(), n);
}
#define pthread_setname_np(name) compat_setname_self(name)

// The CPU the calling thread runs on (Darwin 11+).
static inline int pthread_cpu_number_np(size_t *cpu) {
    int n = sched_getcpu();
    if (n < 0) return -1;
    *cpu = (size_t)n;
    return 0;
}

#if defined(__x86_64__) || defined(__i386__)
#define __builtin_arm_yield() __builtin_ia32_pause()
#endif

#endif
