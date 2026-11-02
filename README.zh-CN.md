# noitadroid

**在 Android 上原生运行 Noita。** noitadroid 把 Windows 版 Noita 静态重编译成 ARM64，在 Android 掌机和手机上运行，不经过模拟器，也不用 Wine 或 Proton。它基于 [mina4mac](https://github.com/paulstraw/mina4mac)，后者为 Apple Silicon 的 Mac 做了同样的事。

[English](README.md)

在 AYANEO Pocket S（骁龙 G3x Gen 2，Android 13）上已经能玩：
- 1920×1080 全屏，前期洞穴 50–60 fps。
- 有完整的声音（FMOD），内置手柄和触屏都能用。
- 游戏内有菜单：存档导出/导入、手柄按键互换、FPS 和温度显示。

其他设备暂未测试，欢迎反馈。

> 这个仓库不含任何游戏代码和资源，所以没有现成的 APK 可下载。你需要用**自己在 GOG 上买的 Noita** 自己构建。构建出的应用里有由你的游戏转换而来的代码，还有 FMOD 的库：只装在自己的设备上，**不要分享 APK**。

## 原理

- **游戏本身：** mina4mac 的重编译器把 32 位 x86 的 `noita.exe`（以及 `msvcp120.dll`）转换成 C，再由 NDK 编译成 ARM64。
- **运行时：** 它替代游戏用到的 Windows DLL，包括 kernel32 和 C 运行库，并桥接到原生的 SDL2、LuaJIT（含 ARM64 JIT）和 FMOD。
- **图形：** 游戏用的桌面 OpenGL 2.1 由 [gl4es](https://github.com/ptitSeb/gl4es) 转到 GLES 3.0 上运行。
- **Android 应用：** 游戏跑在 SDL2 的 Activity 里，外面还有：
  - 首次启动时的检查屏，告诉你还缺什么；
  - 从左边缘滑出的游戏内菜单；
  - 所有文件都放在共享存储里，游戏和存档可以直接通过 USB 拷贝。

## 需要准备

- **GOG 上的 Windows 版 Noita**，版本 `20250125-1640`（2025 年 1 月 25 日）。用离线安装包 `setup_noita_20250125-1640_(79502).exe` 和它旁边的 `.bin` 文件。Steam 版不行，因为重编译出来的代码只对应这一个版本。
- **一台 Android 设备：**
  - arm64，Android 10 或更新，支持 OpenGL ES 3.0；
  - 约 2 GB 空闲空间；
  - 推荐用手柄。
- **一台用来构建的 Linux x86_64 电脑。** 以下步骤在 Ubuntu 24.04 上验证过，12 核大约 15 分钟构建完。
- **可选，用于声音：** Android 版 FMOD Engine **2.01.x**。要在 [fmod.com](https://www.fmod.com/download) 注册免费账号后自己下载，因为它的许可证不允许分享。没有它游戏也能玩，只是没有声音。

## 构建

### 1. 工具

```sh
sudo apt install git clang lld cmake ninja-build make innoextract openjdk-17-jdk libsdl2-dev libgles-dev libegl-dev
curl -LsSf https://astral.sh/uv/install.sh | sh        # 构建脚本用 uv 运行
```

用 Android Studio 或命令行工具安装 Android SDK，需要这些组件：

```sh
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0" "ndk;29.0.14206865"
```

### 2. 获取仓库

```sh
git clone --recursive https://github.com/zhouwuqi/noitadroid
cd noitadroid
mkdir -p .local-deps
ln -s "$ANDROID_HOME/ndk/29.0.14206865" .local-deps/ndk
echo "sdk.dir=$ANDROID_HOME" > android/local.properties
tools/android/deps.sh                                    # 把 gl4es 和 SDL2 构建到 .local-deps
```

### 3. 游戏文件

```sh
innoextract --gog -d .local-deps/noita-gog "setup_noita_20250125-1640_(79502).exe"
export NOITA_DIR=$PWD/.local-deps/noita-gog
```

### 4. 声音（可选，推荐）

在 fmod.com/download 下载 **FMOD Engine 2.01.23 的 Android 版**（`fmodstudioapi20123android.tar.gz`）。2.01.23 是旧版本，要在版本列表里选。然后运行：

```sh
tools/android/setup_fmod.sh path/to/fmodstudioapi20123android.tar.gz
```

不带参数时，脚本会去 `~/Downloads` 里找；找不到就在终端里询问你的 fmod.com 账号，自己下载。不是 2.01.x 的版本会被拒绝。

### 5. 构建应用

```sh
for m in noita msvcp120; do uv run tools/discover.py $m; done   # 只需一次
uv run tools/build_all.py                 # 把游戏重编译成 C（同时构建 Linux 版）
uv run tools/android/build_game.py        # 生成 APK：build/android/noitadroid.apk
```

### 6. 安装并拷贝游戏

```sh
adb install -r build/android/noitadroid.apk
```

在设备上打开 **Noita (noitadroid)**。首屏会列出还缺什么，以及每一项该怎么做：
1. **所有文件访问权限。** 应用从 `/sdcard/noitadroid` 读取游戏，存档也放在那里。
2. **游戏文件。** 把你的 Noita 文件夹（有 `noita.exe` 的那个）里的所有内容拷到「内部存储/noitadroid/Noita」。可以用 USB 拷，也可以用 adb 运行 `tools/android/push_game.sh`，约 1.5 GB。

全部就绪后，打开应用会直接进入游戏。

## 游玩

- **操作：** 手柄和触屏的用法与电脑版相同，触屏相当于鼠标。
- **游戏内菜单：** 从屏幕左边缘的中段往右滑。左边缘的其余部分留给系统手势导航的「返回」。菜单打开时游戏会暂停。菜单里有：
  - **存档：** 导出成 zip，目录结构和电脑版的 `Nolla_Games_Noita` 文件夹一致；也可以从这样的 zip 导入，Android 和电脑版的都行。当前存档会备份为 `save00.backup-日期`，然后游戏重启。
  - **手柄按键：** 为游戏互换按键，比如 A 和 B。游戏自带的设置里也能改各个操作的键位。
  - **FPS 和温度：** 在屏幕顶部显示一个小信息条。
  - **语言：** 应用自己的界面默认是英文，可以在这里或首屏切换成中文。游戏本身的语言在游戏设置里改。
- **设备上的文件：**
  - `/sdcard/noitadroid/Noita/`：游戏。
  - `/sdcard/noitadroid/save/AppData/LocalLow/Nolla_Games_Noita/`：存档，卸载应用后也会保留。
  - `/sdcard/noitadroid/noitadroid.log`：日志，也可以用 `adb logcat -s noitadroid` 查看。

## 常见问题

- **首屏说版本不对：** 你的 `noita.exe` 不是 GOG 的 20250125-1640 版，或者不是构建这个应用时用的那一份。
- **没有声音：** 应用构建时没加入 FMOD（首屏会提示），或者用的不是 2.01.x。
- **手柄有问题：** 在 `/sdcard/noitadroid/` 下放一个名为 `debug_input` 的空文件，日志里就会列出手柄和它们的轴。
- 其他问题：看 `/sdcard/noitadroid/noitadroid.log`，然后提 issue。

## 开发

- [docs/ANDROID.zh.md](docs/ANDROID.zh.md)：移植的开发笔记，包括设计、各个阶段和设备测试。
- [docs/README.mina4mac.md](docs/README.mina4mac.md)：mina4mac 原来的 README，介绍重编译器和 macOS 版的构建。
- 运行时的测试也能在设备上跑：先运行 `tools/android/build_tests.sh`，再运行 `tools/android/device_check.sh`。

## 致谢与许可

- **mina4mac**：Paul Straw 编写，本移植所基于的重编译器和运行时，MIT 许可。
- **随附的代码**沿用各自的许可：[LuaJIT](https://luajit.org)（MIT）、[gl4es](https://github.com/ptitSeb/gl4es)（MIT）、[SDL2](https://libsdl.org)（zlib）。
- **FMOD** 遵循 FMOD 自己的许可，需要你自己下载。
- **noitadroid 自己的代码**采用 MIT 许可，见 [LICENSE](LICENSE)。

Noita © Nolla Games。本项目与 Nolla Games、GOG、Firelight Technologies 均无关联，也未获得它们的认可。
