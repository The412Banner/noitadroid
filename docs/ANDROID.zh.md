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

触控按键（`TouchOverlay`、`TouchControls`、`TouchEditor`、`GameState`，全在 Java 里，不用重新构建游戏）：

- 对游戏来说它就是一个手柄：用 `SDLControllerManager.nativeAddJoystick` 加一个虚拟设备，SDL 按标准映射认成控制器。不用鼠标模拟瞄准，是因为游戏的镜头会带延迟地朝光标偏移，玩家在屏幕上的位置一直在变，「屏幕中心 + 摇杆方向」的瞄准角度最多偏十几度；手柄的右摇杆方向是精确的，准星也由游戏自己画。
- 左摇杆走摇杆轴，往上推时同时按左扳机（飞）；右摇杆走摇杆轴，推过内圈时按右扳机（开火）。按钮是手柄默认键位：B 踢、Y 投掷、A 互动、Back 背包、LB/RB 换物品、Start 暂停。游戏每帧轮询一次手柄状态，所以每次按键至少保持 100 ms，否则快速点按可能落在两帧之间。
- 按键之外的手指都当鼠标，无论触控按键是否显示。这里没用 SDL 自带的「触摸转鼠标」：它把移动和按下放在同一帧，而游戏的界面要看上一帧鼠标在什么上面，所以先移动，70 ms 后再按下。
- 什么时候显示，由 `GameState` 每 100 ms 读游戏自己的三个标志决定（经 `/proc/self/mem`，不改游戏代码；只支持一个游戏版本，地址是固定的）：`0x12056f0` 是主菜单和暂停菜单的对象指针，`0x1205798` 是游戏结束界面的对象指针，`0x1220630` 是 Lua 函数 `GameIsInventoryOpen` 的返回值。前两个非零时全部隐藏，第三个非零时只留背包键。读不到时按「游玩中」处理，按键不会消失。
- 布局存成屏幕比例，换分辨率也能用。模式默认「自动」：没有实体手柄时显示。
- 已在 OPPO Find X9 Ultra 上验证（多点触控用 `app_process` 里的 `InputManagerGlobal.injectInputEvent` 注入）：左手走、右手瞄、第三根手指踢同时进行，四个方向和斜向的瞄准，按键之外点屏幕开火，拖动物品，菜单、背包、暂停、死亡画面下的显示和隐藏，布局编辑。还没测过实体手柄和触控按键同时开启的情况。

退出和重启：
- 游戏的 `exit` 不走 libc 的 `exit()`，而是 `_Exit`：库的析构函数会在还在运行的 FMOD 和驱动线程底下销毁它们的锁（`FORTIFY: pthread_mutex_lock called on a destroyed mutex`）。
- 日志线程用 `read()` 而不是 `fgets`：`fgets` 等待时一直拿着文件锁，退出时的 `fflush(NULL)` 就会永远等下去，表现是黑屏卡死。
- 崩溃或被杀后，游戏发现 `save00/world/.autosave`（约每 3 分钟写一次）就询问是否恢复；选恢复时它用 `CreateProcessW` 启动 `noita.exe -no_logo_splashes -gamemode 0 ...` 再退出。`kernel32.c` 把这一次调用转给 `NoitaActivity.relaunch`：记下参数，由 `RestartActivity` 重启应用。
- 暂停菜单的「保存并退出」会直接关掉游戏，回到桌面，不回主菜单。所以上面的死锁在每次存档退出时都可能碰上。
- 要在进程结束前生效的设置必须用 `commit()`：`apply()` 是异步写入，紧接着杀进程就丢了（「设置声音」按钮曾因此不起作用）。

手柄：SDL 默认把 Android 的加速度计注册成摇杆，而游戏会把任何摇杆都当手柄用，结果菜单光标会跟着机身倾斜跑。`runtime/android_main.c` 因此关掉了 `SDL_HINT_ACCELEROMETER_AS_JOYSTICK`。排查手柄问题时，在 `/sdcard/noitadroid/` 下放一个 `debug_input` 文件，日志里就会记录每个摇杆和偏离中心的轴；文件内容写 `accelerometer` 可以恢复加速度计摇杆，用来对比。

