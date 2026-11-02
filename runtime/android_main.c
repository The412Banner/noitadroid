// Android entry: SDL's activity (android/.../NoitaActivity.java) runs SDL_main from libmain.so, which sets up what
// the launcher (runtime/main.c, built with -Dmain=noitadroid_main) reads from the environment. Everything lives in
// shared storage, <home> = /sdcard/noitadroid (the app has all-files access), so a file manager or MTP reaches it,
// adb can copy the game there, and saves outlive the app:
//   NOITA_DIR      <home>/Noita: the game files (tools/android/push_game.sh)
//   MINA4MAC_DATA  <home>/save: the known folders (saves in AppData/LocalLow/Nolla_Games_Noita)
//   MINA4MAC_FMOD  the app's native library directory, where FMOD's libraries are when the APK has them
// Android drops stdout and stderr, where the runtime reports: they go to logcat (tag noitadroid) and <home>/noitadroid.log.
#include <SDL.h>
#include <android/log.h>
#include <errno.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

int noitadroid_main(int argc, char **argv);

static FILE *LOG_FILE;

static void *pump(void *fd) {
    FILE *in = fdopen((int)(intptr_t)fd, "r");
    char line[2048];
    while (in && fgets(line, sizeof line, in)) {
        line[strcspn(line, "\n")] = 0;
        __android_log_write(ANDROID_LOG_INFO, "noitadroid", line);
        if (LOG_FILE) fprintf(LOG_FILE, "%s\n", line), fflush(LOG_FILE);
    }
    return NULL;
}

