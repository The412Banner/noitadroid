// Scheduling knobs (sched.h). The M1 Max has 8 performance and 2 efficiency cores; jobs that land on an E-core
// run several times slower and become the tail of the game's chunk-update passes (docs/PROFILE.md "Job system").
#include "sched.h"

#include <pthread.h>
#include <sched.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/qos.h>
#include <sys/sysctl.h>
#include <unistd.h>
#ifdef __linux__
#include <dirent.h>
#include <errno.h>
#include <limits.h>
#endif

static uint32_t NCPU;  // 0 until sched_init (the tests don't call it): all CPUs
// User-interactive QoS keeps main (and the Box2D step it waits for) on the P-cores: jungle work_ms -8.2%, heavy
// and flood neutral (PLAN4 Phase 16). It only takes effect after sched_init, so the tests stay unspecified.
static qos_class_t QOS = QOS_CLASS_UNSPECIFIED;
// The game's job wait loop (while (pending) _Thrd_yield()) calls Sleep(0) 40-70k times a frame. usleep(0)
// returns at once, so main spins on a core the 9 workers need, pushing them onto the E-cores; a 20 µs nap
// frees it and notices the end of a 2-3 ms pass late by at most ~20 µs.
static enum { Y_USLEEP, Y_SCHED, Y_SPIN, Y_NAP } YIELD = Y_NAP, YIELD_SET = Y_NAP;  // YIELD_SET: MINA4MAC_YIELD's
static useconds_t NAP_US = 20;

static long sysctl_int(const char *name) {
    int v = 0;
    size_t n = sizeof v;
    return sysctlbyname(name, &v, &n, NULL, 0) ? -1 : v;
}

static const char *env(const char *name, const char *dflt) {
    const char *v = getenv(name);
    return v && *v ? v : dflt;
}


#ifdef __linux__
// Android phones and handhelds mix kinds of cores. The Pocket S (Snapdragon G3x Gen 2) has three slow A510s
// (cpu0-2, capacity 266), four big cores (cpu3-6, 811) and a prime core (cpu7, 1024). The game sizes its job pools
// from the CPU count, and each chunk-update pass waits for its slowest job, so a job on a small core holds up the
// frame; the main thread naps while it waits, and the scheduler may take that for a light thread and move it to a
// small core. macOS gets a QoS class (above); here the game's threads get a CPU affinity, by mode
// (NOITADROID_CORES, which the in-game menu sets, also while the game runs: sched_set_cores):
//   system  Android places every thread; the CPU count is all cores
//   big     the main and guest threads stay off the slowest kind of core; the count is the rest
//   prime   the main thread runs on the fastest kind of core, alone: guest threads take the kinds in between, other
//           threads (FMOD, the GL driver, Java) the rest; the count is the guest cores + 1. Qualcomm's core control
//           pauses the fastest core when its load is low (the Pocket S's cpu7, below 30%), and the kernel won't put
//           a thread on a paused core only: meanwhile the main thread runs on the kinds in between, until the keeper
//           can move it back. Allowed on both, it would stay off the fastest core: the scheduler doesn't move it up.
//           With two kinds of cores, it's big.
//   split   as prime, but guest threads take all the other cores, the slowest too.
//   fast    split, and Sleep(0) spins (MINA4MAC_YIELD=spin): the main thread's job waits keep its core busy, so core
//           control doesn't pause it and its clock stays up.
// On the Pocket S (docs/ANDROID.zh.md, "性能"), split was 16% faster than system in perfbench's jungle scene (mobs:
// the main thread's work) and 8% in heavy (liquids: the guest threads'); fast 25% and 14%, a few degrees hotter;
// big and prime lost in heavy (fewer guest threads). The in-game menu offers system, split and fast.
// A thread inherits its creator's affinity, and Android resets it when the app moves between cgroups (foreground,
// background), so a keeper thread puts each of the process's threads where the mode wants it, once a second.
enum { CORES_SYSTEM, CORES_BIG, CORES_PRIME, CORES_SPLIT, CORES_FAST, NCORE_MODES };
static const char *const CORE_MODES[] = {"system", "big", "prime", "split", "fast"};
#define MAXCPU 64
static long CAP[MAXCPU];  // each CPU's capacity (or its top frequency), for telling the kinds of cores apart
static int NCONF;
static pthread_mutex_t CORES_M = PTHREAD_MUTEX_INITIALIZER;
static int CORES_MODE = -1;  // -1 until sched_init
static unsigned CORES_GEN, CORES_APPLIED = -1u;
static cpu_set_t MAIN_SET, MAIN_FALLBACK, GUEST_SET, OTHER_SET;
static pid_t MAIN_TID;
static unsigned MAIN_FAILED;
static pid_t GUEST_TIDS[256];
static int NGUEST;

