// libnd_fg.so: frame generation for the game, 2x, on the device's own Vulkan driver: WinFG (third_party/win-fg,
// MIT) or LSFG (third_party/lsfg, GPL-3.0: Lossless Scaling's shaders from the user's own Lossless.dll, translated
// once into a cache by nativeBuildLsfgCache).
//
//   The game's thread, at each swap (runtime/gles.c calls ndfg_before_swap with the game's GLES context current):
//     the finished frame (the window's back buffer) is blitted into one of three AHardwareBuffers, with a native
//     fence; nothing waits. When no buffer is free (Vulkan is behind), the frame isn't copied.
//   The frame generation thread: the newest frame is imported into Vulkan (the AHardwareBuffer as an image, the fence
//     as a semaphore), copied into a two-frame ring (flipped: GL's rows go bottom up), and the engine makes the frame
//     between it and the one before (WinFG: alpha 0.35, as Bannerlator's 2x; LSFG: its own chain, pacer and
//     governor). Both are shown, generated then real, on a surface over the game's own (FrameGen.java), through a
//     FIFO swapchain, the display asked for twice the game's rate.
//
// The game's own swap still happens under the overlay, so the game is paced as without frame generation, and when
// this can't run (no Vulkan extension, no surface) the game shows as usual. Log lines go to stderr, which is
// noitadroid.log in the game's process.
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>  // after gl3.h: it uses its types
#include <android/hardware_buffer.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <jni.h>
#include <poll.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <thread>
#include <vector>

#include "config.hpp"
#include "framegen.hpp"
#include "lsfg_dll.h"
#include "lsfg_engine.h"
#include "lsfg_vkd.h"

#include <cmath>
#include <memory>
#include <string>

namespace {

#include "dispatch_fill.inc"


void logf(const char* fmt, ...) __attribute__((format(printf, 1, 2)));
void logf(const char* fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    fprintf(stderr, "[noitadroid-fg] %s\n", buf);
    __android_log_print(ANDROID_LOG_INFO, "noitadroid-fg", "%s", buf);
}

constexpr int kSlots = 3;           // AHardwareBuffers between GL and Vulkan
constexpr float kAlpha = 0.35f;     // where the generated frame sits between the two real ones (Bannerlator's 2x)
enum class Mode { WinFG, Lsfg };
Mode g_mode = Mode::WinFG;
std::string g_lsfgCache, g_tuning;  // from FrameGen.java (nativeStart)
float g_refresh = 120.0f;           // the display's
std::atomic<uint64_t> g_presents{0};  // every frame shown, generated ones too (the HUD's rate)

// fg.conf (FrameGen.java passes its text): "key = value" lines for WinFG, to try other settings on the device.
int tuneInt(const char* key, int dflt) {
    std::string k = std::string(key);
    size_t at = 0;
    while ((at = g_tuning.find(k, at)) != std::string::npos) {
        bool lineStart = at == 0 || g_tuning[at - 1] == '\n';
        size_t eq = g_tuning.find('=', at);
        size_t nl = g_tuning.find('\n', at);
        if (lineStart && eq != std::string::npos && (nl == std::string::npos || eq < nl) &&
            g_tuning.find_first_not_of(" \t", at + k.size()) == eq) {
            return atoi(g_tuning.c_str() + eq + 1);
        }
        at += k.size();
    }
    return dflt;
}

// ---- shared state (g_mu) ----------------------------------------------------------------------------------------
enum class SlotState { Free, Gl, Ready, Vk };
struct Slot {
    AHardwareBuffer* ahb = nullptr;
    EGLImageKHR image = EGL_NO_IMAGE_KHR;
    GLuint tex = 0, fbo = 0;
    SlotState state = SlotState::Free;
    int fence = -1;           // GL's native fence fd for the blit, owned by the slot while Ready
    uint64_t serial = 0;      // which frame
    uint32_t ring = 0;        // the ring generation it belongs to (a new size makes a new ring)
};
std::mutex g_mu;
std::condition_variable g_cv;
Slot g_slot[kSlots];
uint32_t g_w = 0, g_h = 0, g_ring = 0;
uint64_t g_serial = 0;
ANativeWindow* g_pendingWindow = nullptr;  // from Java, for the thread to take
bool g_windowChanged = false, g_windowHeld = false, g_quit = false, g_started = false;
std::atomic<bool> g_failed{false}, g_presenting{false};
std::atomic<uint64_t> g_dropped{0}, g_shown{0}, g_generated{0};

// ---- GL side (the game's thread) ---------------------------------------------------------------------------------
struct {
    bool tried = false, ok = false;
    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC getClientBuffer;
    PFNEGLCREATEIMAGEKHRPROC createImage;
    PFNEGLDESTROYIMAGEKHRPROC destroyImage;
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC targetTexture;
    PFNEGLCREATESYNCKHRPROC createSync;
    PFNEGLDESTROYSYNCKHRPROC destroySync;
    PFNEGLDUPNATIVEFENCEFDANDROIDPROC dupFence;
} gl;

bool glInit() {
    if (gl.tried) return gl.ok;
    gl.tried = true;
    gl.getClientBuffer = (PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC)eglGetProcAddress("eglGetNativeClientBufferANDROID");
    gl.createImage = (PFNEGLCREATEIMAGEKHRPROC)eglGetProcAddress("eglCreateImageKHR");
    gl.destroyImage = (PFNEGLDESTROYIMAGEKHRPROC)eglGetProcAddress("eglDestroyImageKHR");
    gl.targetTexture = (PFNGLEGLIMAGETARGETTEXTURE2DOESPROC)eglGetProcAddress("glEGLImageTargetTexture2DOES");
    gl.createSync = (PFNEGLCREATESYNCKHRPROC)eglGetProcAddress("eglCreateSyncKHR");
    gl.destroySync = (PFNEGLDESTROYSYNCKHRPROC)eglGetProcAddress("eglDestroySyncKHR");
    gl.dupFence = (PFNEGLDUPNATIVEFENCEFDANDROIDPROC)eglGetProcAddress("eglDupNativeFenceFDANDROID");
    gl.ok = gl.getClientBuffer && gl.createImage && gl.destroyImage && gl.targetTexture && gl.createSync &&
            gl.destroySync && gl.dupFence;
    if (!gl.ok) logf("off: the GL driver lacks EGLImage from AHardwareBuffer or native fences");
    return gl.ok;
}

void glFreeSlot(Slot& s) {
    if (s.fbo) glDeleteFramebuffers(1, &s.fbo);
    if (s.tex) glDeleteTextures(1, &s.tex);
    if (s.image != EGL_NO_IMAGE_KHR) gl.destroyImage(eglGetCurrentDisplay(), s.image);
    if (s.ahb) AHardwareBuffer_release(s.ahb);
    if (s.fence >= 0) close(s.fence);
    s = Slot{};
}

// All slots Free (g_mu held): the ring for w x h. GL state it changes is put back.
bool glMakeRing(uint32_t w, uint32_t h) {
    for (Slot& s : g_slot) glFreeSlot(s);
    g_w = g_h = 0;
    GLint tex2d = 0, drawFb = 0;
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &tex2d);
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &drawFb);
    bool ok = true;
    ++g_ring;
    for (Slot& s : g_slot) {
        AHardwareBuffer_Desc d{};
        d.width = w;
        d.height = h;
        d.layers = 1;
        d.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
        d.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT;
        if (AHardwareBuffer_allocate(&d, &s.ahb) != 0) { ok = false; break; }
        EGLClientBuffer cb = gl.getClientBuffer(s.ahb);
        const EGLint attrs[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
        s.image = gl.createImage(eglGetCurrentDisplay(), EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, cb, attrs);
        if (s.image == EGL_NO_IMAGE_KHR) { ok = false; break; }
        glGenTextures(1, &s.tex);
        glBindTexture(GL_TEXTURE_2D, s.tex);
        gl.targetTexture(GL_TEXTURE_2D, (GLeglImageOES)s.image);
        glGenFramebuffers(1, &s.fbo);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, s.fbo);
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, s.tex, 0);
        if (glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) { ok = false; break; }
        s.ring = g_ring;
    }
    glBindTexture(GL_TEXTURE_2D, (GLuint)tex2d);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, (GLuint)drawFb);
    if (!ok) {
        for (Slot& s : g_slot) glFreeSlot(s);
        logf("off: no %ux%u AHardwareBuffer ring for GL", w, h);
        return false;
    }
    g_w = w;
    g_h = h;
    logf("ring %u: %ux%u", g_ring, w, h);
    return true;
}

}  // namespace

