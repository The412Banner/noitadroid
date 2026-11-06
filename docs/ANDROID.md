# noitadroid: a native Android port of Noita

Based on [mina4mac](https://github.com/paulstraw/mina4mac) (remote `upstream`; this repo's working branch is `android`). mina4mac statically recompiles the 32-bit x86 `noita.exe` to C, then uses its own runtime in place of Windows, the MSVC CRT, SDL2, OpenGL, LuaJIT and FMOD. This project adds Linux and Android hosts on top of that. The goal is to run Noita natively on the AYANEO Pocket S (Snapdragon G3x Gen 2 / Adreno A32), without Wine and without x86 emulation.

## Rules (kept from upstream)

- Bring your own game files, for personal use only. **Don't commit** any game files, `data.wak`, generated C code or FMOD libraries. `build/` and `.local-deps/` are both in gitignore.
- The APK you build contains code converted from the game, so build it only on your own machine and install it only on your own device. Don't distribute it.
- Supported: GOG build `20250125-1640` (`setup_noita_20250125-1640_(79502).exe`) and, in this fork, the Steam build of the same day, a different binary of the same game (`tools/game_build.py`). The speed data in `tools/` is GOG's build's.
- Change upstream files as little as possible. Platform differences all go in new files, so upstream updates merge easily.

## Architecture (Android)

```
APK
├─ Java: SDLActivity (SDL2's official Android shell) + launch configuration
├─ libmain.so: the recompiled noita.exe (C) + runtime/*.c (Windows/CRT HLE, the bridges)
│    ├─ LuaJIT 2.1 (arm64, JIT on, statically linked)
│    ├─ SDL2 (Android version)
│    ├─ gl4es: turns desktop GL 2.1 compatibility mode into GLES; gl4es has no sync objects (glFenceSync etc.), so the GL bridge calls GLES3 directly
│    └─ FMOD Engine 2.01.x for Android (downloaded by the user, dlopen)
└─ Game data: /sdcard/Android/data/<package>/files/Noita (adb push, or imported at first launch)
```

Guest memory: one 4 GB anonymous mapping at the fixed host address `0x1000004000`. The `0x200000004000` used on macOS is beyond the Android kernel's 39-bit (512 GB) user address space (see `runtime/compat/compat.h`).

## Stages

| Stage | Content | Needs game files | Status |
|---|---|---|---|
| 0 | Platform compatibility layer `runtime/compat/` | No | Done (2026-11-02) |
| 1 | Linux host build (x86_64, on the dev machine): `build_all.py` switches compiler and link flags by target; `gen_gl`/`gen_sdl` now generate from the Linux headers; get it running with Xvfb + Mesa, then survey the GL calls (`tools/glsurvey.py`) | Yes | **Playable** (2026-11-02): 100,129 functions recompiled, main menu and new game both work, gl4es at about 36 fps on llvmpipe; to look into: `GL_PIXEL_PACK_BUFFER` |
| 2 | Android build: NDK, SDL2 for Android, gl4es (plus GLES3 sync objects), FMOD for Android, paths and known folders, Gradle packaging into an APK | Yes | **Playable on the Pocket S** (2026-11-02): fullscreen 1920×1080, in the world within 15 seconds of starting a game, 50–57 fps in the caves (60 Hz screen), gamepad and touchscreen both work; no FMOD (silent) |
| 3 | Pocket S tuning: main thread and worker threads on the big cores (X3 + A715) (`sched.c`), thermals, frame rate cap, gamepad, resolution | Yes | Not started |

Stage 1 is there to lower the risk: the whole game runs and can be debugged on the dev machine, without the real device. There we first get the list of GL calls and shaders Noita actually uses, and then decide between gl4es and Zink on Android.

## Graphics path: gl4es on both Linux and Android

The game wants desktop OpenGL (2.1 compatibility mode), and its GL loader picks which functions to use by `GL_VERSION`. So three things must agree: the version the driver reports, the extension list, and the bridged functions.

On macOS, the system's legacy 2.1 context and its headers take care of these three. On Linux and Android, the game's GL is **gl4es** (desktop GL 2.1 on top of GLES, which also reports its own 2.1 and extension list):

- `runtime/gles.c` takes over `SDL_Init`, `SDL_GL_SetAttribute` and `SDL_GL_CreateContext` through `-Wl,--wrap` at link time. Whatever desktop GL context the game asks for, what it actually creates is a GLES 3.0 context (on X11 through EGL, not GLX), and then it initializes gl4es.
- `tools/gen_gl.py` bridges only the functions gl4es really implements (`target.gl_provided_names()`, taken from libGL.a's symbol table): 616 of 1,044 names are bridged, 605 of them generated and 11 written by hand.
- 3 more functions are written by hand (`runtime/opengl32.c`, non-Apple platforms only): `glMapBufferRange` and `glFlushMappedBufferRange` (gl4es claims `GL_ARB_map_buffer_range`; like `glMapBuffer`, they keep a shadow copy in the guest heap), and `glGetStringi`.
- gl4es for Linux and for Android is built from the same commit, and exports exactly the same GL functions (1,244). So the desktop development build runs Android's graphics path.

On the dev machine, `tools/run_linux.sh` starts an Xvfb without GLX and forces Mesa's EGL (llvmpipe, GLES 3.2). NVIDIA's EGL needs a real NVIDIA X screen, and crashes on Xvfb.

## What stage 0 did

The build force-includes `runtime/compat/compat.h` (`-include`) and puts `runtime/compat/` first on the include path, to stand in for Darwin's headers:

| Darwin | Replacement |
|---|---|
| `os/lock.h` (`os_unfair_lock`) | pthread mutex |
| `sys/qos.h` (QoS classes) | Empty implementation; core placement on Android is done in stage 3 |
| `sys/sysctl.h` (`sysctlbyname`) | Returns failure, and callers fall back to defaults |
| `mach-o/dyld.h` (`_NSGetExecutablePath`) | `/proc/self/exe` |
| `OpenGL/*.h` (CGL) | `GL/gl.h`; whether the current thread has a GL context is checked with `SDL_GL_GetCurrentContext` instead |
| `clock_gettime_nsec_np`, `CLOCK_UPTIME_RAW` | `clock_gettime(CLOCK_MONOTONIC)` |
| `st_birthtimespec` and other BSD time fields | `st_mtim`/`st_atim` |
| `pthread_setname_np` taking one argument | Linux's two-argument version, with the name cut to 15 bytes |
| `pthread_cpu_number_np` | `sched_getcpu` |
| `__builtin_arm_yield` (x86_64 host) | `__builtin_ia32_pause` |

There is only one change to upstream files: `runtime/cpu.h`'s `MEM_HOST_BASE` may be predefined by the platform.

Note: on Linux and Android you must use `-iquote runtime`, not `-I runtime`. glibc's `pthread.h` does `#include <sched.h>`, and with `-I` it would find `runtime/sched.h`.

Checked (2026-11-02): apart from `sdl2.c` and `sdl_test.c`, which need generated headers, `runtime/*.c` compiles with the host gcc (x86_64-linux-gnu) and with NDK r29 clang 21 (`aarch64-linux-android29`), with strict checks such as `-Werror=implicit-function-declaration` on. LuaJIT 2.1 also cross-compiled with the NDK into an Android arm64 static library.

```sh
NDK=${ANDROID_NDK_HOME:-.local-deps/ndk}  # NDK r29
COMPAT="-include runtime/compat/compat.h -I runtime/compat -iquote runtime -I third_party/luajit/src"
$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang --target=aarch64-linux-android29 -fsyntax-only $COMPAT runtime/heap.c
```

## Linux development build

```sh
export NOITA_DIR=$PWD/.local-deps/noita-gog          # the GOG install folder, unpacked with innoextract --gog
for m in noita msvcp120; do uv run tools/discover.py $m; done   # only needed once
uv run tools/build_all.py                             # about 9 minutes on 12 cores: regsum 188 s, recompile 39 s, compile 80 s, ThinLTO link 208 s
MINA4MAC_FPS=1 tools/run_linux.sh                     # saves go in ~/.local/share/noitadroid
MINA4MAC_CLICKS="15:639,350;20:445,250" tools/run_linux.sh --shot build/s.png 90   # scripted clicks start a new game, screenshot at 90 seconds
```

First run (2026-11-02, Xvfb + llvmpipe):
- A changelog pops up first; click "Ok" (85,662) to close it. After that the main menu and new game both work.
- While the world is generated, one frame takes about 65 seconds.
- In the game, 34–37 fps (1280×720).
- gl4es sometimes reports `unhandled Buffer type GL_PIXEL_PACK_BUFFER`: gl4es doesn't support the game's PBO readback, and we need to find out what the game uses it for.

`tools/gen_sdl.py`: SDL2's own headers use anonymous `typedef struct {…} SDL_DisplayMode;`, which clang's layout dump writes as `(unnamed at …)`. So the typedef names have to be taken from the source. In the sdl2-compat headers used on macOS, these structs have names.

## Android game build

```sh
export NOITA_DIR=$PWD/.local-deps/noita-gog
uv run tools/build_all.py                    # host build first: the recompiled C is the same for both
uv run tools/android/build_game.py           # about 5 minutes: bridges regenerated for Android, NDK compile, ThinLTO link into libmain.so, APK packaging
adb install -r build/android/noitadroid.apk
tools/android/push_game.sh                   # about 1.5 GB: noita.exe, msvcp120.dll, data/, mods/ etc.
adb logcat -s noitadroid                     # also written to /sdcard/noitadroid/noitadroid.log
```

The folders on the device are all under `/sdcard/noitadroid/` (shared storage; the app needs "All files access", which `push_game.sh` grants with `appops`):
- `Noita/`: the game files;
- `save/`: saves (in `AppData/LocalLow/Nolla_Games_Noita`; uninstalling the app doesn't delete them);
- `noitadroid.log`: the runtime log.

Why not the app's own folder (`Android/data/<package>/files`): on Android 13, folders copied in from adb shell are owned by shell, with permissions `drwxrws---`, and the app can't read them; and `run-as` can't get into `/sdcard`.

What `runtime/android_main.c` does:
- It implements `SDL_main`, the entry point SDL calls, which then calls `runtime/main.c`; the latter is renamed to `noitadroid_main` when compiled;
- On every launch it sets `save_shared/config.xml` to fullscreen at the screen's size, because the game defaults to a 1280×720 window, which on Android is only drawn in the bottom-left corner of the screen;
- The second time `SDL_main` is entered in the same process, it exits the process right away: guest memory and gl4es can't be initialized twice in one process, and the smoke test showed incomplete FBOs and addresses already in use.

`libmain.so` exports only `SDL_main` and two JNI functions of `NoitaActivity` (frame count, pause). `build_game.py` builds incrementally: after a change to one runtime file it takes about 1 minute, of which recompiling the 1 file and linking take about 20 seconds.

FMOD: run `tools/android/setup_fmod.sh`. It first looks for `fmodstudioapi201*android.tar.gz` in `~/Downloads` and `.local-deps`; if it finds none, it asks in the terminal for your fmod.com account and password, logs in and downloads the 2.01.23 Android package. The account and password are sent only to fmod.com, and aren't saved. Then it checks that the version is 2.01.x, and unpacks it into `.local-deps/fmod/`, so that `api/core/lib/arm64-v8a/libfmod.so`, `api/studio/lib/arm64-v8a/libfmodstudio.so` and `api/core/lib/fmod.jar` are in place. `build_game.py` then packs them into the APK. Without FMOD the game runs silently. The FMOD libraries are loaded on the Java side by `NoitaActivity.getLibraries()`, because libfmod.so needs the Java VM in `JNI_OnLoad` to ask `org.fmod.FMOD` about the audio output. After that `runtime/fmod.c` dlopens the same libraries and looks up symbols by handle, because Android's `RTLD_DEFAULT` skips RTLD_LOCAL libraries loaded by Java. Checked on the Pocket S: 2.01.23 works, with AAudio low-latency MMAP output.

First screen: the launcher entry is `SetupActivity`. It uses `GameCheck` to check four things: all files access; noita.exe, msvcp120.dll and data/data.wak in `/sdcard/noitadroid/Noita`; the game version (by comparing hashes); and whether FMOD is in the APK. It compares against the values `build_game.py` writes into `assets/noitadroid.properties` at build time: the sha256 of noita.exe and msvcp120.dll in the builder's copy of the game, the size of data.wak, and the FMOD version. When the required items are all met, it starts `NoitaActivity` straight away without showing anything (the first time it has to compute the hashes, about 0.1 seconds; after that they're cached by file size and modification time). Otherwise it shows a checklist in English and Chinese, where each item says what to do. It works with touch and gamepad, and checks again when it comes back to the foreground. Once it has the permission, it creates the Noita folder by itself, for copying over USB. Tested on the Pocket S: the four cases everything ready, no permission, missing game files and wrong version, and checking again with the gamepad's A button.

Interface language (`Lang`): the first screen and the in-game menu are in English by default, and don't follow the system language; both can be switched to Chinese, which is stored in SharedPreferences. The first screen changes language with `attachBaseContext`, and the menu with its own localized Context, so a switch applies at once, without restarting the game. The first screen stays only when it was just opened or the language was just switched; after that, each time it comes back to the foreground and everything is ready, it goes straight into the game, for example when coming back from granting the permission or from tapping the icon on the home screen.

In-game menu (`GameDrawer`): in the game, swipe right from the left edge of the screen to pull out a side panel.
- System gesture navigation also uses this edge for "back", so `NoitaActivity` excludes the middle 200 dp of the left edge (the most Android allows) from system gestures. Swiping inside that part opens the panel.
- While the side panel is open the game stops: `runtime/gles.c` wraps `SDL_GL_SwapWindow` and waits at the end of the frame, and FMOD's `System::mixerSuspend` stops the sound.
- When the app goes to the background, it first releases this wait and leaves the pausing to SDL; back in the foreground, it lets the game draw 4 frames before stopping it, so the game shows behind the side panel instead of a black screen.
- Menu contents:
  - Save export: saved with SAF as a zip laid out like the PC version's `Nolla_Games_Noita`, with save00 and save_shared.
  - Save import: takes save00 out of the zip into a staging area first; after you confirm, `RestartActivity` (a separate process, `:restart`) restarts the game. The new `NoitaActivity` swaps the save in before the game starts, and the old save is renamed to `save00.backup-<date>`.
  - Gamepad button swap: `PadMap`, always a one-to-one permutation, which replaces key codes before the keys reach SDL.
  - FPS/temperature display: `StatsOverlay`; the frame count comes from the swap count, and temperatures from the thermal zones the app is allowed to read.
- It works with touch and gamepad; B goes back one page.
- Checked on the Pocket S: opening with the edge swipe, 0 FPS while open and about 60 FPS after closing, the button swap screen, export (1.2 MB, 131 files), restart and backup after import, the audio stream stopping while the menu is open.

Touch controls (`TouchOverlay`, `TouchControls`, `TouchEditor`, `GameState`, all in Java, so the game doesn't need rebuilding):

- To the game it's just a gamepad: `SDLControllerManager.nativeAddJoystick` adds a virtual device, which SDL recognizes as a controller with the standard mapping. Aiming doesn't use mouse emulation: the game's camera drifts toward the cursor with a lag, so the player's position on the screen keeps changing, and an aim angle of "screen center + stick direction" can be off by up to ten-odd degrees. The gamepad's right stick direction is exact, and the game draws the crosshair itself.
- The left stick drives the stick axes, and pushing it up also presses the left trigger (fly); the right stick drives the stick axes, and pushing it past the inner ring presses the right trigger (fire). The buttons use the gamepad's default layout: B kick, Y throw, A interact, Back inventory, LB/RB switch item, Start pause. The game polls the gamepad state once per frame, so each press is held for at least 100 ms; otherwise a quick tap could fall between two frames.
- Fingers outside the buttons all act as the mouse, whether the touch controls are shown or not. SDL's own "touch to mouse" isn't used here: it puts the move and the press in the same frame, but the game's UI looks at what the mouse was over in the previous frame. So it moves first, and presses 70 ms later.
- When they show is decided by `GameState`, which reads three of the game's own flags every 100 ms (through `/proc/self/mem`, without changing the game's code; only one game version is supported, so the addresses are fixed): `0x12056f0` is the object pointer of the main menu and the pause menu, `0x1205798` the object pointer of the game over screen, and `0x1220630` the return value of the Lua function `GameIsInventoryOpen`. When either of the first two is non-zero, everything is hidden; when the third is non-zero, only the inventory button stays. When they can't be read, it's treated as "playing", so the buttons don't disappear.
- The layout is stored as screen proportions, so it also works at another resolution. The mode defaults to "Auto": shown when there's no physical gamepad.
- Checked on the OPPO Find X9 Ultra (multi-touch injected with `InputManagerGlobal.injectInputEvent` in `app_process`): moving with the left hand, aiming with the right and kicking with a third finger at the same time, aiming in four directions and diagonally, firing by tapping the screen outside the buttons, dragging items, showing and hiding in the menus, the inventory, the pause screen and the death screen, and layout editing. A physical gamepad and the touch controls switched on at the same time hasn't been tested yet.

Exit and restart:
- The game's `exit` doesn't go through libc's `exit()` but `_Exit`: the libraries' destructors would destroy their locks under the FMOD and driver threads that are still running (`FORTIFY: pthread_mutex_lock called on a destroyed mutex`).
- The log thread uses `read()` instead of `fgets`: `fgets` holds the file lock all the while it waits, so the `fflush(NULL)` at exit would wait forever, which shows as a hang on a black screen.
- After a crash or a kill, the game finds `save00/world/.autosave` (written about every 3 minutes) and asks whether to restore it; if you choose to restore, it starts `noita.exe -no_logo_splashes -gamemode 0 ...` with `CreateProcessW` and then exits. `kernel32.c` passes this one call to `NoitaActivity.relaunch`: it records the arguments, and `RestartActivity` restarts the app.
- The pause menu's "Save and quit" closes the game right away and goes back to the home screen, not to the main menu. So the deadlock above could be hit on every save and quit.
- Settings that must take effect before the process ends have to use `commit()`: `apply()` writes asynchronously, and the write is lost when the process is killed right after (the "Set up sound" button once didn't work because of this).

Gamepad: by default SDL registers Android's accelerometer as a joystick, and the game uses any joystick as a gamepad, so the menu cursor followed the device's tilt. `runtime/android_main.c` therefore turns off `SDL_HINT_ACCELEROMETER_AS_JOYSTICK`. To debug gamepad problems, put a `debug_input` file in `/sdcard/noitadroid/`, and the log records each joystick and the axes that are off center; writing `accelerometer` in the file brings back the accelerometer joystick, for comparison.

The APK you build contains code converted from the game: install it only on your own devices, and don't distribute it.

## Building on the device (in progress)

The goal is the same as the Bloodborne port's (Bloodborne-Android): publish an APK that **contains no game code**. Users copy their own game to the phone, and the app translates and compiles the game on the phone into `files/game/libmain.so`. The translated code belongs to the game's copyright holder, and stays only on the user's own device.

Steps:
1. **Toolchain** (done): `tools/android/build_toolchain.py`, output in `.local-deps/toolchain`. None of it comes from the game.
   - `libnd_llvm.so`: clang, lld and llvm-nm 21.1.8 combined into one static multi-call program, for arm64 and x86 targets, 95 MB.
   - `libnd_ld.so`: a small program that lets clang's driver call lld.
   - `libnd_python.so` and `libpython3.14.so`: python.org's Android build of CPython 3.14.
   - `libcapstone.so`: x86 only.
   - `libnd_innoextract.so`: statically linked innoextract (the mainline version after 1.9, up to 2025-02), so users can choose GOG's offline installer directly and the app unpacks it on the phone. Its dependencies, Boost 1.92, liblzma and bzip2, are all linked in statically.
   - assets: clang's headers, the NDK's sysroot (API 29), a trimmed Python standard library, capstone's Python bindings, pefile, and D8 converted to dex.
2. **The build flow on the device** (done):
   - `tools/android/device_build.py` runs on the phone: check version → discover → generate SDL/GL bridges → regsum → translate in blocks → compile → link. Each step keeps what it has finished, so after an interruption, running it again continues from where it stopped. Progress goes to the app as `ND-PROGRESS`, `ND-DONE` and `ND-ERROR` lines.
   - `tools/target.py` uses the app's own toolchain when `NOITADROID_TOOLCHAIN` is set.
   - `tools/android/build_app.py` packages the public APK (`build/android-app/noitadroid.apk`, Gradle with `-Pnoitadroid.public`). It contains only SDL2, the toolchain, `kit.zip` (the build tools, runtime, headers, and prebuilt LuaJIT and gl4es) and `toolchain.zip`, with no game code and no FMOD.
   - App side: `BuildService` (foreground service and wake lock, with the progress in the notification) and `NativeBuild` (paths, unpacking, whether the library is usable, progress state). `GameCheck` looks for the GOG installer by itself in "Download", the storage root and the memory card; on the first screen you can "Install from it" (unpack plus build) or "Build on this device".
   - Tested from scratch on the Pocket S on 2026-11-03: the old app uninstalled, and only the installer put in "Download". The whole process took about 13.5 minutes: unpacking under 1 minute, building 785 seconds. After that the game started, with no sound since there was no FMOD.
3. **Interface** (mostly done):
   - **Import FMOD** (`FmodImport`, `AppFactory`): the app looks for `fmodstudioapi*android.tar.gz` in "Download", or you can choose it by hand. It takes the two arm64 libraries and `fmod.jar` out of it, checks that the version is 2.01.x, converts `fmod.jar` to dex with the bundled D8, puts it in `files/fmod`, and restarts the app. `AppComponentFactory` adds this dex to the app's class loader at process start, so `libfmod.so`'s `JNI_OnLoad` can find `org.fmod.*`. The dex must be set read-only (an Android 14 requirement).
   - **Choose file**: a file picker that tells by the file name whether it's a GOG installer (unpack plus build), `noita.exe` (its folder becomes the game folder, the `--game` argument, which raised the ABI to 2) or an FMOD archive (import).
   - **Unpack location**: when internal storage hasn't enough space and the installer is on the memory card, it unpacks to the card's `noitadroid/Noita`. After unpacking you can "Delete installer".
   - **Restart**: RestartActivity now uses `FLAG_ACTIVITY_CLEAR_TASK`. Otherwise, when the new Intent is the same as the old task's root Intent, the system only brings that old task, which has no Activity left, to the front, and starts nothing.
   - Tested on the Pocket S on 2026-11-03:
     - Importing FMOD, 2.01.23, with sound (AAudio);
     - Installing an update over the app kills a build in progress, and building again continues from where it stopped;
     - "Stop" ends all build processes, and building again continues from where it stopped (385 seconds).
   - Not tested yet: choosing `noita.exe` or the installer in the system file picker, and deleting the installer. Importing a library built on a PC isn't done yet either.
4. **Release**: switch to an icon of our own, and publish the APK on GitHub Releases.

Measured on the Pocket S (2026-11-03, from scratch, without any cache):

| Step | Time | Memory | Notes |
|---|---|---|---|
| Unpack the GOG installer (1.5 GB) on the TF card | 162 s | 8 MB | 822 files, byte-identical to unpacking on the PC |
| Translate (discover, memorder, regsum, lift) | 429 s | 5.7 GB peak in one process | 6 forked workers; output byte-identical to the PC's (258 files) |
| Compile (290 tasks, 8 in parallel) | 267 s | at most 355 MB per task | CPU up to 93 °C |
| Link (no LTO) | 0.6 s | 217 MB | 49 MB `libmain.so` |

Since 2026-11-06 the phone build also uses ThinLTO and PGO (see the "Performance" section). The total time went from 768 seconds to 956 seconds (about 16 minutes): compiling about 190 seconds, linking about 290 seconds (8 threads). CPU up to 94.7 °C. The link's memory hasn't been measured on the phone on its own; on the PC with 12 threads the peak is 1.6 GB. The resulting `libmain.so` is about 78 MB.

`NoitaActivity` loads `files/game/libmain.so` when it exists, instead of the APK's own `libmain.so`. This way an APK without `libmain.so` gets into the game, with sound.

Things to watch out for:
- **Multiprocessing**: Android's libc has no `sem_open`, so Python's `multiprocessing` can't be used. The translation driver now initializes in the parent process first, then forks the workers itself; the workers share the parent's data copy-on-write, and only send results back through pipes.
- **FMOD**: `libfmod.so`'s `JNI_OnLoad` looks for classes such as `org.fmod.AudioDevice`, and without `fmod.jar` the app crashes. The planned approach:
  - Convert the user's own `fmod.jar` to dex with D8 on the phone (1 second in a test);
  - Use `AppComponentFactory` to make the app's class loader include this dex; this part hasn't been verified yet.
- **Memory**: translation peaks at 5.7 GB in one process, which may not be enough on a phone with 8 GB of RAM; it needs optimizing.
- **adb**: `adb exec-in` may return before the tar on the device has finished writing, so after a push, check the files before using them.

## Performance (measured on the Pocket S, 2026-11-03)

Tool: `tools/android/perfbench.sh` runs upstream perfbench's scenes on the device (fixed seed, 1800 frames measured):
- **heavy**: liquids, physics objects and explosions; the load is on the simulation threads (job workers).
- **jungle**: lots of enemies; the load is on the main thread.

It needs a debuggable build of the app: `NoitaActivity` takes the game arguments and environment variables from the intent. `install` backs up save00 first, and `remove` puts it back.

Baseline (system placement):
- heavy 17.8 fps, 53 ms of CPU work per frame (about 16 ms on an M1 Max).
- jungle 26.3 fps, 36 ms per frame.
- The GPU isn't the bottleneck: the swap takes only 0.5 ms. The game's internal resolution is 1280×720 anyway, so changing the screen resolution doesn't help.

Findings:
- The Snapdragon G3x Gen 2 has 1 prime core (cpu7, capacity 1024, 3.36 GHz), 4 big cores (cpu3–6, 811) and 3 little cores (cpu0–2, 266). The game starts simulation threads by core count (7), and each simulation round waits for the slowest thread.
- **core control**: Qualcomm pauses the prime core when its load is below 30% (`/sys/devices/system/cpu/cpu7/core_ctl`, `min_cpus=0`), and wakes it again above 60%.
  - With system placement, the main thread rarely runs on the prime core: during jungle the prime core averages only 1.1–1.5 GHz.
- **Strict pinning**:
  - Pinning to a paused core alone is refused by the kernel (`EINVAL`), and an existing pinning is even rewritten to the other cores.
  - So the main thread is strictly pinned to the prime core; while that core is paused, the thread falls back to the big cores, and a keeper thread (once a second) pins it back.
  - If both the prime core and the big cores are allowed, the scheduler doesn't move the main thread to the prime core (the prime core averages only 0.6–0.9 GHz).
- **Keeper thread**: threads inherit their creator's pinning, and Android also resets pinning when the app moves between foreground and background. So `runtime/sched.c` checks all the threads in the process once a second.
- **AYANEO's performance mode**: during the tests it capped the big cores at 2.19 GHz (max 2.80) and the prime core at 2.48 GHz (max 3.36). This stays the same when the device cools down, so it isn't thermal throttling. One step up in performance mode may help more than any of the items below.
- **ADPF frame time hints** (`APerformanceHint`, Android 13+): the session can be set up, but the results for both scenes were within noise, so it's off by default; `NOITADROID_HINT=1` turns it on. Other devices' power HALs may make use of it.
- **30 fps cap**: Noita advances the simulation once per frame, so a 30 fps cap means running at half speed; it can't be used as in the Bloodborne port.

Results (`NOITADROID_CORES`, 1–2 rounds each, in rotating order):

| Mode | jungle fps | heavy fps | Notes |
|---|---|---|---|
| system | 26.3 | 17.8 | Android's placement |
| big | – | 15.5 | No little cores: only 4 simulation threads left |
| prime | 31.8 | 16.2 | Main thread has the prime core to itself, simulation threads only on the big cores |
| **split** | **31.0 (+16%)** | **19.2 (+8%)** | Main thread has the prime core to itself, simulation threads on the other 7 cores |
| **fast** | **33.5 (+25%)** | **20.3 (+14%)** | split, with the main thread spinning while it waits (`MINA4MAC_YIELD=spin`); average temperature about 4 °C higher |

Conclusions:
- The in-game menu's "Performance settings" offers three modes: system placement, split ("Prime core for the main thread") and boost ("Prime core does both", the default, see the next section).
- A switch applies at once; the number of simulation threads changes at the next launch.
- The menu offers split and boost only on devices that have a single fastest core (three or more kinds of cores).
- fast, big and prime are kept only as environment variables.
- The raw records are in `build/perfbench-android/` (not committed).

### Further optimization (2026-11-05/06, same performance mode)

All measured on the Pocket S. Within each comparison the builds were run alternately, because the temperature drifts. At least 2 rounds each of heavy and jungle.

| Cumulative change | heavy fps | jungle fps | coal fps |
|---|---|---|---|
| split, ThinLTO on the PC (starting point) | 22 | 34 | 57 |
| boost | 25 | 37 | 59 |
| Memory prefetch | 30.6 | 39 | 59 |
| PGO | 33.2 | 41.5 | – |
| **Phone build (what users of the public build get)** | **33.2** | **43.4** | **59.3** |

The phone build without LTO and without PGO (as in 0.2.x, same code): heavy 25, jungle 36.

coal is the second level (`$biome_excavationsite`) with no fighting. It's close to 60 fps, so stutter in real fights isn't caught by this set of scenes.

Bottlenecks (heavy, measured with simpleperf and PMU counters):
- About 90% of the cycles go to the game threads' code; very few to gl4es and the driver.
- Worker threads: IPC 1.70, 41% backend stalls, and L1D misses mostly go out to DRAM. The L2 TLB is only 0.35 MPKI, so the TLB isn't the problem. 47% of instructions access memory.
- Main thread: IPC 1.14, 28% frontend stalls, L1I 16.7 MPKI. In jungle the main thread's hot spots are spread out (the hottest function is only 2.3%).

The changes:
- **boost** (`runtime/sched.c`): one more simulation thread, on the main thread's prime core, at nice 10. When the main thread waits, it can use that core.
- **Memory prefetch** (`tools/prefetch.py`): the chunk table is a 512×512 grid, and each cell points to an object on the heap; walking it, reading the objects' vtables misses all the way to DRAM. `lift.py` inserts prefetches by guest address in 5 hot loops. Prefetches don't change any semantics, and only read mapped guest memory (the heap has no holes, and guard pages are only near thread stacks).
- **ThinLTO**: on for the phone build too. About 20% (heavy) and 10% (jungle) faster than without LTO, and the whole build takes only about 3 minutes longer: compiling to bitcode is faster than generating machine code, which makes up for part of the link time.
- **PGO**: the profile is `tools/noitadroid.profdata` (2.4 MB, only function names and counts, no game code), and it goes to the phone with the kit. After changing the translator or the runtime, retrain it with `tools/android/pgo_train.sh`; functions whose code changed just have no profile to use, and nothing breaks. Source files must be compiled with relative paths, because the profile tells static functions apart by their path.

Tried but not kept:
- **Transparent huge pages**: no gain. Memory is too fragmented, and huge page allocations mostly fail (`thp_fault_fallback` 6666 times, 2042 successes); the TLB isn't the bottleneck anyway.
- **Timer slack** (`PR_SET_TIMERSLACK`): `usleep(20)` actually sleeps 78 µs; making the slack smaller gave about +2%, within noise.
- **Putting the hot functions into one compilation unit**: about +6% in heavy without LTO; with ThinLTO, no clear gain (heavy +2.5%, jungle −3%).
- **Upstream's wide addressing**: the instruction count didn't go down.

### OPPO Find X9 Ultra (2026-11-05)

Snapdragon 8 Elite Gen 5 (SM8850, 2×4.61 GHz + 6×3.63 GHz), Android 16, 2376×1080 screen. The game is a debug build, and core placement is "Android decides", because this chip has only two kinds of cores, so the settings page has no other choices. The phone was charging, with the touch controls on.

| Scene | fps | Work per frame | Main thread CPU | Pocket S, same settings |
|---|---|---|---|---|
| jungle | 40.3 | 23.2 ms | 17.8 ms | 26.3 fps |
| heavy | 30.3 | 30.6 ms | 15.0 ms | 17.8 fps |

Each scene was run only once. Temperatures and clocks weren't recorded (`perfbench.sh`'s sampling reads no data on this device), and giving the main thread the prime core to itself wasn't tried.

## Device tests (no game files needed)

Both test sets are built in the project folder on the 4TB drive; `adb` is in `/opt/android-sdk/platform-tools/`.

```sh
# 1. runtime tests run in adb shell: guest memory, calling conventions, LuaJIT's arm64 JIT, the mod sandbox
tools/android/build_tests.sh
adb push build/android/hosttest build/android/luatest /data/local/tmp/
adb shell 'cd /data/local/tmp && ./hosttest | tail -1; ./luatest | tail -1'

# 2. smoke test in the app process (SELinux's app domain, ART's address space): guest memory, LuaJIT, SDL2's GLES3 context,
#    gl4es's desktop GL 2.1 (immediate mode, GLSL 1.20 built-in variables, FBO, glMapBuffer), GLES3 sync objects
tools/android/build_smoke.sh
adb install -r build/android/noitadroid-smoke.apk
#    open "Noita (noitadroid)": each check is a color band, green for passed and red for failed; tap the screen to exit
adb shell cat /sdcard/Android/data/io.github.zhouwuqi.noitadroid/files/smoke.txt
```

gl4es collects commands into batches before it submits them, so a `glFlush` has to come before a GLES3 fence (`smoke.c` does this; the GL bridge has to as well).

## What you need to provide

1. The offline installer of the GOG version of Noita, `setup_noita_20250125-1640_(79502).exe`, and the `.bin` files next to it, or an already unpacked game folder.
2. FMOD Engine **2.01.x** (2.01.23 is tested) for Android; for stage 1, the Linux version as well. You have to register on fmod.com and download it yourself; the license doesn't allow redistribution.
3. Testing on the Pocket S (the dev machine only has the x86 emulator).

## Known risks

- **GL translation**: Noita's shaders use GLSL 1.10/1.20 and fixed-function built-in variables. Whether gl4es translates all of them correctly won't be known until stage 1 gets the list of shaders. If it can't, the fallback is Mesa Zink + Turnip (full desktop GL), but Turnip's support for the A32 also needs checking.
- **Performance**: for measurements, see the "Performance" section above: on the Pocket S, 33 fps in the heavy load scene and 43 fps in the scene with many enemies. When Noita can't keep up, it slows down rather than skipping frames.
- **Version lock**: the runtime is written against this one version's addresses and data layout, so when Noita updates, we have to wait for upstream to catch up.
- **Missing features**: mods with native DLLs and online features don't work (upstream made networking an offline stub); pure Lua mods do.