static void redirect_stdio(const char *log_path) {
    int p[2];
    if (pipe(p)) return;
    LOG_FILE = fopen(log_path, "w");
    setvbuf(stdout, NULL, _IOLBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
    dup2(p[1], STDOUT_FILENO);
    dup2(p[1], STDERR_FILENO);
    close(p[1]);
    pthread_t t;
    if (!pthread_create(&t, NULL, pump, (void *)(intptr_t)p[0])) pthread_detach(t);
}

static void env_default(const char *name, const char *home, const char *leaf) {
    if (getenv(name)) return;
    char v[1024];
    snprintf(v, sizeof v, "%s/%s", home, leaf);
    setenv(name, v, 1);
}

// The game sizes its window and back buffer from save_shared/config.xml (1280x720 windowed by default), but an
// Android window is always the whole screen: make the config fullscreen at the display's size before the game
// reads it (a minimal config when there is none yet; the game fills in the rest).
static void config_set(char *xml, size_t cap, const char *key, int value) {
    char pat[64], val[16];
    snprintf(pat, sizeof pat, " %s=\"", key);
    snprintf(val, sizeof val, "%d", value);
    char *at = xml;  // key="..." after any whitespace
    while ((at = strstr(at, pat + 1)) && (at == xml || !strchr(" \t\r\n", at[-1]))) at++;
    if (at) at--;
    if (at) {
        char *v = at + strlen(pat), *end = strchr(v, '"');
        if (!end || strlen(xml) - (size_t)(end - v) + strlen(val) >= cap) return;
        memmove(v + strlen(val), end, strlen(end) + 1);
        memcpy(v, val, strlen(val));
    } else if ((at = strstr(xml, "<Config")) && strlen(xml) + strlen(pat) + strlen(val) + 2 < cap) {
        char ins[96];
        snprintf(ins, sizeof ins, "%s%s\"", pat, val);
        at += strlen("<Config");
        memmove(at + strlen(ins), at, strlen(at) + 1);
        memcpy(at, ins, strlen(ins));
    }
}

static void config_fit_display(void) {
    SDL_DisplayMode mode;
    if (SDL_InitSubSystem(SDL_INIT_VIDEO) || SDL_GetDesktopDisplayMode(0, &mode)) {
        fprintf(stderr, "[noitadroid] no display mode: %s\n", SDL_GetError());
        return;
    }
    int w = mode.w > mode.h ? mode.w : mode.h, h = mode.w > mode.h ? mode.h : mode.w;  // landscape
    char dir[1024], path[1100];
    snprintf(dir, sizeof dir, "%s/AppData/LocalLow/Nolla_Games_Noita/save_shared", getenv("MINA4MAC_DATA"));
    snprintf(path, sizeof path, "%s/config.xml", dir);
    static char xml[1 << 16];
    FILE *f = fopen(path, "r");
    size_t n = f ? fread(xml, 1, sizeof xml - 1, f) : 0;
    if (f) fclose(f);
    xml[n] = 0;
    if (!strstr(xml, "<Config")) snprintf(xml, sizeof xml, "<Config\n>\n</Config>\n");
    config_set(xml, sizeof xml, "fullscreen", 1);
    config_set(xml, sizeof xml, "window_w", w);
    config_set(xml, sizeof xml, "window_h", h);
    config_set(xml, sizeof xml, "backbuffer_width", w);
    config_set(xml, sizeof xml, "backbuffer_height", h);
    mkdir(getenv("MINA4MAC_DATA"), 0775);
    for (char *p = dir + strlen(getenv("MINA4MAC_DATA")) + 1; (p = strchr(p, '/')); *p++ = '/') {
        *p = 0;
        mkdir(dir, 0775);
    }
    mkdir(dir, 0775);
    if ((f = fopen(path, "w"))) fputs(xml, f), fclose(f);
    fprintf(stderr, "[noitadroid] %s: fullscreen %dx%d\n", path, w, h);
}

static int fail(const char *msg) {
    fprintf(stderr, "[noitadroid] %s\n", msg);
    SDL_ShowSimpleMessageBox(SDL_MESSAGEBOX_ERROR, "noitadroid", msg, NULL);
    return 1;
}

int SDL_main(int argc, char *argv[]) {
    // The guest memory, gl4es and the game's own state can't be set up twice in one process. SDL runs SDL_main
    // again when the activity is created again in the same process: end the process so the next start is clean.
    static int entered;
    if (entered++) {
        __android_log_write(ANDROID_LOG_WARN, "noitadroid", "SDL_main again in the same process: exiting");
        exit(0);
    }
    // NoitaActivity.getArguments(): "--home <dir> --fmod-dir <dir>", then the game's own
    const char *home = NULL, *fmod = NULL;
    while (argc >= 3 && !strncmp(argv[1], "--", 2)) {
        if (!strcmp(argv[1], "--home")) home = argv[2];
        else if (!strcmp(argv[1], "--fmod-dir")) fmod = argv[2];
        argv[2] = argv[0];
        argc -= 2, argv += 2;
    }
    if (!home || !*home) home = "/storage/emulated/0/noitadroid";
    if (mkdir(home, 0775) && errno != EEXIST)
        __android_log_print(ANDROID_LOG_ERROR, "noitadroid", "mkdir %s: %s", home, strerror(errno));
    char path[1024];
    snprintf(path, sizeof path, "%s/noitadroid.log", home);
    redirect_stdio(path);
    env_default("NOITA_DIR", home, "Noita");
    env_default("MINA4MAC_DATA", home, "save");
    if (fmod && *fmod && !getenv("MINA4MAC_FMOD")) setenv("MINA4MAC_FMOD", fmod, 1);
    fprintf(stderr, "[noitadroid] NOITA_DIR=%s MINA4MAC_DATA=%s MINA4MAC_FMOD=%s\n", getenv("NOITA_DIR"),
            getenv("MINA4MAC_DATA"), getenv("MINA4MAC_FMOD") ? getenv("MINA4MAC_FMOD") : "(none)");

    snprintf(path, sizeof path, "%s/noita.exe", getenv("NOITA_DIR"));
    if (access(path, R_OK)) {
        char msg[1400];
        snprintf(msg, sizeof msg,
                 "Noita's game files are not readable at\n%s\n(%s)\n\nCopy the folder of your GOG install "
                 "(Noita 20250125-1640) there; tools/android/push_game.sh does it over adb. The app also needs "
                 "\"All files access\" in Android's settings.",
                 getenv("NOITA_DIR"), strerror(errno));
        return fail(msg);
    }
    config_fit_display();
    int r = noitadroid_main(argc, argv);
    fprintf(stderr, "[noitadroid] the game returned %d\n", r);
    exit(r);
}