// The game's swap, before the real one (runtime/gles.c): the frame into a free slot, for the generation thread.
extern "C" __attribute__((visibility("default"))) void ndfg_before_swap(void) {
    if (g_failed.load(std::memory_order_relaxed) || !glInit()) return;
    EGLDisplay dpy = eglGetCurrentDisplay();
    EGLSurface surf = eglGetCurrentSurface(EGL_DRAW);
    EGLint w = 0, h = 0;
    if (dpy == EGL_NO_DISPLAY || surf == EGL_NO_SURFACE || !eglQuerySurface(dpy, surf, EGL_WIDTH, &w) ||
        !eglQuerySurface(dpy, surf, EGL_HEIGHT, &h) || w <= 0 || h <= 0) {
        return;
    }
    Slot* s = nullptr;
    {
        std::lock_guard<std::mutex> lk(g_mu);
        if (!g_presenting.load(std::memory_order_relaxed)) return;  // nothing to show it on yet
        if ((uint32_t)w != g_w || (uint32_t)h != g_h) {
            for (Slot& x : g_slot) {
                if (x.state != SlotState::Free) return;  // the other side still has one: next frame
            }
            if (!glMakeRing((uint32_t)w, (uint32_t)h)) {
                g_failed = true;
                return;
            }
        }
        for (Slot& x : g_slot) {
            if (x.state == SlotState::Free) { s = &x; break; }
        }
        if (!s) {
            g_dropped.fetch_add(1, std::memory_order_relaxed);
            return;
        }
        s->state = SlotState::Gl;
    }

    GLint readFb = 0, drawFb = 0, readBuf = GL_BACK;
    glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &readFb);
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &drawFb);
    GLboolean scissor = glIsEnabled(GL_SCISSOR_TEST);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
    glGetIntegerv(GL_READ_BUFFER, &readBuf);
    if (readBuf != GL_BACK) glReadBuffer(GL_BACK);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, s->fbo);
    if (scissor) glDisable(GL_SCISSOR_TEST);
    glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL_COLOR_BUFFER_BIT, GL_NEAREST);
    GLenum err = glGetError();
    if (scissor) glEnable(GL_SCISSOR_TEST);
    if (readBuf != GL_BACK) glReadBuffer((GLenum)readBuf);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, (GLuint)readFb);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, (GLuint)drawFb);

    int fd = -1;
    if (err == GL_NO_ERROR) {
        const EGLint attrs[] = {EGL_SYNC_NATIVE_FENCE_FD_ANDROID, EGL_NO_NATIVE_FENCE_FD_ANDROID, EGL_NONE};
        EGLSyncKHR sync = gl.createSync(dpy, EGL_SYNC_NATIVE_FENCE_ANDROID, attrs);
        glFlush();  // the fence's fd exists once the commands are flushed
        if (sync != EGL_NO_SYNC_KHR) {
            fd = gl.dupFence(dpy, sync);
            gl.destroySync(dpy, sync);
        }
    } else {
        static int told;
        if (told++ < 3) logf("blit failed: GL error 0x%x", err);
    }
    std::lock_guard<std::mutex> lk(g_mu);
    if (err != GL_NO_ERROR || fd < 0) {
        if (fd >= 0) close(fd);
        s->state = SlotState::Free;
        return;
    }
    s->fence = fd;
    s->serial = ++g_serial;
    s->state = SlotState::Ready;
    g_cv.notify_all();
}

namespace {

// ---- Vulkan side (the generation thread) -------------------------------------------------------------------------
struct Image {
    VkImage img = VK_NULL_HANDLE;
    VkDeviceMemory mem = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
};

struct Vk {
    void* lib = nullptr;
    PFN_vkGetInstanceProcAddr gipa = nullptr;
    VkInstance inst = VK_NULL_HANDLE;
    VkPhysicalDevice phys = VK_NULL_HANDLE;
    VkDevice dev = VK_NULL_HANDLE;
    uint32_t qf = 0;
    VkQueue queue = VK_NULL_HANDLE;
    bool semFd = false;
    VkPhysicalDeviceMemoryProperties mem{};
    winfg::InstanceDispatch id{};
    winfg::DeviceDispatch dd{};
    PFN_vkDestroySurfaceKHR destroySurface = nullptr;
    PFN_vkCreateAndroidSurfaceKHR createSurface = nullptr;
    PFN_vkGetPhysicalDeviceSurfaceSupportKHR surfaceSupport = nullptr;
    PFN_vkGetPhysicalDeviceSurfaceFormatsKHR surfaceFormats = nullptr;
    PFN_vkGetAndroidHardwareBufferPropertiesANDROID ahbProps = nullptr;
    PFN_vkImportSemaphoreFdKHR importSemFd = nullptr;
    PFN_vkResetCommandBuffer resetCmd = nullptr;

