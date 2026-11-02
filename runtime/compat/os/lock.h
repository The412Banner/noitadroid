// os_unfair_lock on a pthread mutex (futex-backed on Linux and Android). Static initialisation only, as the
// runtime uses it.
#pragma once
#include <pthread.h>
#include <stdbool.h>

typedef pthread_mutex_t os_unfair_lock;
typedef os_unfair_lock *os_unfair_lock_t;
#define OS_UNFAIR_LOCK_INIT PTHREAD_MUTEX_INITIALIZER

static inline void os_unfair_lock_lock(os_unfair_lock_t l) { pthread_mutex_lock(l); }
static inline void os_unfair_lock_unlock(os_unfair_lock_t l) { pthread_mutex_unlock(l); }
static inline bool os_unfair_lock_trylock(os_unfair_lock_t l) { return pthread_mutex_trylock(l) == 0; }
