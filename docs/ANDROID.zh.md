# noitadroid：Noita 原生 Android 移植

基于 [mina4mac](https://github.com/paulstraw/mina4mac)（远程 `upstream`，本仓库的工作分支是 `android`）。mina4mac 把 32 位 x86 的 `noita.exe` 静态重编译成 C，再用自己的运行时替代 Windows、MSVC CRT、SDL2、OpenGL、LuaJIT 和 FMOD。本项目在此基础上加上 Linux 和 Android 宿主，目标是在 AYANEO Pocket S（骁龙 G3x Gen 2 / Adreno A32）上原生运行 Noita，不经过 Wine，也不经过 x86 模拟。

## 规则（沿用上游）

- 自带游戏文件，只供个人使用。**不提交**任何游戏文件、`data.wak`、生成的 C 代码或 FMOD 的库文件。`build/` 和 `.local-deps/` 都已加入 gitignore。
- 生成的 APK 里包含由游戏转换而来的代码，所以只能在本机构建、装在自己的设备上，不能分发。
- 只支持 GOG 版 `20250125-1640`（`setup_noita_20250125-1640_(79502).exe`），Steam 版是另一个二进制。
- 尽量不改上游文件，平台差异都放在新文件里，方便合并上游更新。

## 架构（Android）

```
APK
├─ Java：SDLActivity（SDL2 官方的 Android 外壳）+ 启动配置
├─ libmain.so：重编译的 noita.exe（C）+ runtime/*.c（Windows/CRT HLE、各个桥）
│    ├─ LuaJIT 2.1（arm64，开 JIT，静态链接）
│    ├─ SDL2（Android 版）
│    ├─ gl4es：把桌面 GL 2.1 兼容模式转成 GLES；同步对象（glFenceSync 等）gl4es 没有，GL 桥直接调 GLES3
│    └─ FMOD Engine 2.01.x Android 版（用户自己下载，dlopen）
└─ 游戏数据：/sdcard/Android/data/<包名>/files/Noita（用 adb push，或首次启动时导入）
```

来宾内存：一块 4 GB 的匿名映射，放在固定宿主地址 `0x1000004000`。macOS 上用的 `0x200000004000` 超出了 Android 内核 39 位（512 GB）的用户地址空间（见 `runtime/compat/compat.h`）。

## 阶段

| 阶段 | 内容 | 需要游戏文件 | 状态 |
|---|---|---|---|
| 0 | 平台兼容层 `runtime/compat/` | 否 | 完成（2026-11-02） |
| 1 | Linux 宿主构建（x86_64，在开发机上）：`build_all.py` 按目标切换编译器和链接参数；`gen_gl`/`gen_sdl` 改为对着 Linux 头文件生成；用 Xvfb + Mesa 跑通，再做 GL 调用调查（`tools/glsurvey.py`） | 是 | **能玩**（2026-11-02）：重编译 100,129 个函数，主菜单和开新局都正常，gl4es 在 llvmpipe 上约 36 fps；待查：`GL_PIXEL_PACK_BUFFER` |
| 2 | Android 构建：NDK、SDL2 Android、gl4es（加 GLES3 同步对象）、FMOD Android、路径和已知文件夹、Gradle 打包 APK | 是 | **在 Pocket S 上能玩**（2026-11-02）：全屏 1920×1080，开局后 15 秒内进入世界，洞穴里 50–57 fps（60 Hz 屏），手柄和触屏都能用；缺 FMOD（静音） |
| 3 | Pocket S 调优：主线程和工作线程放到大核（X3 + A715）上（`sched.c`）、温控、帧率上限、手柄、分辨率 | 是 | 未开始 |

第 1 阶段是为了降低风险：开发机上就能把整个游戏跑起来调试，不依赖真机，并在那里先拿到 Noita 实际用到的 GL 调用和着色器清单，再决定 Android 上用 gl4es 还是 Zink。

## 图形路径：Linux 和 Android 都用 gl4es

游戏要的是桌面 OpenGL（2.1 兼容模式），而且它的 GL 加载器按 `GL_VERSION` 来决定用哪些函数。所以驱动报告的版本、扩展列表和桥接的函数这三者必须一致。

在 macOS 上，这三者由系统的 legacy 2.1 上下文和它的头文件保证。在 Linux 和 Android 上，游戏用的 GL 是 **gl4es**（在 GLES 上提供桌面 GL 2.1，报告的也是它自己的 2.1 和扩展列表）：

- `runtime/gles.c` 通过链接时的 `-Wl,--wrap` 接管 `SDL_Init`、`SDL_GL_SetAttribute` 和 `SDL_GL_CreateContext`。不管游戏要求哪种桌面 GL 上下文，实际创建的都是 GLES 3.0 上下文（X11 上走 EGL，不走 GLX），然后初始化 gl4es。
- `tools/gen_gl.py` 只桥接 gl4es 真正实现的函数（`target.gl_provided_names()`，取自 libGL.a 的符号表）：1,044 个名字里桥接了 616 个，其中 605 个自动生成、11 个手写。
- 另外手写了 3 个函数（`runtime/opengl32.c`，仅非 Apple 平台）：`glMapBufferRange` 和 `glFlushMappedBufferRange`（gl4es 宣称支持 `GL_ARB_map_buffer_range`；和 `glMapBuffer` 一样在来宾堆里放一份影子拷贝），以及 `glGetStringi`。
- Linux 和 Android 的 gl4es 是同一个提交编出来的，导出的 GL 函数完全相同（1,244 个）。所以桌面开发版跑的就是 Android 的图形路径。

在开发机上，`tools/run_linux.sh` 会起一个不带 GLX 的 Xvfb，并强制使用 Mesa 的 EGL（llvmpipe，GLES 3.2）。NVIDIA 的 EGL 需要真正的 NVIDIA X 屏幕，在 Xvfb 上会崩。

## 第 0 阶段做了什么

`runtime/compat/compat.h` 由构建强制引入（`-include`），并把 `runtime/compat/` 放在 include 路径最前面，用来顶替 Darwin 的头文件：

| Darwin | 替代 |
|---|---|
| `os/lock.h`（`os_unfair_lock`） | pthread mutex |
| `sys/qos.h`（QoS 类） | 空实现；Android 上的核心分配在第 3 阶段做 |
| `sys/sysctl.h`（`sysctlbyname`） | 返回失败，调用方会退回默认值 |
| `mach-o/dyld.h`（`_NSGetExecutablePath`） | `/proc/self/exe` |
| `OpenGL/*.h`（CGL） | `GL/gl.h`；判断当前线程有没有 GL 上下文改用 `SDL_GL_GetCurrentContext` |
| `clock_gettime_nsec_np`、`CLOCK_UPTIME_RAW` | `clock_gettime(CLOCK_MONOTONIC)` |
| `st_birthtimespec` 等 BSD 时间字段 | `st_mtim`/`st_atim` |
| 只接受一个参数的 `pthread_setname_np` | Linux 的两参数版本，名字截到 15 字节 |
| `pthread_cpu_number_np` | `sched_getcpu` |
| `__builtin_arm_yield`（x86_64 宿主） | `__builtin_ia32_pause` |

对上游文件只有一处改动：`runtime/cpu.h` 的 `MEM_HOST_BASE` 允许由平台预先定义。

注意：在 Linux 和 Android 上必须用 `-iquote runtime`，不能用 `-I runtime`。glibc 的 `pthread.h` 会 `#include <sched.h>`，`-I` 会让它找到 `runtime/sched.h`。

验证（2026-11-02）：除了需要生成头文件的 `sdl2.c` 和 `sdl_test.c`，`runtime/*.c` 在宿主 gcc（x86_64-linux-gnu）和 NDK r29 clang 21（`aarch64-linux-android29`）上都能编译通过，打开了 `-Werror=implicit-function-declaration` 等严格检查。LuaJIT 2.1 用 NDK 交叉编译成 Android arm64 静态库也成功了。

```sh
NDK=${ANDROID_NDK_HOME:-.local-deps/ndk}  # NDK r29
COMPAT="-include runtime/compat/compat.h -I runtime/compat -iquote runtime -I third_party/luajit/src"
$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang --target=aarch64-linux-android29 -fsyntax-only $COMPAT runtime/heap.c
```

## Linux 开发版

```sh
export NOITA_DIR=$PWD/.local-deps/noita-gog          # innoextract --gog 解包出来的 GOG 安装目录
for m in noita msvcp120; do uv run tools/discover.py $m; done   # 只需一次
uv run tools/build_all.py                             # 12 核约 9 分钟：regsum 188 s，重编译 39 s，编译 80 s，ThinLTO 链接 208 s
MINA4MAC_FPS=1 tools/run_linux.sh                     # 存档在 ~/.local/share/noitadroid
MINA4MAC_CLICKS="15:639,350;20:445,250" tools/run_linux.sh --shot build/s.png 90   # 脚本点击开新局，90 秒时截图
```

第一次跑（2026-11-02，Xvfb + llvmpipe）：
- 会先弹出更新日志，点「Ok」（85,662）关掉；之后主菜单和开新局都正常。
- 生成世界时有一帧长约 65 秒。
- 进入游戏后 34–37 fps（1280×720）。
- gl4es 偶尔报 `unhandled Buffer type GL_PIXEL_PACK_BUFFER`：游戏的 PBO 读回 gl4es 不支持，需要查清楚游戏用它做什么。

`tools/gen_sdl.py`：SDL2 自带的头文件用匿名的 `typedef struct {…} SDL_DisplayMode;`，clang 的布局转储会写成 `(unnamed at …)`。所以要回到源码里取 typedef 的名字。macOS 上用的 sdl2-compat 头文件里这些结构体是有名字的。

## Android 游戏版

```sh
export NOITA_DIR=$PWD/.local-deps/noita-gog
uv run tools/build_all.py                    # 先做宿主构建：重编译出来的 C 两边通用
uv run tools/android/build_game.py           # 约 5 分钟：桥按 Android 重新生成，NDK 编译，ThinLTO 链接成 libmain.so，打 APK
adb install -r build/android/noitadroid.apk
tools/android/push_game.sh                   # 约 1.5 GB：noita.exe、msvcp120.dll、data/、mods/ 等
adb logcat -s noitadroid                     # 也会写到 /sdcard/noitadroid/noitadroid.log
```

设备上的目录都在 `/sdcard/noitadroid/` 下（共享存储，App 需要「所有文件访问权限」，`push_game.sh` 会用 `appops` 授予）：
- `Noita/`：游戏文件；
- `save/`：存档（在 `AppData/LocalLow/Nolla_Games_Noita` 里，卸载 App 也不会被删）；
- `noitadroid.log`：运行时日志。

为什么不放在 App 自己的目录（`Android/data/<包名>/files`）：在 Android 13 上，adb shell 拷进去的目录归 shell 所有，权限是 `drwxrws---`，App 读不了；`run-as` 又进不了 `/sdcard`。

`runtime/android_main.c` 做的事：
- 实现 SDL 调用的入口 `SDL_main`，再去调 `runtime/main.c`；后者编译时被改名为 `noitadroid_main`；
- 每次启动都把 `save_shared/config.xml` 改成和屏幕一样大的全屏，因为游戏默认是 1280×720 窗口模式，在 Android 上只会画在屏幕左下角；
- 同一进程里第二次进入 `SDL_main` 时直接退出进程：来宾内存和 gl4es 不能在一个进程里初始化两次，冒烟测试里出现过 FBO 不完整和地址被占用。

`libmain.so` 只导出 `SDL_main` 和 `NoitaActivity` 的两个 JNI 函数（帧计数、暂停）。`build_game.py` 是增量构建：只改了运行时的一个文件时约 1 分钟，其中重编 1 个文件、链接约 20 秒。

FMOD：运行 `tools/android/setup_fmod.sh`。它会先在 `~/Downloads` 和 `.local-deps` 里找 `fmodstudioapi201*android.tar.gz`；找不到就在终端里询问 fmod.com 账号密码，登录后下载 2.01.23 的 Android 包。账号密码只发给 fmod.com，不会保存。之后它会检查版本必须是 2.01.x，再解压到 `.local-deps/fmod/`，使 `api/core/lib/arm64-v8a/libfmod.so`、`api/studio/lib/arm64-v8a/libfmodstudio.so` 和 `api/core/lib/fmod.jar` 就位。然后 `build_game.py` 会把它们打进 APK。没有 FMOD 时游戏静音运行。FMOD 的库由 `NoitaActivity.getLibraries()` 在 Java 端加载，因为 libfmod.so 要在 `JNI_OnLoad` 里拿到 Java VM，才能向 `org.fmod.FMOD` 查询音频输出。之后 `runtime/fmod.c` 再 dlopen 同一份库，按句柄查找符号，因为 Android 的 `RTLD_DEFAULT` 会跳过 Java 加载的 RTLD_LOCAL 库。已在 Pocket S 上验证：2.01.23 正常工作，走 AAudio 低延迟 MMAP 输出。

首屏引导：启动器入口是 `SetupActivity`。它用 `GameCheck` 检查四项：所有文件访问权限、`/sdcard/noitadroid/Noita` 里的 noita.exe、msvcp120.dll 和 data/data.wak、游戏版本（比对哈希），以及 FMOD 是否在 APK 里。比对的标准是 `build_game.py` 在构建时写进 `assets/noitadroid.properties` 的值：构建者那份游戏里 noita.exe 和 msvcp120.dll 的 sha256、data.wak 的大小、FMOD 版本。必需项都满足时，它直接启动 `NoitaActivity`，不显示任何界面（第一次要算哈希，约 0.1 秒，之后按文件大小和修改时间缓存）。否则显示中英文的检查清单，每项都写明该怎么做，触屏和手柄都能操作，回到前台时会自动重查。拿到权限后它会自动建好 Noita 文件夹，方便通过 USB 拷贝。已在 Pocket S 上测过：一切就绪、没有权限、缺游戏文件、版本不对四种情况，以及手柄 A 键重查。

界面语言（`Lang`）：首屏和游戏内菜单默认英文，不跟随系统语言；两处都能切换成中文，存在 SharedPreferences 里。首屏用 `attachBaseContext` 换语言，菜单用自己的本地化 Context，所以切换后立刻生效，不用重启游戏。首屏只在刚打开或刚切换完语言时停留；之后每次回到前台，如果已经全部就绪就直接进游戏，比如授权回来、从桌面点图标回来。

游戏内菜单（`GameDrawer`）：在游戏中从屏幕左边缘往右滑，会拉出侧边栏。
- 系统手势导航也用这个边缘做「返回」，所以 `NoitaActivity` 把左边缘中间的 200 dp（Android 允许的最大值）排除在系统手势之外，在这段里滑才能打开。
- 侧边栏打开时游戏停住：`runtime/gles.c` 包装了 `SDL_GL_SwapWindow`，在帧末尾等待，同时用 FMOD 的 `System::mixerSuspend` 让声音停下。
- 应用切到后台时会先放开这个等待，交给 SDL 自己暂停；回到前台后先让游戏画 4 帧再停住，这样侧边栏后面看得到游戏画面，不是黑屏。
- 菜单内容：
  - 存档导出：用 SAF 存成 zip，结构和电脑版 `Nolla_Games_Noita` 一致，包含 save00 和 save_shared。
  - 存档导入：从 zip 里取出 save00 先放到暂存区，确认后由 `RestartActivity`（独立进程 `:restart`）重启游戏。新的 `NoitaActivity` 在游戏启动前换入存档，原来的存档改名为 `save00.backup-日期`。
  - 手柄按键互换：`PadMap`，始终是一一对应的置换，在按键到达 SDL 之前替换键码。
  - FPS/温度显示：`StatsOverlay`，帧数来自 swap 计数，温度读自应用有权限读取的 thermal zone。
- 触屏和手柄都能操作，B 键返回上一页。
- 已在 Pocket S 上验证：边缘滑动打开，打开时 0 FPS、关上后约 60 FPS，按键互换界面，导出（1.2 MB、131 个文件），导入后重启并备份，菜单打开时音频流停止。

手柄：SDL 默认把 Android 的加速度计注册成摇杆，而游戏会把任何摇杆都当手柄用，结果菜单光标会跟着机身倾斜跑。`runtime/android_main.c` 因此关掉了 `SDL_HINT_ACCELEROMETER_AS_JOYSTICK`。排查手柄问题时，在 `/sdcard/noitadroid/` 下放一个 `debug_input` 文件，日志里就会记录每个摇杆和偏离中心的轴；文件内容写 `accelerometer` 可以恢复加速度计摇杆，用来对比。

生成的 APK 里有由游戏转换而来的代码：只装在自己的设备上，不要分发。

## 设备测试（不需要游戏文件）

两组测试都在 4TB 盘上的工程目录里构建，`adb` 在 `/opt/android-sdk/platform-tools/`。

```sh
# 1. adb shell 里跑的运行时测试：来宾内存、调用约定、LuaJIT 的 arm64 JIT、mod 沙盒
tools/android/build_tests.sh
adb push build/android/hosttest build/android/luatest /data/local/tmp/
adb shell 'cd /data/local/tmp && ./hosttest | tail -1; ./luatest | tail -1'

# 2. App 进程里的冒烟测试（SELinux 的 App 域、ART 的地址空间）：来宾内存、LuaJIT、SDL2 的 GLES3 上下文、
#    gl4es 的桌面 GL 2.1（立即模式、GLSL 1.20 内置变量、FBO、glMapBuffer）、GLES3 同步对象
tools/android/build_smoke.sh
adb install -r build/android/noitadroid-smoke.apk
#    打开「Noita (noitadroid)」：每一项检查是一条色带，绿色通过、红色失败；点屏幕退出
adb shell cat /sdcard/Android/data/io.github.zhouwuqi.noitadroid/files/smoke.txt
```

gl4es 会把命令攒成批次再提交，所以在 GLES3 的 fence 之前要先 `glFlush`（`smoke.c` 里这样做了；GL 桥里也要这样做）。

## 需要你准备的

1. GOG 版 Noita 的离线安装包 `setup_noita_20250125-1640_(79502).exe`，以及旁边的 `.bin` 文件，或者已经解包的游戏目录。
2. FMOD Engine **2.01.x**（测试过的是 2.01.23）的 Android 版；第 1 阶段要的话再加 Linux 版。需要在 fmod.com 注册后自己下载，许可证不允许再分发。
3. 在 Pocket S 上测试（开发机只有 x86 模拟器）。

## 已知风险

- **GL 转换**：Noita 的着色器用 GLSL 1.10/1.20 和固定管线的内置变量，gl4es 能不能全部转换正确，要等第 1 阶段拿到着色器清单才知道。转换不了的话，备选方案是 Mesa Zink + Turnip（完整的桌面 GL），但 Turnip 对 A32 的支持也需要验证。
- **性能**：上游在 M1 Max 上重负载场景约 35–47 fps。G3x Gen 2 的多核算力大约是它的 40–50%，粗估重负载场景 15–25 fps、普通场景 30–50 fps（没有实测）。Noita 跟不上时会变慢，而不是跳帧。
- **版本锁定**：运行时是对着这一个版本的地址和数据布局写的，Noita 一更新就要等上游跟进。
- **功能缺失**：带原生 DLL 的 mod 和联网功能用不了（上游把网络做成了离线桩），纯 Lua 的 mod 可以。