    ANativeWindow* window = nullptr;
    VkSurfaceKHR surface = VK_NULL_HANDLE;
    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    VkExtent2D extent{};
    std::vector<VkImage> scImages;

    VkCommandPool pool = VK_NULL_HANDLE;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    VkSemaphore semGl = VK_NULL_HANDLE, semAcq[2]{}, semDone[2]{};

    Image imported[kSlots];
    uint32_t importedRing[kSlots]{};
    Image ring[2], gen;  // the last two real frames; the generated one
    uint32_t ringW = 0, ringH = 0, curr = 0;
    bool havePrev = false;
    winfg::FrameGen fg;
    std::unique_ptr<lsfg::Engine> lsfg;
    bool fgReady = false;
    uint32_t gmSlot = 0;
    uint64_t sourceFrames = 0;
    float presentRate = 0;  // measured, for LSFG's governor
} vk;

uint32_t memType(uint32_t bits, VkMemoryPropertyFlags want) {
    for (uint32_t i = 0; i < vk.mem.memoryTypeCount; i++) {
        if ((bits & (1u << i)) && (vk.mem.memoryTypes[i].propertyFlags & want) == want) return i;
    }
    for (uint32_t i = 0; i < vk.mem.memoryTypeCount; i++) {
        if (bits & (1u << i)) return i;
    }
    return 0;
}

bool hasExt(const std::vector<VkExtensionProperties>& exts, const char* name) {
    for (auto& e : exts) {
        if (!strcmp(e.extensionName, name)) return true;
    }
    return false;
}

bool vkInit() {
    vk.lib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!vk.lib) { logf("off: no libvulkan.so"); return false; }
    vk.gipa = (PFN_vkGetInstanceProcAddr)dlsym(vk.lib, "vkGetInstanceProcAddr");
    auto createInstance = (PFN_vkCreateInstance)vk.gipa(VK_NULL_HANDLE, "vkCreateInstance");
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "noitadroid frame generation";
    uint32_t loaderVersion = VK_API_VERSION_1_1;
    if (auto ver = (PFN_vkEnumerateInstanceVersion)vk.gipa(VK_NULL_HANDLE, "vkEnumerateInstanceVersion")) ver(&loaderVersion);
    app.apiVersion = loaderVersion >= VK_API_VERSION_1_3 ? VK_API_VERSION_1_3 : VK_API_VERSION_1_1;  // LSFG: 1.3
    const char* iext[] = {VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
    VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ici.pApplicationInfo = &app;
    ici.enabledExtensionCount = 2;
    ici.ppEnabledExtensionNames = iext;
    if (!createInstance || createInstance(&ici, nullptr, &vk.inst) != VK_SUCCESS) {
        logf("off: vkCreateInstance failed");
        return false;
    }
    fillInstance(vk.id, vk.gipa, vk.inst);
    auto enumPhys = (PFN_vkEnumeratePhysicalDevices)vk.gipa(vk.inst, "vkEnumeratePhysicalDevices");
    uint32_t n = 1;
    if (enumPhys(vk.inst, &n, &vk.phys) < 0 || n == 0) { logf("off: no Vulkan device"); return false; }
    VkPhysicalDeviceProperties props{};
    vk.id.GetPhysicalDeviceProperties(vk.phys, &props);
    vk.id.GetPhysicalDeviceMemoryProperties(vk.phys, &vk.mem);

    uint32_t nq = 0;
    vk.id.GetPhysicalDeviceQueueFamilyProperties(vk.phys, &nq, nullptr);
    std::vector<VkQueueFamilyProperties> qs(nq);
    vk.id.GetPhysicalDeviceQueueFamilyProperties(vk.phys, &nq, qs.data());
    vk.qf = UINT32_MAX;
    for (uint32_t i = 0; i < nq; i++) {
        if ((qs[i].queueFlags & (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) ==
            (VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT)) { vk.qf = i; break; }
    }
    if (vk.qf == UINT32_MAX) { logf("off: no graphics+compute queue"); return false; }

    auto enumExt = (PFN_vkEnumerateDeviceExtensionProperties)vk.gipa(vk.inst, "vkEnumerateDeviceExtensionProperties");
    uint32_t ne = 0;
    enumExt(vk.phys, nullptr, &ne, nullptr);
    std::vector<VkExtensionProperties> exts(ne);
    enumExt(vk.phys, nullptr, &ne, exts.data());
    std::vector<const char*> want = {VK_KHR_SWAPCHAIN_EXTENSION_NAME,
                                     VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
                                     VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME};
    for (const char* e : want) {
        if (!hasExt(exts, e)) { logf("off: the Vulkan driver lacks %s", e); return false; }
    }
    for (const char* e : {VK_KHR_SAMPLER_YCBCR_CONVERSION_EXTENSION_NAME, VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME,
                          VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME, VK_KHR_GET_MEMORY_REQUIREMENTS_2_EXTENSION_NAME,
                          VK_KHR_MAINTENANCE1_EXTENSION_NAME, VK_KHR_BIND_MEMORY_2_EXTENSION_NAME,
                          VK_KHR_EXTERNAL_SEMAPHORE_EXTENSION_NAME}) {
        if (hasExt(exts, e)) want.push_back(e);  // the AHardwareBuffer extension's dependencies (core in 1.1)
    }
    vk.semFd = hasExt(exts, VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME);
    if (vk.semFd) want.push_back(VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME);

    // LSFG's shaders (SPIR-V 1.6 from DXVK's translator) need a 1.3 device with these three on.
    VkPhysicalDeviceVulkan12Features v12{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
    VkPhysicalDeviceFeatures2 feats{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2, &v12};
    auto getFeatures2 = (PFN_vkGetPhysicalDeviceFeatures2)vk.gipa(vk.inst, "vkGetPhysicalDeviceFeatures2");
    if (props.apiVersion >= VK_API_VERSION_1_2 && app.apiVersion >= VK_API_VERSION_1_2 && getFeatures2) {
        getFeatures2(vk.phys, &feats);
    }
    VkPhysicalDeviceVulkan12Features v12on{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
    VkPhysicalDeviceFeatures2 featsOn{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2, &v12on};
    if (g_mode == Mode::Lsfg) {
        if (props.apiVersion < VK_API_VERSION_1_3 || app.apiVersion < VK_API_VERSION_1_3 || !v12.vulkanMemoryModel ||
            !feats.features.shaderStorageImageWriteWithoutFormat || !feats.features.shaderStorageImageExtendedFormats) {
            logf("off: LSFG needs Vulkan 1.3 with vulkanMemoryModel and storage images without a format");
            return false;
        }
        v12on.vulkanMemoryModel = VK_TRUE;
        v12on.vulkanMemoryModelDeviceScope = v12.vulkanMemoryModelDeviceScope;
        featsOn.features.shaderStorageImageWriteWithoutFormat = VK_TRUE;
        featsOn.features.shaderStorageImageExtendedFormats = VK_TRUE;
    }

    float prio = 1.0f;
    VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qci.queueFamilyIndex = vk.qf;
    qci.queueCount = 1;
    qci.pQueuePriorities = &prio;
    VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO, g_mode == Mode::Lsfg ? &featsOn : nullptr};
    dci.queueCreateInfoCount = 1;
    dci.pQueueCreateInfos = &qci;
    dci.enabledExtensionCount = (uint32_t)want.size();
    dci.ppEnabledExtensionNames = want.data();
    auto createDevice = (PFN_vkCreateDevice)vk.gipa(vk.inst, "vkCreateDevice");
    if (createDevice(vk.phys, &dci, nullptr, &vk.dev) != VK_SUCCESS) { logf("off: vkCreateDevice failed"); return false; }
    auto gdpa = (PFN_vkGetDeviceProcAddr)vk.gipa(vk.inst, "vkGetDeviceProcAddr");
    fillDevice(vk.dd, gdpa, vk.dev);
    vk.dd.GetDeviceQueue(vk.dev, vk.qf, 0, &vk.queue);
    vk.destroySurface = (PFN_vkDestroySurfaceKHR)vk.gipa(vk.inst, "vkDestroySurfaceKHR");
    vk.createSurface = (PFN_vkCreateAndroidSurfaceKHR)vk.gipa(vk.inst, "vkCreateAndroidSurfaceKHR");
    vk.surfaceSupport = (PFN_vkGetPhysicalDeviceSurfaceSupportKHR)vk.gipa(vk.inst, "vkGetPhysicalDeviceSurfaceSupportKHR");
    vk.surfaceFormats = (PFN_vkGetPhysicalDeviceSurfaceFormatsKHR)vk.gipa(vk.inst, "vkGetPhysicalDeviceSurfaceFormatsKHR");
    vk.ahbProps = (PFN_vkGetAndroidHardwareBufferPropertiesANDROID)gdpa(vk.dev, "vkGetAndroidHardwareBufferPropertiesANDROID");
    vk.importSemFd = vk.semFd ? (PFN_vkImportSemaphoreFdKHR)gdpa(vk.dev, "vkImportSemaphoreFdKHR") : nullptr;
    vk.resetCmd = (PFN_vkResetCommandBuffer)gdpa(vk.dev, "vkResetCommandBuffer");

    VkCommandPoolCreateInfo pci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    pci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pci.queueFamilyIndex = vk.qf;
    vk.dd.CreateCommandPool(vk.dev, &pci, nullptr, &vk.pool);
    VkCommandBufferAllocateInfo cai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    cai.commandPool = vk.pool;
    cai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cai.commandBufferCount = 1;
    vk.dd.AllocateCommandBuffers(vk.dev, &cai, &vk.cmd);
    VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    vk.dd.CreateFence(vk.dev, &fci, nullptr, &vk.fence);
    VkSemaphoreCreateInfo sci{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    vk.dd.CreateSemaphore(vk.dev, &sci, nullptr, &vk.semGl);
    for (int i = 0; i < 2; i++) {
        vk.dd.CreateSemaphore(vk.dev, &sci, nullptr, &vk.semAcq[i]);
        vk.dd.CreateSemaphore(vk.dev, &sci, nullptr, &vk.semDone[i]);
    }
    logf("Vulkan: %s, API %u.%u, sync fd %s", props.deviceName, VK_API_VERSION_MAJOR(props.apiVersion),
         VK_API_VERSION_MINOR(props.apiVersion), vk.semFd ? "yes" : "no");
    if (g_mode == Mode::Lsfg) {
        if (!lsfgVkdInit(vk.gipa, vk.inst, gdpa, vk.dev)) { logf("off: LSFG's Vulkan entry points"); return false; }
        lsfg::ModuleSet modules;
        lsfg::DllStatus st = lsfg::loadModules(g_lsfgCache, modules);
        if (st != lsfg::DllStatus::Ok) {
            logf("off: no LSFG shader cache (%s): import Lossless.dll on the Performance page", lsfg::statusName(st));
            return false;
        }
        vk.lsfg = std::make_unique<lsfg::Engine>();
        if (!vk.lsfg->init(vk.dev, vk.phys, g_lsfgCache, lsfg::kSpirv16)) { logf("off: LSFG init failed"); return false; }
        vk.lsfg->configure(2, 0, 1.0f, g_refresh);
        logf("engine: LSFG 2x, display %.0f Hz", (double)g_refresh);
        return true;
    }
    if (!vk.fg.init(&vk.dd, &vk.id, vk.phys, vk.dev, vk.qf, vk.queue)) { logf("off: win-fg init failed"); return false; }
    // Light by default (the 1080p balanced profile took the Pocket FIT's GPU from 12% to 99% and the game from
    // 61 to 52 fps): flow at 1/8 resolution, no TV-L1 iterations. fg.conf can set them otherwise.
    winfg::Config cfg;
    cfg.enabled = true;
    cfg.multiplier = 2;
    cfg.perfPreset = tuneInt("preset", 2);
    cfg.model = tuneInt("model", 4);
    cfg.frMode = tuneInt("fr", 2);
    cfg.gmMode = tuneInt("gm", 0);
    cfg.sanitize();
    vk.fg.configure(cfg);
    logf("engine: WinFG 2x, preset %d, model %d, fr %d, gm %d", cfg.perfPreset, cfg.model, cfg.frMode, cfg.gmMode);
    return true;
}

void destroyImage(Image& i) {
    if (i.view) vk.dd.DestroyImageView(vk.dev, i.view, nullptr);
    if (i.img) vk.dd.DestroyImage(vk.dev, i.img, nullptr);
    if (i.mem) vk.dd.FreeMemory(vk.dev, i.mem, nullptr);
    i = Image{};
}

bool makeImage(Image& out, uint32_t w, uint32_t h, VkImageUsageFlags usage) {
    VkImageCreateInfo ci{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ci.imageType = VK_IMAGE_TYPE_2D;
    ci.format = VK_FORMAT_R8G8B8A8_UNORM;
    ci.extent = {w, h, 1};
    ci.mipLevels = ci.arrayLayers = 1;
    ci.samples = VK_SAMPLE_COUNT_1_BIT;
    ci.tiling = VK_IMAGE_TILING_OPTIMAL;
    ci.usage = usage;
    ci.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vk.dd.CreateImage(vk.dev, &ci, nullptr, &out.img) != VK_SUCCESS) return false;
    VkMemoryRequirements req{};
    vk.dd.GetImageMemoryRequirements(vk.dev, out.img, &req);
    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    ai.allocationSize = req.size;
    ai.memoryTypeIndex = memType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (vk.dd.AllocateMemory(vk.dev, &ai, nullptr, &out.mem) != VK_SUCCESS) return false;
    vk.dd.BindImageMemory(vk.dev, out.img, out.mem, 0);
    VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vi.image = out.img;
    vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vi.format = ci.format;
    vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    return vk.dd.CreateImageView(vk.dev, &vi, nullptr, &out.view) == VK_SUCCESS;
}

// The ring, the generated frame's image and win-fg's own images for w x h.
bool prepare(uint32_t w, uint32_t h) {
    if (vk.ringW == w && vk.ringH == h && vk.fgReady) return true;
    vk.dd.DeviceWaitIdle(vk.dev);
    for (Image& i : vk.ring) destroyImage(i);
    destroyImage(vk.gen);
    vk.fgReady = false;
    vk.havePrev = false;
    const VkImageUsageFlags ringUse = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT |
                                      VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    if (!makeImage(vk.ring[0], w, h, ringUse) || !makeImage(vk.ring[1], w, h, ringUse) ||
        !makeImage(vk.gen, w, h, VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)) {
        logf("off: no %ux%u images", w, h);
        return false;
    }
    if (vk.lsfg) {
        vk.lsfg->setGuestExtent(w, h);
        if (!vk.lsfg->prepare(w, h, VK_FORMAT_R8G8B8A8_UNORM)) {
            logf("off: LSFG prepare %ux%u failed", w, h);
            return false;
        }
    } else if (!vk.fg.onResize(VkExtent2D{w, h}, VK_FORMAT_R8G8B8A8_UNORM)) {
        logf("off: win-fg onResize %ux%u failed", w, h);
        return false;
    }
    vk.ringW = w;
    vk.ringH = h;
    vk.fgReady = true;
    logf("%s ready for %ux%u", vk.lsfg ? "LSFG" : "win-fg", w, h);
    return true;
}

bool importSlot(int i, const Slot& s) {
    if (vk.imported[i].img && vk.importedRing[i] == s.ring) return true;
    destroyImage(vk.imported[i]);
    VkAndroidHardwareBufferFormatPropertiesANDROID fmt{VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID};
    VkAndroidHardwareBufferPropertiesANDROID props{VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID, &fmt};
    if (vk.ahbProps(vk.dev, s.ahb, &props) != VK_SUCCESS) return false;
    VkExternalMemoryImageCreateInfo ext{VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
    ext.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    VkImageCreateInfo ci{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, &ext};
    ci.imageType = VK_IMAGE_TYPE_2D;
    ci.format = fmt.format != VK_FORMAT_UNDEFINED ? fmt.format : VK_FORMAT_R8G8B8A8_UNORM;
    ci.extent = {g_w, g_h, 1};
    ci.mipLevels = ci.arrayLayers = 1;
    ci.samples = VK_SAMPLE_COUNT_1_BIT;
    ci.tiling = VK_IMAGE_TILING_OPTIMAL;
    ci.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    ci.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    Image& out = vk.imported[i];
    if (vk.dd.CreateImage(vk.dev, &ci, nullptr, &out.img) != VK_SUCCESS) return false;
    VkImportAndroidHardwareBufferInfoANDROID imp{VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID};
    imp.buffer = s.ahb;
    VkMemoryDedicatedAllocateInfo ded{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO, &imp};
    ded.image = out.img;
    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, &ded};
    ai.allocationSize = props.allocationSize;
    ai.memoryTypeIndex = memType(props.memoryTypeBits, 0);
    if (vk.dd.AllocateMemory(vk.dev, &ai, nullptr, &out.mem) != VK_SUCCESS) {
        destroyImage(out);
        return false;
    }
    vk.dd.BindImageMemory(vk.dev, out.img, out.mem, 0);
    vk.importedRing[i] = s.ring;
    return true;
}

void destroySwapchain() {
    if (!vk.dev) return;
    vk.dd.DeviceWaitIdle(vk.dev);
    if (vk.swapchain) vk.dd.DestroySwapchainKHR(vk.dev, vk.swapchain, nullptr);
    if (vk.surface) vk.destroySurface(vk.inst, vk.surface, nullptr);
    vk.swapchain = VK_NULL_HANDLE;
    vk.surface = VK_NULL_HANDLE;
    vk.scImages.clear();
    // An acquire or an import may have been left without its wait (a swapchain out of date): new semaphores.
    VkSemaphoreCreateInfo sci{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
    for (VkSemaphore* sem : {&vk.semGl, &vk.semAcq[0], &vk.semAcq[1]}) {
        if (*sem) vk.dd.DestroySemaphore(vk.dev, *sem, nullptr);
        vk.dd.CreateSemaphore(vk.dev, &sci, nullptr, sem);
    }
}

bool makeSwapchain() {
    VkAndroidSurfaceCreateInfoKHR sci{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
    sci.window = vk.window;
    if (vk.createSurface(vk.inst, &sci, nullptr, &vk.surface) != VK_SUCCESS) { logf("no Vulkan surface"); return false; }
    VkBool32 ok = VK_FALSE;
    vk.surfaceSupport(vk.phys, vk.qf, vk.surface, &ok);
    if (!ok) { logf("off: the queue can't present"); return false; }
    VkSurfaceCapabilitiesKHR caps{};
    vk.id.GetPhysicalDeviceSurfaceCapabilitiesKHR(vk.phys, vk.surface, &caps);
    uint32_t nf = 0;
    vk.surfaceFormats(vk.phys, vk.surface, &nf, nullptr);
    std::vector<VkSurfaceFormatKHR> fmts(nf);
    vk.surfaceFormats(vk.phys, vk.surface, &nf, fmts.data());
    VkSurfaceFormatKHR fmt = fmts.empty() ? VkSurfaceFormatKHR{VK_FORMAT_R8G8B8A8_UNORM, VK_COLOR_SPACE_SRGB_NONLINEAR_KHR}
                                          : fmts[0];
    for (auto& f : fmts) {
        if (f.format == VK_FORMAT_R8G8B8A8_UNORM) { fmt = f; break; }
    }
    vk.extent = caps.currentExtent.width != UINT32_MAX ? caps.currentExtent
                : VkExtent2D{(uint32_t)ANativeWindow_getWidth(vk.window), (uint32_t)ANativeWindow_getHeight(vk.window)};
    uint32_t count = caps.minImageCount + 2;  // two acquired per real frame, one on screen
    if (caps.maxImageCount && count > caps.maxImageCount) count = caps.maxImageCount;
    VkSwapchainCreateInfoKHR ci{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
    ci.surface = vk.surface;
    ci.minImageCount = count;
    ci.imageFormat = fmt.format;
    ci.imageColorSpace = fmt.colorSpace;
    ci.imageExtent = vk.extent;
    ci.imageArrayLayers = 1;
    ci.imageUsage = VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    ci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ci.preTransform = (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
                      ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR : caps.currentTransform;
    ci.compositeAlpha = (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                        ? VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR : VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
    ci.presentMode = VK_PRESENT_MODE_FIFO_KHR;
    ci.clipped = VK_TRUE;
    if (vk.dd.CreateSwapchainKHR(vk.dev, &ci, nullptr, &vk.swapchain) != VK_SUCCESS) {
        logf("no swapchain");
        return false;
    }
    uint32_t n = 0;
    vk.dd.GetSwapchainImagesKHR(vk.dev, vk.swapchain, &n, nullptr);
    vk.scImages.resize(n);
    vk.dd.GetSwapchainImagesKHR(vk.dev, vk.swapchain, &n, vk.scImages.data());
    ANativeWindow_setFrameRate(vk.window, std::min(g_refresh, 120.0f), ANATIVEWINDOW_FRAME_RATE_COMPATIBILITY_DEFAULT);
    logf("swapchain %ux%u, %u images, format %d, transform %d", vk.extent.width, vk.extent.height, n, (int)fmt.format,
         (int)caps.currentTransform);
    return true;
}

void barrier(VkImage img, VkImageLayout from, VkImageLayout to, VkAccessFlags src, VkAccessFlags dst,
             VkPipelineStageFlags srcStage, VkPipelineStageFlags dstStage,
             uint32_t srcQf = VK_QUEUE_FAMILY_IGNORED, uint32_t dstQf = VK_QUEUE_FAMILY_IGNORED) {
    VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    b.oldLayout = from;
    b.newLayout = to;
    b.srcAccessMask = src;
    b.dstAccessMask = dst;
    b.srcQueueFamilyIndex = srcQf;
    b.dstQueueFamilyIndex = dstQf;
    b.image = img;
    b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    vk.dd.CmdPipelineBarrier(vk.cmd, srcStage, dstStage, 0, 0, nullptr, 0, nullptr, 1, &b);
}

void blit(VkImage src, uint32_t sw, uint32_t sh, VkImage dst, uint32_t dw, uint32_t dh, bool flip) {
    VkImageBlit r{};
    r.srcSubresource = r.dstSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    r.srcOffsets[0] = {0, flip ? (int32_t)sh : 0, 0};
    r.srcOffsets[1] = {(int32_t)sw, flip ? 0 : (int32_t)sh, 1};
    r.dstOffsets[1] = {(int32_t)dw, (int32_t)dh, 1};
    vk.dd.CmdBlitImage(vk.cmd, src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &r,
                       (sw == dw && sh == dh) ? VK_FILTER_NEAREST : VK_FILTER_LINEAR);
}

constexpr VkPipelineStageFlags TR = VK_PIPELINE_STAGE_TRANSFER_BIT, CS = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                               TOP = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, BOT = VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;

// One real frame: in, the generated one before it (when there's a frame before it), both shown. false: the
// swapchain must be made again.
bool showFrame(int si, Slot& s) {
    if (!importSlot(si, s) || !prepare(g_w, g_h)) return true;
    bool gen = vk.havePrev;
    uint32_t lsfgGens = 0;
    if (vk.lsfg) {  // LSFG keeps its own history; its pacer and governor say whether to generate
        vk.lsfg->setPresentedRate(vk.presentRate);
        lsfgGens = vk.lsfg->plan(1, ++vk.sourceFrames);
        gen = lsfgGens > 0;
    }
    uint32_t idx[2] = {0, 0};
    for (int k = gen ? 0 : 1; k < 2; k++) {
        VkResult r = vk.dd.AcquireNextImageKHR(vk.dev, vk.swapchain, 1000000000ull, vk.semAcq[k], VK_NULL_HANDLE, &idx[k]);
        if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) return false;  // made again, with new semaphores
    }
    // GL's fence: waited on the GPU (a semaphore) or here.
    bool waitSem = false;
    if (s.fence >= 0) {
        if (vk.importSemFd) {
            VkImportSemaphoreFdInfoKHR info{VK_STRUCTURE_TYPE_IMPORT_SEMAPHORE_FD_INFO_KHR};
            info.semaphore = vk.semGl;
            info.flags = VK_SEMAPHORE_IMPORT_TEMPORARY_BIT;
            info.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT;
            info.fd = s.fence;
            if (vk.importSemFd(vk.dev, &info) == VK_SUCCESS) {
                waitSem = true;
                s.fence = -1;  // the semaphore owns it now
            }
        }
        if (s.fence >= 0) {
            pollfd p{s.fence, POLLIN, 0};
            poll(&p, 1, 200);
            close(s.fence);
            s.fence = -1;
        }
    }
    Image& cur = vk.ring[vk.curr];
    Image& prev = vk.ring[1 - vk.curr];
    const VkImage src = vk.imported[si].img;
    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vk.resetCmd(vk.cmd, 0);
    vk.dd.BeginCommandBuffer(vk.cmd, &bi);
    // the AHardwareBuffer from GL (the foreign queue) for the copy, and back
    barrier(src, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, 0, VK_ACCESS_TRANSFER_READ_BIT, TOP, TR,
            VK_QUEUE_FAMILY_FOREIGN_EXT, vk.qf);
    barrier(cur.img, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT, TOP, TR);
    blit(src, g_w, g_h, cur.img, g_w, g_h, true);
    barrier(src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL, VK_ACCESS_TRANSFER_READ_BIT, 0, TR, BOT,
            vk.qf, VK_QUEUE_FAMILY_FOREIGN_EXT);
    const VkImageLayout curLayout = vk.lsfg ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    barrier(cur.img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, curLayout,
            VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT, TR, CS | TR);
    if (vk.lsfg) {
        vk.lsfg->process(vk.cmd, cur.img, g_w, g_h, lsfgGens);  // every frame: it seeds the history
        if (gen) {
            barrier(vk.gen.img, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, 0, VK_ACCESS_SHADER_WRITE_BIT, TOP, CS);
            vk.lsfg->generateInto(vk.cmd, 0, 0, vk.gen.img, vk.gen.view, g_w, g_h);
        }
    } else if (gen) {
        barrier(vk.gen.img, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, 0, VK_ACCESS_SHADER_WRITE_BIT, TOP, CS);
        vk.fg.record(vk.cmd, prev.view, cur.view, vk.gen.view, kAlpha, vk.gmSlot);
        vk.gmSlot = (vk.gmSlot + 1) % (uint32_t)winfg::FrameGen::kGmSlots;
    }
    if (gen) {
        barrier(vk.gen.img, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_ACCESS_SHADER_WRITE_BIT,
                VK_ACCESS_TRANSFER_READ_BIT, CS, TR);
        VkImage a = vk.scImages[idx[0]];
        barrier(a, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT, TR, TR);
        blit(vk.gen.img, g_w, g_h, a, vk.extent.width, vk.extent.height, false);
        barrier(a, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_ACCESS_TRANSFER_WRITE_BIT, 0, TR, BOT);
    }
    VkImage b = vk.scImages[idx[1]];
    barrier(cur.img, curLayout, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, CS | TR, TR);
    barrier(b, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT, TR, TR);
    blit(cur.img, g_w, g_h, b, vk.extent.width, vk.extent.height, false);
    barrier(b, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_ACCESS_TRANSFER_WRITE_BIT, 0, TR, BOT);
    barrier(cur.img, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_SHADER_READ_BIT, TR, CS);  // "prev" for the next frame
    vk.dd.EndCommandBuffer(vk.cmd);

    VkSemaphore waits[3];
    VkPipelineStageFlags stages[3];
    uint32_t nw = 0;
    if (waitSem) { waits[nw] = vk.semGl; stages[nw++] = TR; }
    for (int k = gen ? 0 : 1; k < 2; k++) { waits[nw] = vk.semAcq[k]; stages[nw++] = TR; }
    VkSemaphore signals[2] = {vk.semDone[0], vk.semDone[1]};
    VkSubmitInfo si2{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si2.waitSemaphoreCount = nw;
    si2.pWaitSemaphores = waits;
    si2.pWaitDstStageMask = stages;
    si2.commandBufferCount = 1;
    si2.pCommandBuffers = &vk.cmd;
    si2.signalSemaphoreCount = gen ? 2 : 1;
    si2.pSignalSemaphores = gen ? signals : &signals[1];
    vk.dd.ResetFences(vk.dev, 1, &vk.fence);
    if (vk.dd.QueueSubmit(vk.queue, 1, &si2, vk.fence) != VK_SUCCESS) {
        logf("off: submit failed");
        g_failed = true;
        return true;
    }
    bool redo = false;
    for (int k = gen ? 0 : 1; k < 2; k++) {
        VkPresentInfoKHR pi{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
        pi.waitSemaphoreCount = 1;
        pi.pWaitSemaphores = &vk.semDone[k];
        pi.swapchainCount = 1;
        pi.pSwapchains = &vk.swapchain;
        pi.pImageIndices = &idx[k];
        VkResult r = vk.dd.QueuePresentKHR(vk.queue, &pi);
        if (r == VK_ERROR_OUT_OF_DATE_KHR) redo = true;  // VK_SUBOPTIMAL_KHR (a rotation) is fine: it's shown
    }
    vk.dd.WaitForFences(vk.dev, 1, &vk.fence, VK_TRUE, 1000000000ull);  // the slot can go back to GL after this
    if (gen) g_generated.fetch_add(1, std::memory_order_relaxed);
    {
        static auto t0 = std::chrono::steady_clock::now();
        static uint64_t n0 = 0;
        uint64_t n = g_presents.fetch_add(gen ? 2 : 1, std::memory_order_relaxed) + (gen ? 2 : 1);
        double dt = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
        if (dt >= 0.5) {
            vk.presentRate = (float)((n - n0) / dt);
            n0 = n;
            t0 = std::chrono::steady_clock::now();
        }
    }
    g_shown.fetch_add(1, std::memory_order_relaxed);
    vk.havePrev = true;
    vk.curr = 1 - vk.curr;
    return !redo;
}

void run() {
    if (!vkInit()) {
        std::lock_guard<std::mutex> lk(g_mu);
        g_failed = true;
        if (g_pendingWindow) ANativeWindow_release(g_pendingWindow);
        g_pendingWindow = nullptr;
        g_windowChanged = false;
        g_cv.notify_all();
        return;
    }
    auto last = std::chrono::steady_clock::now();
    std::unique_lock<std::mutex> lk(g_mu);
    for (;;) {
        g_cv.wait(lk, [] {
            if (g_quit || g_windowChanged) return true;
            if (!vk.swapchain) return false;
            for (Slot& s : g_slot) {
                if (s.state == SlotState::Ready) return true;
            }
            return false;
        });
        if (g_quit) break;
        if (g_windowChanged) {
            ANativeWindow* next = g_pendingWindow;
            g_pendingWindow = nullptr;
            g_windowChanged = false;
            g_presenting = false;
            lk.unlock();
            destroySwapchain();
            if (vk.window) ANativeWindow_release(vk.window);
            vk.window = next;
            vk.havePrev = false;
            bool ok = vk.window && makeSwapchain();
            if (!ok) destroySwapchain();
            lk.lock();
            g_presenting = ok;
            g_windowHeld = vk.window != nullptr;
            g_cv.notify_all();  // nativeSurfaceGone waits for the old window to be let go
            continue;
        }
        int si = -1;
        for (int i = 0; i < kSlots; i++) {  // the newest; older ones are dropped
            Slot& s = g_slot[i];
            if (s.state != SlotState::Ready) continue;
            if (si < 0 || s.serial > g_slot[si].serial) si = i;
        }
        for (int i = 0; i < kSlots; i++) {
            Slot& s = g_slot[i];
            if (i != si && s.state == SlotState::Ready) {
                if (s.fence >= 0) close(s.fence);
                s.fence = -1;
                s.state = SlotState::Free;
                g_dropped.fetch_add(1, std::memory_order_relaxed);
            }
        }
        if (si < 0) continue;
        Slot& s = g_slot[si];
        s.state = SlotState::Vk;
        lk.unlock();
        bool ok = showFrame(si, s);
        if (!ok) {
            destroySwapchain();
            vk.havePrev = false;
            if (vk.window && !makeSwapchain()) destroySwapchain();
        }
        auto now = std::chrono::steady_clock::now();
        if (now - last > std::chrono::seconds(10)) {
            last = now;
            logf("real frames shown %llu, generated %llu, dropped %llu", (unsigned long long)g_shown.load(),
                 (unsigned long long)g_generated.load(), (unsigned long long)g_dropped.load());
        }
        lk.lock();
        if (s.fence >= 0) close(s.fence);
        s.fence = -1;
        s.state = SlotState::Free;
        if (g_failed) break;
    }
    g_presenting = false;
    lk.unlock();
    destroySwapchain();
    if (vk.window) ANativeWindow_release(vk.window);
    vk.window = nullptr;
    lk.lock();
    g_windowHeld = false;
    g_windowChanged = false;
    g_cv.notify_all();
}

}  // namespace

// ---- Java (FrameGen.java) --------------------------------------------------------------------------------------------
namespace {
std::string str(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}
}  // namespace

// mode "lsfg" or WinFG; lsfgCache: the shader cache nativeBuildLsfgCache made; refresh: the display's rate;
// tuning: fg.conf's text (WinFG settings) or empty.
extern "C" JNIEXPORT void JNICALL Java_io_github_zhouwuqi_noitadroid_FrameGen_nativeStart(
        JNIEnv* env, jclass, jstring mode, jstring lsfgCache, jfloat refresh, jstring tuning) {
    std::lock_guard<std::mutex> lk(g_mu);
    if (g_started) return;
    g_started = true;
    g_mode = str(env, mode) == "lsfg" ? Mode::Lsfg : Mode::WinFG;
    g_lsfgCache = str(env, lsfgCache);
    g_tuning = str(env, tuning);
    if (refresh > 1.0f) g_refresh = refresh;
    std::thread(run).detach();
}

// Every frame shown so far, generated ones too: the HUD's rate (StatsOverlay).
extern "C" JNIEXPORT jlong JNICALL Java_io_github_zhouwuqi_noitadroid_FrameGen_nativePresents(JNIEnv*, jclass) {
    return (jlong)g_presents.load(std::memory_order_relaxed);
}

// Lossless.dll (the user's own, read as data) -> LSFG's 25 shaders as SPIR-V in lsfgCache. "Ok" or why not.
extern "C" JNIEXPORT jstring JNICALL Java_io_github_zhouwuqi_noitadroid_FrameGen_nativeBuildLsfgCache(
        JNIEnv* env, jclass, jstring dll, jstring lsfgCache) {
    std::string d = str(env, dll), c = str(env, lsfgCache);
    lsfg::DllStatus st = lsfg::validateDll(d);
    if (st == lsfg::DllStatus::Ok) st = lsfg::buildCache(d, c, false);
    logf("LSFG shader cache from %s: %s", d.c_str(), lsfg::statusName(st));
    return env->NewStringUTF(lsfg::statusName(st));
}

// Whether the cache is there and loads (the Performance page's LSFG state).
extern "C" JNIEXPORT jboolean JNICALL Java_io_github_zhouwuqi_noitadroid_FrameGen_nativeLsfgCacheOk(
        JNIEnv* env, jclass, jstring lsfgCache) {
    lsfg::ModuleSet set;
    return lsfg::loadModules(str(env, lsfgCache), set) == lsfg::DllStatus::Ok && set.complete();
}

extern "C" JNIEXPORT void JNICALL Java_io_github_zhouwuqi_noitadroid_FrameGen_nativeSurface(JNIEnv* env, jclass,
                                                                                           jobject surface) {
    ANativeWindow* w = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
    std::lock_guard<std::mutex> lk(g_mu);
    if (g_failed) {  // nothing will show on it
        if (w) ANativeWindow_release(w);
        return;
    }
    if (g_pendingWindow) ANativeWindow_release(g_pendingWindow);
    g_pendingWindow = w;
    g_windowChanged = true;
    g_cv.notify_all();
}

// The surface is going: returns once the thread has let go of it (Android destroys it after surfaceDestroyed).
extern "C" JNIEXPORT void JNICALL Java_io_github_zhouwuqi_noitadroid_FrameGen_nativeSurfaceGone(JNIEnv*, jclass) {
    std::unique_lock<std::mutex> lk(g_mu);
    if (g_pendingWindow) ANativeWindow_release(g_pendingWindow);
    g_pendingWindow = nullptr;
    if (!g_windowHeld && !g_windowChanged) return;
    g_windowChanged = true;
    g_cv.notify_all();
    g_cv.wait_for(lk, std::chrono::seconds(2), [] { return !g_windowChanged && !g_windowHeld; });
}

extern "C" JNIEXPORT jboolean JNICALL Java_io_github_zhouwuqi_noitadroid_FrameGen_nativeActive(JNIEnv*, jclass) {
    return g_presenting.load() && !g_failed.load();
}
