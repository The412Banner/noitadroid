# noitadroid

**Noita on Android, natively.** noitadroid runs the Windows game, statically recompiled to ARM64, on Android
handhelds and phones. It has no emulator and no Wine or Proton. It's built on
[mina4mac](https://github.com/paulstraw/mina4mac), which does the same for Apple Silicon Macs.

[中文说明](README.zh-CN.md)

It plays on an AYANEO Pocket S (Snapdragon G3x Gen 2, Android 13):
- Fullscreen 1920×1080, with 50–60 fps in the early caves.
- Full sound (FMOD), and the built-in gamepad and the touchscreen both work.
- An in-game menu with save export/import, gamepad button swaps, and an FPS and temperature display.

Other devices are untested so far. Reports are welcome.

> This repository contains no game code or assets, so there's no APK to download. You build the app yourself, from
> **your own copy of Noita from GOG**. The app you build contains code derived from your game and FMOD's libraries:
> keep it on your own devices, and **don't share the APK**.

## How it works

- **The game.** mina4mac's recompiler turns the 32-bit x86 `noita.exe` (and `msvcp120.dll`) into C.
  The NDK compiles that C to ARM64.
- **The runtime.** It stands in for the Windows DLLs the game uses: kernel32 and the C runtime, plus bridges to
  native SDL2, LuaJIT (with its ARM64 JIT) and FMOD.
- **Graphics.** The game's desktop OpenGL 2.1 runs on [gl4es](https://github.com/ptitSeb/gl4es) over GLES 3.0.
- **The Android app.** SDL2's activity runs the game. Around it:
  - a first-start screen checks what's missing;
  - a menu slides in from the left edge;
  - the files live in shared storage, so you can copy the game and your saves over USB.

## What you need

- **Noita for Windows from GOG**, build `20250125-1640` (Jan 25 2025). Use the offline installer
  `setup_noita_20250125-1640_(79502).exe` and its `.bin` files. The Steam version won't work: the recompiled code
  is for this one build.
- **An Android device:**
  - arm64, Android 10 or later, with OpenGL ES 3.0;
  - about 2 GB free;
  - a gamepad is recommended.
- **A Linux x86_64 PC to build on.** These steps were tested on Ubuntu 24.04. A build takes about 15 minutes on
  12 cores.
- **Optional, for sound:** FMOD Engine **2.01.x** for Android. You download it yourself from
  [fmod.com](https://www.fmod.com/download) (a free account), because its license doesn't allow sharing it.
  Without it, the game runs silently.

## Build

### 1. Tools

```sh
sudo apt install git clang lld cmake ninja-build make innoextract openjdk-17-jdk libsdl2-dev libgles-dev libegl-dev
curl -LsSf https://astral.sh/uv/install.sh | sh        # uv runs the build scripts
```

Install the Android SDK, from Android Studio or the command-line tools, with these packages:

```sh
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0" "ndk;29.0.14206865"
```

### 2. The repository

```sh
git clone --recursive https://github.com/zhouwuqi/noitadroid
cd noitadroid
mkdir -p .local-deps
ln -s "$ANDROID_HOME/ndk/29.0.14206865" .local-deps/ndk
echo "sdk.dir=$ANDROID_HOME" > android/local.properties
tools/android/deps.sh                                    # builds gl4es and SDL2 into .local-deps
```

### 3. The game

```sh
innoextract --gog -d .local-deps/noita-gog "setup_noita_20250125-1640_(79502).exe"
export NOITA_DIR=$PWD/.local-deps/noita-gog
```

### 4. Sound (optional, recommended)

Download **FMOD Engine 2.01.23 for Android** (`fmodstudioapi20123android.tar.gz`) from fmod.com/download. Pick
2.01.23 in the version list; it's an older version. Then run:

```sh
tools/android/setup_fmod.sh path/to/fmodstudioapi20123android.tar.gz
```

Without an argument, the script looks in `~/Downloads`. If there's nothing there, it asks for your fmod.com account
and downloads the file itself. It refuses any version other than 2.01.x.

### 5. Build the app

```sh
for m in noita msvcp120; do uv run tools/discover.py $m; done   # once
uv run tools/build_all.py                 # recompiles the game to C (and builds the Linux version)
uv run tools/android/build_game.py        # the APK: build/android/noitadroid.apk
```

### 6. Install and copy the game

```sh
adb install -r build/android/noitadroid.apk
```

Open **Noita (noitadroid)** on the device. Its first screen shows what's still missing and what to do about each
item:
1. **All files access.** The app reads the game from, and keeps saves in, `/sdcard/noitadroid`.
2. **The game files.** Copy everything in your Noita folder (the one with `noita.exe`) into
   `Internal storage/noitadroid/Noita`, over USB, or run `tools/android/push_game.sh` with adb. That's about
   1.5 GB.

Once everything is there, the app goes straight into the game.

## Playing

- **Controls.** The gamepad and touch work as in the PC game: touch acts as the mouse.
- **The in-game menu.** Swipe right from the middle of the screen's left edge. Gesture navigation's back swipe
  keeps the rest of that edge. The game pauses while the menu is open. The menu has:
  - **Saves.** Export to a zip laid out like the Windows game's `Nolla_Games_Noita` folder. Import from such a zip,
    from Android or Windows; the current save is kept as `save00.backup-<date>`, and the game restarts.
  - **Gamepad buttons.** Swap buttons (say A and B) for the game. The game's own settings can also rebind its
    actions.
  - **FPS and temperatures.** A small display at the top of the screen.
- **Files** on the device:
  - `/sdcard/noitadroid/Noita/`: the game.
  - `/sdcard/noitadroid/save/AppData/LocalLow/Nolla_Games_Noita/`: the saves. Uninstalling keeps them.
  - `/sdcard/noitadroid/noitadroid.log`: the log, also in `adb logcat -s noitadroid`.

## Troubleshooting

- **The first screen says the version is wrong.** Your `noita.exe` isn't GOG build 20250125-1640, or it isn't the
  build the app was made from.
- **No sound.** The app was built without FMOD (its first screen says so), or with a version other than 2.01.x.
- **Gamepad trouble.** Put an empty file named `debug_input` in `/sdcard/noitadroid/`. The log then lists the
  gamepads and their axes.
- Anything else: look at `/sdcard/noitadroid/noitadroid.log` and open an issue.

## Development

- [docs/ANDROID.zh.md](docs/ANDROID.zh.md) has the port's notes: its design, its phases, and device testing (in
  Chinese).
- [docs/README.mina4mac.md](docs/README.mina4mac.md) is mina4mac's README, for the recompiler and the macOS build.
- The runtime's tests run on the device too: `tools/android/build_tests.sh`, then
  `tools/android/device_check.sh`.

## Credits and license

- **mina4mac** by Paul Straw, the recompiler and runtime this port is built on. MIT.
- **Bundled code** keeps its own license: [LuaJIT](https://luajit.org) (MIT), [gl4es](https://github.com/ptitSeb/gl4es)
  (MIT) and [SDL2](https://libsdl.org) (zlib).
- **FMOD** is under FMOD's own license, and you download it yourself.
- **noitadroid's own code** is MIT; see [LICENSE](LICENSE).

Noita is © Nolla Games. This project isn't affiliated with or endorsed by Nolla Games, GOG or Firelight
Technologies.