生成的 APK 里有由游戏转换而来的代码：只装在自己的设备上，不要分发。

## 在设备上构建（进行中）

目标和血源移植（Bloodborne-Android）一样：公开发布一个**不含任何游戏代码**的 APK。用户把自己的游戏拷到手机上，由 App 在手机上把游戏翻译、编译成 `files/game/libmain.so`。翻译出来的代码属于游戏的版权方，只留在用户自己的设备上。

步骤：
1. **工具链**（已完成）：`tools/android/build_toolchain.py`，产物在 `.local-deps/toolchain`。都不来自游戏。
   - `libnd_llvm.so`：clang、lld 和 llvm-nm 21.1.8 合成的一个静态多合一程序，目标是 arm64 和 x86，大小 95 MB。
   - `libnd_ld.so`：让 clang 的驱动能调用 lld 的小程序。
   - `libnd_python.so` 和 `libpython3.14.so`：python.org 的 Android 版 CPython 3.14。
   - `libcapstone.so`：只含 x86。
   - `libnd_innoextract.so`：静态链接的 innoextract（1.9 之后到 2025-02 的主线版本），用户可以直接选 GOG 的离线安装包，由 App 在手机上解包。依赖的 Boost 1.92、liblzma、bzip2 都静态链接进去。
   - assets：clang 的头文件、NDK 的 sysroot（API 29）、精简过的 Python 标准库、capstone 的 Python 绑定、pefile，以及转成 dex 的 D8。
2. **设备上的构建流程**（已完成）：
   - `tools/android/device_build.py` 在手机上运行：检查版本 → discover → 生成 SDL/GL 桥接 → regsum → 按块翻译 → 编译 → 链接。每一步都保留做完的部分，中断后再运行就从中断处继续。进度以 `ND-PROGRESS`、`ND-DONE`、`ND-ERROR` 行输出给 App。
   - `tools/target.py` 在设置了 `NOITADROID_TOOLCHAIN` 时改用 App 自带的工具链。
   - `tools/android/build_app.py` 打包公开版 APK（`build/android-app/noitadroid.apk`，Gradle 加 `-Pnoitadroid.public`），里面只有 SDL2、工具链、`kit.zip`（构建用的工具、运行时、头文件、预编译的 LuaJIT 和 gl4es）和 `toolchain.zip`，不含游戏代码和 FMOD。
   - App 端：`BuildService`（前台服务和唤醒锁，通知里显示进度）、`NativeBuild`（路径、解压、库是否可用、进度状态）。`GameCheck` 会在「下载」、存储根目录和存储卡里自动找 GOG 安装包；首屏可以「从安装包安装」（解包加构建）或「在本机构建」。
   - 2026-11-03 在 Pocket S 上从零实测：卸载旧 App，只把安装包放在「下载」里。整个过程约 13.5 分钟：解包不到 1 分钟，构建 785 秒。之后游戏能进，没有 FMOD 时没有声音。