static long read_long(const char *path) {
    long v = -1;
    FILE *f = fopen(path, "r");
    if (f) {
        if (fscanf(f, "%ld", &v) != 1) v = -1;
        fclose(f);
    }
    return v;
}

static void cores_probe(void) {
    long n = sysconf(_SC_NPROCESSORS_CONF);
    NCONF = n < 1 ? 1 : n > MAXCPU ? MAXCPU : (int)n;
    for (int i = 0; i < NCONF; i++) {
        char p[96];
        snprintf(p, sizeof p, "/sys/devices/system/cpu/cpu%d/cpu_capacity", i);
        if ((CAP[i] = read_long(p)) <= 0) {
            snprintf(p, sizeof p, "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i);
            CAP[i] = read_long(p);
        }
    }
}

static int mode_of(const char *s) {
    for (int i = 0; i < NCORE_MODES; i++)
        if (s && !strcmp(s, CORE_MODES[i])) return i;
    return CORES_SYSTEM;
}

// The sets for a mode; returns the CPU count the game should see.
static int cores_sets(int mode, cpu_set_t *main, cpu_set_t *fallback, cpu_set_t *guest, cpu_set_t *other) {
    long lo = LONG_MAX, hi = 0;
    int known = 1;
    cpu_set_t all, big, top, mid;
    CPU_ZERO(&all);
    CPU_ZERO(&big);
    CPU_ZERO(&top);
    CPU_ZERO(&mid);
    for (int i = 0; i < NCONF; i++) {
        CPU_SET(i, &all);
        if (CAP[i] <= 0) known = 0;
        if (CAP[i] < lo) lo = CAP[i];
        if (CAP[i] > hi) hi = CAP[i];
    }
    *main = *fallback = *guest = *other = all;
    if (mode == CORES_SYSTEM || !known || lo == hi) return NCONF;
    for (int i = 0; i < NCONF; i++) {
        if (CAP[i] > lo) CPU_SET(i, &big);
        if (CAP[i] == hi) CPU_SET(i, &top);
        else if (CAP[i] > lo) CPU_SET(i, &mid);
    }
    if (mode == CORES_BIG || !CPU_COUNT(&mid)) {
        *main = *fallback = *guest = big;
        return CPU_COUNT(&big);
    }
    CPU_ZERO(other);
    for (int i = 0; i < NCONF; i++)
        if (!CPU_ISSET(i, &top)) CPU_SET(i, other);
    *main = top, *fallback = big;
    *guest = mode == CORES_SPLIT || mode == CORES_FAST ? *other : mid;
    return CPU_COUNT(guest) + 1;
}

static const char *set_str(const cpu_set_t *s, char *buf, size_t n) {  // "3-6,8"
    size_t at = 0;
    buf[0] = 0;
    for (int i = 0; i < NCONF && at < n; i++) {
        if (!CPU_ISSET(i, s)) continue;
        int j = i;
        while (j + 1 < NCONF && CPU_ISSET(j + 1, s)) j++;
        at += j > i ? snprintf(buf + at, n - at, "%s%d-%d", at ? "," : "", i, j)
                    : snprintf(buf + at, n - at, "%s%d", at ? "," : "", i);
        i = j;
    }
    return buf;
}

static int is_guest(pid_t tid) {
    int n = __atomic_load_n(&NGUEST, __ATOMIC_ACQUIRE);
    for (int i = 0; i < n && i < 256; i++)
        if (GUEST_TIDS[i] == tid) return 1;
    return 0;
}

// Puts every thread of the process where the mode wants it. In system mode only once after a change, to undo the
// last mode's sets.
static void cores_apply(void) {
    pthread_mutex_lock(&CORES_M);
    cpu_set_t m = MAIN_SET, fb = MAIN_FALLBACK, g = GUEST_SET, o = OTHER_SET;
    unsigned gen = CORES_GEN;
    int idle = CORES_MODE == CORES_SYSTEM && CORES_APPLIED == gen;
    CORES_APPLIED = gen;
    pthread_mutex_unlock(&CORES_M);
    if (idle) return;
    DIR *d = opendir("/proc/self/task");
    if (!d) return;
    struct dirent *e;
    while ((e = readdir(d))) {
        pid_t tid = (pid_t)atoi(e->d_name);
        if (tid <= 0) continue;
        const cpu_set_t *want = tid == MAIN_TID ? &m : is_guest(tid) ? &g : &o;
        cpu_set_t have;
        if (!sched_getaffinity(tid, sizeof have, &have) && CPU_EQUAL(&have, want)) continue;
        // fails for any thread while the cgroup has none of its cores (background)
        if (!sched_setaffinity(tid, sizeof *want, want) || tid != MAIN_TID) continue;
        // the main thread's cores are paused (core control): the fallback until they're back
        int err = errno;
        if (!CPU_EQUAL(&have, &fb)) sched_setaffinity(tid, sizeof fb, &fb);
        if (MAIN_FAILED != gen + 1 && getenv("NOITADROID_CORES_DEBUG")) {
            MAIN_FAILED = gen + 1;
            fprintf(stderr, "[noitadroid] cores: main thread %d: %s, on the fallback\n", tid, strerror(err));
        }
    }
    closedir(d);
}

static void *cores_keeper(void *arg) {
    (void)arg;
    pthread_setname_np("nd cores");
    for (;;) {
        cores_apply();
        sleep(1);
    }
    return NULL;
}

// Sets CORES_MODE and the sets; returns the CPU count for it. Callers hold CORES_M.
static int cores_set_mode(int mode) {
    CORES_MODE = mode;
    CORES_GEN++;
    return cores_sets(mode, &MAIN_SET, &MAIN_FALLBACK, &GUEST_SET, &OTHER_SET);
}

static int cores_init(void) {
    cores_probe();
    MAIN_TID = gettid();
    pthread_mutex_lock(&CORES_M);
    int n = cores_set_mode(mode_of(env("NOITADROID_CORES", "system")));
    cpu_set_t m = MAIN_SET;
    pthread_mutex_unlock(&CORES_M);
    if (sched_setaffinity(0, sizeof m, &m)) sched_setaffinity(0, sizeof MAIN_FALLBACK, &MAIN_FALLBACK);
    char caps[256] = "", a[64], b[64], c[64];
    for (int i = 0, at = 0; i < NCONF && at < (int)sizeof caps; i++)
        at += snprintf(caps + at, sizeof caps - at, "%s%ld", i ? " " : "", CAP[i]);
    fprintf(stderr, "[noitadroid] cores %s (capacities %s): main %s, guests %s, others %s; %d cpus for the game\n",
            CORE_MODES[CORES_MODE], caps, set_str(&MAIN_SET, a, sizeof a), set_str(&GUEST_SET, b, sizeof b),
            set_str(&OTHER_SET, c, sizeof c), n);
    pthread_t t;
    if (!pthread_create(&t, NULL, cores_keeper, NULL)) pthread_detach(t);
    return n;
}

void sched_set_cores(const char *mode) {
    pthread_mutex_lock(&CORES_M);
    if (CORES_MODE < 0) {  // before the game's start: it reads the env var
        pthread_mutex_unlock(&CORES_M);
        setenv("NOITADROID_CORES", mode, 1);
        return;
    }
    cores_set_mode(mode_of(mode));  // the job pools keep their size until the next start
    __atomic_store_n(&YIELD, CORES_MODE == CORES_FAST ? Y_SPIN : YIELD_SET, __ATOMIC_RELAXED);
    char a[64], b[64], c[64];
    fprintf(stderr, "[noitadroid] cores %s: main %s, guests %s, others %s\n", CORE_MODES[CORES_MODE],
            set_str(&MAIN_SET, a, sizeof a), set_str(&GUEST_SET, b, sizeof b), set_str(&OTHER_SET, c, sizeof c));
    pthread_mutex_unlock(&CORES_M);
    cores_apply();
}
#endif

#ifdef __ANDROID__
void sched_set_hint(int on);
#endif

void sched_init(void) {
    const char *cpus = env("MINA4MAC_CPUS", "all");
    long n = sysconf(_SC_NPROCESSORS_ONLN);
    if (!strcmp(cpus, "pcores")) {
        long p = sysctl_int("hw.perflevel0.logicalcpu");  // absent on Intel Macs: keep them all
        if (p > 0) n = p;
    } else if (strcmp(cpus, "all")) {
        n = atol(cpus);
    }
#ifdef __linux__
    int cores = cores_init();
    if (!strcmp(cpus, "all")) n = cores;  // a number in MINA4MAC_CPUS still wins
#endif
#ifdef __ANDROID__
    sched_set_hint(!strcmp(env("NOITADROID_HINT", "0"), "1"));
#endif
    NCPU = n < 1 ? 1 : n > 32 ? 32 : (uint32_t)n;

    const char *qos = env("MINA4MAC_QOS", "interactive");
    QOS = !strcmp(qos, "none")      ? QOS_CLASS_UNSPECIFIED
        : !strcmp(qos, "initiated") ? QOS_CLASS_USER_INITIATED
                                    : QOS_CLASS_USER_INTERACTIVE;

    const char *y = env("MINA4MAC_YIELD", "nap20");
    if (!strcmp(y, "sched")) YIELD = Y_SCHED;
    else if (!strcmp(y, "spin")) YIELD = Y_SPIN;
    else if (!strcmp(y, "usleep")) YIELD = Y_USLEEP;
    else if (!strncmp(y, "nap", 3)) YIELD = Y_NAP, NAP_US = (useconds_t)atol(y + 3);
    YIELD_SET = YIELD;
#ifdef __linux__
    if (CORES_MODE == CORES_FAST) YIELD = Y_SPIN;
#endif
    if (getenv("MINA4MAC_CPUS") || getenv("MINA4MAC_QOS") || getenv("MINA4MAC_YIELD"))
        fprintf(stderr, "[mina4mac] sched: %u cpus, qos %s, yield %s\n", NCPU, qos, y);
    sched_thread_start();
}

uint32_t sched_ncpu(void) {
    if (NCPU) return NCPU;
    long n = sysconf(_SC_NPROCESSORS_ONLN);
    return n < 1 ? 1 : n > 32 ? 32 : (uint32_t)n;
}

void sched_thread_start(void) {
    if (QOS != QOS_CLASS_UNSPECIFIED) pthread_set_qos_class_self_np(QOS, 0);
#ifdef __linux__
    pid_t tid = gettid();
    if (tid == MAIN_TID) return;  // sched_init's call: the main thread has its own cores
    pthread_mutex_lock(&CORES_M);
    int at = NGUEST < 256 ? NGUEST : -1;
    if (at >= 0) GUEST_TIDS[at] = tid, __atomic_store_n(&NGUEST, at + 1, __ATOMIC_RELEASE);
    cpu_set_t g = GUEST_SET;
    int set = CORES_MODE > CORES_SYSTEM;
    pthread_mutex_unlock(&CORES_M);
    if (set) sched_setaffinity(0, sizeof g, &g);
#endif
}

void sched_sleep0(void) {
    switch (__atomic_load_n(&YIELD, __ATOMIC_RELAXED)) {
    case Y_USLEEP: usleep(0); break;
    case Y_SCHED: sched_yield(); break;
    case Y_SPIN:  // about 1-2 µs of yield hints before giving up the core
        for (int i = 0; i < 64; i++) __builtin_arm_yield();
        sched_yield();
        break;
    case Y_NAP: usleep(NAP_US); break;
    }
}

// Frame-time hints (Android 13 and later, NOITADROID_HINT=1): a performance hint session (ADPF) for the main and
// guest threads, with a 16.7 ms target, told each frame's work: the main thread's time from one swap's return to the
// next swap (MINA4MAC_FRAMELOG's work_ms). When frames run long the system should raise those threads' clocks, rather
// than let them drop while the main thread naps in its job waits. On the Pocket S it changed nothing (docs/ANDROID.zh.md,
// "性能"), so it's off unless asked for; other devices' power HALs may do more with it.
#ifdef __ANDROID__
#include <dlfcn.h>
static struct {
    void *(*get_manager)(void);
    void *(*create)(void *, const int32_t *, size_t, int64_t);
    int (*report)(void *, int64_t);
    void (*close)(void *);
    void *manager, *session;
    int loaded, tried;  // tried: the guest thread count the last session was opened for (-1: none)
    int on;
    uint64_t t0;
} HINT = {.tried = -1};

static uint64_t hint_now(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (uint64_t)t.tv_sec * 1000000000u + (uint64_t)t.tv_nsec;
}

static void hint_close(void) {
    if (HINT.session) HINT.close(HINT.session), HINT.session = NULL;
    HINT.tried = -1;
}

// On the main thread: a session for it and the guest threads there are now.
static void hint_open(int guests) {
    if (!HINT.loaded) {
        HINT.loaded = 1;
        void *lib = dlopen("libandroid.so", RTLD_NOW);
        if (lib) {
            HINT.get_manager = (void *(*)(void))dlsym(lib, "APerformanceHint_getManager");
            HINT.create = (void *(*)(void *, const int32_t *, size_t, int64_t))dlsym(lib, "APerformanceHint_createSession");
            HINT.report = (int (*)(void *, int64_t))dlsym(lib, "APerformanceHint_reportActualWorkDuration");
            HINT.close = (void (*)(void *))dlsym(lib, "APerformanceHint_closeSession");
        }
        if (HINT.get_manager && HINT.create && HINT.report && HINT.close) HINT.manager = HINT.get_manager();
    }
    if (HINT.session) HINT.close(HINT.session), HINT.session = NULL;
    HINT.tried = guests;
    if (!HINT.manager) {
        fprintf(stderr, "[noitadroid] frame-time hint: not on this Android version\n");
        return;
    }
    int32_t tids[257];
    int n = 0;
    tids[n++] = gettid();
    for (int i = 0; i < guests && i < 256; i++) tids[n++] = GUEST_TIDS[i];
    HINT.session = HINT.create(HINT.manager, tids, (size_t)n, 16666667);
    fprintf(stderr, "[noitadroid] frame-time hint: %s (%d threads)\n", HINT.session ? "on" : "refused", n);
}

void sched_frame(int after_swap) {
    if (after_swap) {
        HINT.t0 = hint_now();
        return;
    }
    if (!__atomic_load_n(&HINT.on, __ATOMIC_RELAXED)) {
        if (HINT.tried >= 0) hint_close();
        return;
    }
    int guests = __atomic_load_n(&NGUEST, __ATOMIC_ACQUIRE);
    if (HINT.tried != guests) hint_open(guests);  // and again when the game starts more threads
    if (HINT.session && HINT.t0) HINT.report(HINT.session, (int64_t)(hint_now() - HINT.t0));
}

void sched_set_hint(int on) {
    __atomic_store_n(&HINT.on, on, __ATOMIC_RELAXED);
}
#else
void sched_frame(int after_swap) {
    (void)after_swap;
}
#endif
