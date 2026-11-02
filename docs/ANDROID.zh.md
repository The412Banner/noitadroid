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
| 1 | Linux 宿主构建（x86_64，在开发机上）：`build_all.py` 按目标切换编译器和链接参数；`gen_gl`/`gen_sdl` 改为对着 Linux 头文件生成；用 Xvfb + Mesa 跑通，再做 GL 调用调查（`tools/glsurvey.py`） | 是 | 未开始 |
| 2 | Android 构建：NDK、SDL2 Android、gl4es（加 GLES3 同步对象）、FMOD Android、路径和已知文件夹、Gradle 打包 APK | 是 | 未开始 |
| 3 | Pocket S 调优：主线程和工作线程放到大核（X3 + A715）上（`sched.c`）、温控、帧率上限、手柄、分辨率 | 是 | 未开始 |

第 1 阶段是为了降低风险：开发机上就能把整个游戏跑起来调试，不依赖真机，并在那里先拿到 Noita 实际用到的 GL 调用和着色器清单，再决定 Android 上用 gl4es 还是 Zink。

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
NDK=$ANDROID_HOME/ndk/29.0.14206865
COMPAT="-include runtime/compat/compat.h -I runtime/compat -iquote runtime -I third_party/luajit/src"
$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang --target=aarch64-linux-android29 -fsyntax-only $COMPAT runtime/heap.c
```

## 需要你准备的

1. GOG 版 Noita 的离线安装包 `setup_noita_20250125-1640_(79502).exe`，以及旁边的 `.bin` 文件，或者已经解包的游戏目录。
2. FMOD Engine **2.01.x**（测试过的是 2.01.23）的 Android 版；第 1 阶段要的话再加 Linux 版。需要在 fmod.com 注册后自己下载，许可证不允许再分发。
3. 在 Pocket S 上测试（开发机只有 x86 模拟器）。

## 已知风险

- **GL 转换**：Noita 的着色器用 GLSL 1.10/1.20 和固定管线的内置变量，gl4es 能不能全部转换正确，要等第 1 阶段拿到着色器清单才知道。转换不了的话，备选方案是 Mesa Zink + Turnip（完整的桌面 GL），但 Turnip 对 A32 的支持也需要验证。
- **性能**：上游在 M1 Max 上重负载场景约 35–47 fps。G3x Gen 2 的多核算力大约是它的 40–50%，粗估重负载场景 15–25 fps、普通场景 30–50 fps（没有实测）。Noita 跟不上时会变慢，而不是跳帧。
- **版本锁定**：运行时是对着这一个版本的地址和数据布局写的，Noita 一更新就要等上游跟进。
- **功能缺失**：带原生 DLL 的 mod 和联网功能用不了（上游把网络做成了离线桩），纯 Lua 的 mod 可以。