3. **界面**（大部分完成）：
   - **导入 FMOD**（`FmodImport`、`AppFactory`）：App 在「下载」里找 `fmodstudioapi*android.tar.gz`，也可以手动选。从里面取出两个 arm64 库和 `fmod.jar`，检查版本必须是 2.01.x，用自带的 D8 把 `fmod.jar` 转成 dex，放到 `files/fmod`，然后重启 App。`AppComponentFactory` 在进程启动时让 App 的类加载器带上这份 dex，`libfmod.so` 的 `JNI_OnLoad` 就能找到 `org.fmod.*`。dex 必须设为只读（Android 14 的要求）。
   - **选择文件**：一个文件选择器，按文件名区分 GOG 安装包（解包加构建）、`noita.exe`（把它所在的文件夹设为游戏文件夹，`--game` 参数，ABI 因此升到 2）和 FMOD 压缩包（导入）。
   - **解包位置**：机身存储空间不够、而安装包在存储卡上时，解包到存储卡的 `noitadroid/Noita`。解包完成后可以「删除安装包」。
   - **重启**：RestartActivity 改用 `FLAG_ACTIVITY_CLEAR_TASK`。否则新 Intent 和旧任务的根 Intent 相同时，系统只会把那个已经没有 Activity 的旧任务调到前台，什么都不启动。
   - 2026-11-03 在 Pocket S 上实测：
     - 导入 FMOD，2.01.23，有声音（AAudio）；
     - 覆盖安装会杀掉正在进行的构建，再次构建时从中断处继续；
     - 「停止」会结束所有构建进程，再次构建时从中断处继续（385 秒）。
   - 还没实测：在系统文件选择器里选 `noita.exe` 或安装包、删除安装包。导入电脑上建好的库也还没做。
4. **发布**：换成自己画的图标，在 GitHub Releases 发布 APK。

Pocket S 上的实测（2026-11-03，从零开始，不用任何缓存）：

| 步骤 | 时间 | 内存 | 说明 |
|---|---|---|---|
| 在 TF 卡上解包 GOG 安装包（1.5 GB） | 162 秒 | 8 MB | 822 个文件，和电脑上解包的逐字节相同 |
| 翻译（discover、memorder、regsum、lift） | 429 秒 | 单进程峰值 5.7 GB | 6 个 fork 出来的 worker；输出和电脑上的逐字节相同（258 个文件） |
| 编译（290 个任务，8 路并行） | 267 秒 | 单个任务最多 355 MB | CPU 最高 93 °C |
| 链接（不开 LTO） | 0.6 秒 | 217 MB | 49 MB 的 `libmain.so` |

2026-11-06 起手机端也用 ThinLTO 和 PGO（见「性能」一节）。总时间从 768 秒变成 956 秒（约 16 分钟）：编译约 190 秒，链接约 290 秒（8 个线程）。CPU 最高 94.7 °C。链接的内存没在手机上单独测过；电脑上 12 个线程的峰值是 1.6 GB。生成的 `libmain.so` 约 78 MB。

`NoitaActivity` 在 `files/game/libmain.so` 存在时就加载它，而不是 APK 自带的 `libmain.so`。不含 `libmain.so` 的 APK 用这种方式能进游戏，也有声音。

要注意的地方：
- **多进程**：Android 的 libc 没有 `sem_open`，所以 Python 的 `multiprocessing` 不能用。翻译的驱动改成先在父进程里初始化，再自己 fork 出 worker；worker 用写时复制共享父进程的数据，只通过管道传回结果。
- **FMOD**：`libfmod.so` 的 `JNI_OnLoad` 要找 `org.fmod.AudioDevice` 等类，不带 `fmod.jar` 时 App 直接崩溃。打算的做法：
  - 在手机上用 D8 把用户自己的 `fmod.jar` 转成 dex（实测 1 秒）；
  - 用 `AppComponentFactory` 让 App 的类加载器包含这份 dex，这一点还没验证。
- **内存**：翻译时单个进程峰值 5.7 GB，8 GB 内存的手机可能不够，需要优化。
- **adb**：`adb exec-in` 可能在设备上的 tar 写完之前就返回，推送后要先核对文件再用。

## 性能（Pocket S 实测，2026-11-03）

工具：`tools/android/perfbench.sh` 在设备上跑上游 perfbench 的场景（固定种子，测 1800 帧）：
- **heavy**：液体、物理物体和爆炸，压力在模拟线程（job worker）上。
- **jungle**：大量敌人，压力在主线程上。

它需要可调试版的 App：`NoitaActivity` 从 intent 里取游戏参数和环境变量。`install` 会先备份 save00，`remove` 再放回去。

