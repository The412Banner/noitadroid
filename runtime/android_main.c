// Android entry: SDL's activity (android/.../NoitaActivity.java) runs SDL_main from libmain.so, which sets up what
// the launcher (runtime/main.c, built with -Dmain=noitadroid_main) reads from the environment:
//   NOITA_DIR      <external files>/Noita: the game files, pushed with tools/android/push_game.sh
//   MINA4MAC_DATA  <external files>/save: the known folders (saves in AppData/LocalLow/Nolla_Games_Noita)
//   MINA4MAC_FMOD  the app's native library directory, where FMOD's libraries are when the APK has them
// Android drops stdout and stderr, where the runtime reports: they go to logcat (tag noitadroid) and to
// <external files>/noitadroid.log.
#include <SDL.h>
#include <android/log.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
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

static void env_default(const char *name, const char *base, const char *leaf) {
    if (getenv(name)) return;
    char v[1024];
    snprintf(v, sizeof v, "%s/%s", base, leaf);
    setenv(name, v, 1);
}

int SDL_main(int argc, char *argv[]) {
    const char *ext = SDL_AndroidGetExternalStoragePath();
    if (!ext) {
        __android_log_print(ANDROID_LOG_ERROR, "noitadroid", "no external files directory: %s", SDL_GetError());
        return 1;
    }
    char path[1024];
    snprintf(path, sizeof path, "%s/noitadroid.log", ext);
    redirect_stdio(path);
    env_default("NOITA_DIR", ext, "Noita");
    env_default("MINA4MAC_DATA", ext, "save");
    // NoitaActivity.getArguments() puts "--fmod-dir <dir>" first; the rest are the game's
    if (argc >= 3 && !strcmp(argv[1], "--fmod-dir")) {
        if (!getenv("MINA4MAC_FMOD")) setenv("MINA4MAC_FMOD", argv[2], 1);
        argv[2] = argv[0];
        argc -= 2, argv += 2;
    }
    fprintf(stderr, "[noitadroid] NOITA_DIR=%s MINA4MAC_DATA=%s MINA4MAC_FMOD=%s\n", getenv("NOITA_DIR"),
            getenv("MINA4MAC_DATA"), getenv("MINA4MAC_FMOD") ? getenv("MINA4MAC_FMOD") : "(none)");

    snprintf(path, sizeof path, "%s/noita.exe", getenv("NOITA_DIR"));
    if (access(path, R_OK)) {
        char msg[1400];
        snprintf(msg, sizeof msg,
                 "Noita's game files are not on this device.\n\nCopy the folder of your GOG install (Noita 20250125-1640) "
                 "to:\n%s\n\n(tools/android/push_game.sh does it over adb.)",
                 getenv("NOITA_DIR"));
        fprintf(stderr, "[noitadroid] %s missing\n", path);
        SDL_ShowSimpleMessageBox(SDL_MESSAGEBOX_ERROR, "noitadroid", msg, NULL);
        return 1;
    }
    return noitadroid_main(argc, argv);
}
