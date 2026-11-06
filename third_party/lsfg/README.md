# LSFG chain (vendored, GPL-3.0)

Native Lossless Scaling frame generation: the chain that runs LSFG 3.1's 25 compute shaders, its pacer and
governor (`chain/`), and DXVK's DXBC -> SPIR-V translator (`dxbc/`, zlib, `dxbc/LICENSE.md`) that turns the
shaders in the user's own `Lossless.dll` into SPIR-V once, on the device. **No Lossless Scaling file is in this
repository or the APK**: the user imports their own `Lossless.dll` (Lossless Scaling, Steam app 993090) on the
Performance page; it is read as data, never loaded or run.

Taken from [Bannerlator](https://github.com/The412Banner/Bannerlator) at `7d6757a6`
(`app/src/main/cpp/winlator/lsfg/`, `app/src/main/cpp/thirdparty/dxbc/`), which ported it from WinNative's
`vkr_lsfg` (GPL-3.0-or-later). The LSFG port is credited to Camille LaVey and the Eden Emulator Project, following
upstream [lsfg-vk](https://github.com/PancakeTAS/lsfg-vk) by PancakeTAS; the Android work by FrankBarretta and
GameNative. Changes here: `lsfg_vkd` fills its dispatch from the Vulkan loader (noitadroid creates its own device),
and Bannerlator's renderer probe and JNI glue are left out.

This part is GPL-3.0 (`LICENSE`), so the APK built from this fork, which links it into `libnd_fg.so`, is GPL-3.0 as
a whole; the rest of noitadroid's own code stays MIT.