基线（系统分配）：
- heavy 17.8 fps，每帧 CPU 工作 53 ms（M1 Max 上约 16 ms）。
- jungle 26.3 fps，每帧 36 ms。
- GPU 不是瓶颈：交换只占 0.5 ms。游戏内部分辨率本来就是 1280×720，改屏幕分辨率帮不上忙。

发现：
- 骁龙 G3x Gen 2 有 1 个超大核（cpu7，capacity 1024，3.36 GHz）、4 个大核（cpu3–6，811）和 3 个小核（cpu0–2，266）。游戏按核心数开模拟线程（7 个），每轮模拟要等最慢的那个线程。
- **core control**：Qualcomm 会在超大核负载低于 30% 时把它暂停（`/sys/devices/system/cpu/cpu7/core_ctl`，`min_cpus=0`），高于 60% 再唤醒。
  - 系统分配时，主线程很少在超大核上跑：jungle 时超大核平均只有 1.1–1.5 GHz。
- **严格绑定**：
  - 只绑在一个被暂停的核心上，内核会拒绝（`EINVAL`），已有的绑定还会被改写成其余核心。
  - 所以主线程严格绑在超大核上；暂停期间先退到大核，由守护线程（每秒一次）再绑回去。
  - 如果同时允许超大核和大核，调度器不会把主线程挪到超大核上（超大核平均只有 0.6–0.9 GHz）。
- **守护线程**：线程会继承创建者的绑定，Android 切换前后台时也会重置绑定。所以 `runtime/sched.c` 每秒检查一次进程里的所有线程。
- **AYANEO 的性能模式**：测试时它把大核限制在 2.19 GHz（最高 2.80）、超大核限制在 2.48 GHz（最高 3.36）。机器凉下来也不变，所以不是温控。性能模式调高一档，提升可能比下面任何一项都大。
- **ADPF 帧时间提示**（`APerformanceHint`，Android 13+）：会话能建立，但两个场景的结果都在误差内，所以默认关闭，`NOITADROID_HINT=1` 可以打开。别的机器的电源 HAL 可能会用上它。
- **锁 30 帧**：Noita 每帧推进一次模拟，锁 30 帧等于半速运行，不能像血源那样用。

结果（`NOITADROID_CORES`，每种 1–2 轮，顺序轮换）：

| 模式 | jungle fps | heavy fps | 说明 |
|---|---|---|---|
| system | 26.3 | 17.8 | Android 分配 |
| big | – | 15.5 | 不用小核：只剩 4 个模拟线程 |
| prime | 31.8 | 16.2 | 主线程独占超大核，模拟线程只用大核 |
| **split** | **31.0（+16%）** | **19.2（+8%）** | 主线程独占超大核，模拟线程用其余 7 个核心 |
| **fast** | **33.5（+25%）** | **20.3（+14%）** | split，主线程等待时自旋（`MINA4MAC_YIELD=spin`），平均温度高 4 °C 左右 |

结论：
- 游戏内菜单的「性能设置」提供三种：系统分配、split（「超大核给主线程」）和 boost（「超大核身兼两职」，默认，见下一节）。
- 切换立即生效，模拟线程数要等下次启动。
- 菜单只在有单独最快核心（三种以上核心）的机器上提供 split 和 boost。
- fast、big 和 prime 只留作环境变量。
- 原始记录在 `build/perfbench-android/`（不提交）。

### 继续优化（2026-11-05/06，性能模式不变）

都在 Pocket S 上测，同一组对比内的版本交替运行，因为温度会漂移。heavy 和 jungle 每种至少 2 轮。

| 累加的改动 | heavy fps | jungle fps | coal fps |
|---|---|---|---|
| split，电脑上 ThinLTO（起点） | 22 | 34 | 57 |
| boost | 25 | 37 | 59 |
| 内存预取 | 30.6 | 39 | 59 |
| PGO | 33.2 | 41.5 | – |
| **手机端构建（公开版用户拿到的）** | **33.2** | **43.4** | **59.3** |

