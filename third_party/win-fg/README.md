# win-fg core (vendored)

The frame-generation core of [win-fg](https://github.com/The412Banner/win-fg) (MIT, see `LICENSE` and
`NOTICE_FIDELITYFX_OPTICALFLOW.md`): optical flow and synthesis in Vulkan compute, its shaders embedded as SPIR-V
(`embedded_shaders.hpp`). `android/fg/ndfg.cpp` runs it on the game's frames (docs/ANDROID.md, "Frame generation").

Taken from Bannerlator's copy of win-fg v0.3.0 at commit `a45ad92f`
(`app/src/main/cpp/winlator/winfg/`), unchanged: that is v0.3.0 plus "free the shared scratch with its engine, never
reuse a dead pool's sets", which a second engine in the same process (frame generation off and on again, a new
surface) needs. Only the core is here: not the Vulkan layer (`layer.cpp`), not Bannerlator's renderer glue.
