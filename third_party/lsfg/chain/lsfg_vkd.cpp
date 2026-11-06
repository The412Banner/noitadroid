// See lsfg_vkd.h. Fills the chain's dispatch from the Vulkan loader (noitadroid; Bannerlator filled it from its
// renderer's table).

#include "lsfg_vkd.h"

#include <android/log.h>
#include <cstring>

LsfgVkDispatch vkd;

namespace {
bool g_ready = false;
}

bool lsfgVkdInit(PFN_vkGetInstanceProcAddr gipa, VkInstance instance, PFN_vkGetDeviceProcAddr gdpa,
                 VkDevice device) {
    bool ok = true;
#define COPY(fn)                                                                   \
    do {                                                                           \
        vkd.fn = (decltype(vkd.fn))(#fn[0] == 'G' && !strcmp(#fn, "GetPhysicalDeviceMemoryProperties") \
                     ? (PFN_vkVoidFunction)gipa(instance, "vk" #fn)               \
                     : gdpa(device, "vk" #fn));                                   \
        if (!vkd.fn) {                                                             \
            __android_log_print(ANDROID_LOG_ERROR, "LsfgVkd",                      \
                                "entry point vk" #fn " did not resolve");          \
            ok = false;                                                            \
        }                                                                          \
    } while (0)

    COPY(AllocateDescriptorSets);
    COPY(AllocateMemory);
    COPY(BindBufferMemory);
    COPY(BindImageMemory);
    COPY(CmdBindDescriptorSets);
    COPY(CmdBindPipeline);
    COPY(CmdCopyImage);
    COPY(CmdDispatch);
    COPY(CmdPipelineBarrier);
    COPY(CreateBuffer);
    COPY(CreateComputePipelines);
    COPY(CreateDescriptorPool);
    COPY(CreateDescriptorSetLayout);
    COPY(CreateImage);
    COPY(CreateImageView);
    COPY(CreatePipelineLayout);
    COPY(CreateSampler);
    COPY(CreateShaderModule);
    COPY(DestroyBuffer);
    COPY(DestroyDescriptorPool);
    COPY(DestroyDescriptorSetLayout);
    COPY(DestroyImage);
    COPY(DestroyImageView);
    COPY(DestroyPipeline);
    COPY(DestroyPipelineLayout);
    COPY(DestroySampler);
    COPY(DestroyShaderModule);
    COPY(FreeMemory);
    COPY(GetBufferMemoryRequirements);
    COPY(GetImageMemoryRequirements);
    COPY(GetPhysicalDeviceMemoryProperties);
    COPY(MapMemory);
    COPY(UnmapMemory);
    COPY(UpdateDescriptorSets);
#undef COPY

    g_ready = ok;
    return ok;
}

bool lsfgVkdReady() { return g_ready; }