手机端不开 LTO、也不开 PGO 时（0.2.x 的做法，代码相同）：heavy 25，jungle 36。

coal 是第二关（`$biome_excavationsite`）没有战斗时的情况，它接近 60 fps，所以实际战斗里的卡顿这组场景测不出来。

瓶颈（heavy，用 simpleperf 和 PMU 计数器测）：
- 约九成周期花在游戏线程的代码上，gl4es 和驱动很少。
- 工作线程 IPC 1.70，后端停顿 41%，L1D 未命中大多到 DRAM。L2 TLB 只有 0.35 MPKI，所以 TLB 不是问题。47% 的指令访问内存。
- 主线程 IPC 1.14，前端停顿 28%，L1I 16.7 MPKI。jungle 的主线程热点很分散（最热的函数只占 2.3%）。

各项改动：
- **boost**（`runtime/sched.c`）：模拟线程多开一个，放在主线程的超大核上，nice 10。主线程等待时它就能用这个核心。
- **内存预取**（`tools/prefetch.py`）：区块表是 512×512 的格子，每格指向一个堆上的对象，遍历时读对象的虚表会一路未命中到 DRAM。`lift.py` 按客户机地址在 5 个热循环里插入预取。预取不改变任何语义，只读已映射的客户机内存（堆没有空洞，guard 页只在线程栈附近）。
- **ThinLTO**：手机上也开。比不开 LTO 快约 20%（heavy）和 10%（jungle），整个构建只多约 3 分钟：bitcode 编译比生成机器码快，抵消了一部分链接时间。
- **PGO**：配置文件是 `tools/noitadroid.profdata`（2.4 MB，只有函数名和计数，不含游戏代码），随工具包一起带到手机上。修改翻译器或运行时之后用 `tools/android/pgo_train.sh` 重新训练；代码变了的函数只是没有配置可用，不会出错。源文件必须用相对路径编译，因为配置文件用路径区分静态函数。

试过但没留下：
- **透明大页**：没有提升。内存太碎，大页分配多半失败（`thp_fault_fallback` 6666 次，成功 2042 次），TLB 本来也不是瓶颈。
- **计时器松弛**（`PR_SET_TIMERSLACK`）：`usleep(20)` 实际睡 78 µs，调小后约 +2%，在误差内。
- **把热点函数集中到一个编译单元**：不开 LTO 时 heavy 约 +6%；开了 ThinLTO 就没有明确收益（heavy +2.5%，jungle −3%）。
- **上游的宽寻址**：指令数没有减少。

### OPPO Find X9 Ultra（2026-11-05）

第五代骁龙8至尊版（SM8850，2×4.61 GHz + 6×3.63 GHz），Android 16，屏幕 2376×1080。游戏是调试版，核心分配是「系统分配」，因为这颗芯片只有两种核心，设置页没有其他选项。手机在充电，触控按键开着。

| 场景 | fps | 每帧工作 | 主线程 CPU | Pocket S 同设置 |
|---|---|---|---|---|
| jungle | 40.3 | 23.2 ms | 17.8 ms | 26.3 fps |
| heavy | 30.3 | 30.6 ms | 15.0 ms | 17.8 fps |

每种场景只跑了一次，没有记录温度和频率（`perfbench.sh` 的采样在这台机器上读不到数据），也没试过主线程独占超大核。

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
- **性能**：实测见上面的「性能」一节：Pocket S 上重负载场景 33 fps，敌人多的场景 43 fps。Noita 跟不上时会变慢，而不是跳帧。
- **版本锁定**：运行时是对着这一个版本的地址和数据布局写的，Noita 一更新就要等上游跟进。
- **功能缺失**：带原生 DLL 的 mod 和联网功能用不了（上游把网络做成了离线桩），纯 Lua 的 mod 可以。
