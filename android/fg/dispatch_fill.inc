// Generated from third_party/win-fg/vk_dispatch.hpp by hand-run script (field -> vk entry point).

static void fillInstance(winfg::InstanceDispatch& t, PFN_vkGetInstanceProcAddr gipa, VkInstance inst) {
    t.GetInstanceProcAddr = reinterpret_cast<PFN_vkGetInstanceProcAddr>(gipa(inst, "vkGetInstanceProcAddr"));
    t.DestroyInstance = reinterpret_cast<PFN_vkDestroyInstance>(gipa(inst, "vkDestroyInstance"));
    t.GetPhysicalDeviceMemoryProperties = reinterpret_cast<PFN_vkGetPhysicalDeviceMemoryProperties>(gipa(inst, "vkGetPhysicalDeviceMemoryProperties"));
    t.GetPhysicalDeviceQueueFamilyProperties = reinterpret_cast<PFN_vkGetPhysicalDeviceQueueFamilyProperties>(gipa(inst, "vkGetPhysicalDeviceQueueFamilyProperties"));
    t.GetPhysicalDeviceProperties = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(gipa(inst, "vkGetPhysicalDeviceProperties"));
    t.GetPhysicalDeviceProperties2 = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties2>(gipa(inst, "vkGetPhysicalDeviceProperties2"));
    t.GetPhysicalDeviceSurfaceCapabilitiesKHR = reinterpret_cast<PFN_vkGetPhysicalDeviceSurfaceCapabilitiesKHR>(gipa(inst, "vkGetPhysicalDeviceSurfaceCapabilitiesKHR"));
}

static void fillDevice(winfg::DeviceDispatch& t, PFN_vkGetDeviceProcAddr gdpa, VkDevice dev) {
    t.GetDeviceProcAddr = gdpa;
    t.DestroyDevice = reinterpret_cast<PFN_vkDestroyDevice>(gdpa(dev, "vkDestroyDevice"));
    t.GetDeviceQueue = reinterpret_cast<PFN_vkGetDeviceQueue>(gdpa(dev, "vkGetDeviceQueue"));
    t.QueueSubmit = reinterpret_cast<PFN_vkQueueSubmit>(gdpa(dev, "vkQueueSubmit"));
    t.QueueSubmit2 = reinterpret_cast<PFN_vkQueueSubmit2>(gdpa(dev, "vkQueueSubmit2"));
    t.QueueSubmit2KHR = reinterpret_cast<PFN_vkQueueSubmit2KHR>(gdpa(dev, "vkQueueSubmit2KHR"));
    t.QueueWaitIdle = reinterpret_cast<PFN_vkQueueWaitIdle>(gdpa(dev, "vkQueueWaitIdle"));
    t.DeviceWaitIdle = reinterpret_cast<PFN_vkDeviceWaitIdle>(gdpa(dev, "vkDeviceWaitIdle"));
    t.CreateSwapchainKHR = reinterpret_cast<PFN_vkCreateSwapchainKHR>(gdpa(dev, "vkCreateSwapchainKHR"));
    t.DestroySwapchainKHR = reinterpret_cast<PFN_vkDestroySwapchainKHR>(gdpa(dev, "vkDestroySwapchainKHR"));
    t.GetSwapchainImagesKHR = reinterpret_cast<PFN_vkGetSwapchainImagesKHR>(gdpa(dev, "vkGetSwapchainImagesKHR"));
    t.AcquireNextImageKHR = reinterpret_cast<PFN_vkAcquireNextImageKHR>(gdpa(dev, "vkAcquireNextImageKHR"));
    t.AcquireNextImage2KHR = reinterpret_cast<PFN_vkAcquireNextImage2KHR>(gdpa(dev, "vkAcquireNextImage2KHR"));
    t.QueuePresentKHR = reinterpret_cast<PFN_vkQueuePresentKHR>(gdpa(dev, "vkQueuePresentKHR"));
    t.CreateImage = reinterpret_cast<PFN_vkCreateImage>(gdpa(dev, "vkCreateImage"));
    t.DestroyImage = reinterpret_cast<PFN_vkDestroyImage>(gdpa(dev, "vkDestroyImage"));
    t.CreateImageView = reinterpret_cast<PFN_vkCreateImageView>(gdpa(dev, "vkCreateImageView"));
    t.DestroyImageView = reinterpret_cast<PFN_vkDestroyImageView>(gdpa(dev, "vkDestroyImageView"));
    t.AllocateMemory = reinterpret_cast<PFN_vkAllocateMemory>(gdpa(dev, "vkAllocateMemory"));
    t.FreeMemory = reinterpret_cast<PFN_vkFreeMemory>(gdpa(dev, "vkFreeMemory"));
    t.BindImageMemory = reinterpret_cast<PFN_vkBindImageMemory>(gdpa(dev, "vkBindImageMemory"));
    t.GetImageMemoryRequirements = reinterpret_cast<PFN_vkGetImageMemoryRequirements>(gdpa(dev, "vkGetImageMemoryRequirements"));
    t.CreateBuffer = reinterpret_cast<PFN_vkCreateBuffer>(gdpa(dev, "vkCreateBuffer"));
    t.DestroyBuffer = reinterpret_cast<PFN_vkDestroyBuffer>(gdpa(dev, "vkDestroyBuffer"));
    t.GetBufferMemoryRequirements = reinterpret_cast<PFN_vkGetBufferMemoryRequirements>(gdpa(dev, "vkGetBufferMemoryRequirements"));
    t.BindBufferMemory = reinterpret_cast<PFN_vkBindBufferMemory>(gdpa(dev, "vkBindBufferMemory"));
    t.MapMemory = reinterpret_cast<PFN_vkMapMemory>(gdpa(dev, "vkMapMemory"));
    t.UnmapMemory = reinterpret_cast<PFN_vkUnmapMemory>(gdpa(dev, "vkUnmapMemory"));
    t.CreateSampler = reinterpret_cast<PFN_vkCreateSampler>(gdpa(dev, "vkCreateSampler"));
    t.DestroySampler = reinterpret_cast<PFN_vkDestroySampler>(gdpa(dev, "vkDestroySampler"));
    t.CreateShaderModule = reinterpret_cast<PFN_vkCreateShaderModule>(gdpa(dev, "vkCreateShaderModule"));
    t.DestroyShaderModule = reinterpret_cast<PFN_vkDestroyShaderModule>(gdpa(dev, "vkDestroyShaderModule"));
    t.CreateDescriptorSetLayout = reinterpret_cast<PFN_vkCreateDescriptorSetLayout>(gdpa(dev, "vkCreateDescriptorSetLayout"));
    t.DestroyDescriptorSetLayout = reinterpret_cast<PFN_vkDestroyDescriptorSetLayout>(gdpa(dev, "vkDestroyDescriptorSetLayout"));
    t.CreatePipelineLayout = reinterpret_cast<PFN_vkCreatePipelineLayout>(gdpa(dev, "vkCreatePipelineLayout"));
    t.DestroyPipelineLayout = reinterpret_cast<PFN_vkDestroyPipelineLayout>(gdpa(dev, "vkDestroyPipelineLayout"));
    t.CreateComputePipelines = reinterpret_cast<PFN_vkCreateComputePipelines>(gdpa(dev, "vkCreateComputePipelines"));
    t.DestroyPipeline = reinterpret_cast<PFN_vkDestroyPipeline>(gdpa(dev, "vkDestroyPipeline"));
    t.CreateDescriptorPool = reinterpret_cast<PFN_vkCreateDescriptorPool>(gdpa(dev, "vkCreateDescriptorPool"));
    t.DestroyDescriptorPool = reinterpret_cast<PFN_vkDestroyDescriptorPool>(gdpa(dev, "vkDestroyDescriptorPool"));
    t.ResetDescriptorPool = reinterpret_cast<PFN_vkResetDescriptorPool>(gdpa(dev, "vkResetDescriptorPool"));
    t.AllocateDescriptorSets = reinterpret_cast<PFN_vkAllocateDescriptorSets>(gdpa(dev, "vkAllocateDescriptorSets"));
    t.UpdateDescriptorSets = reinterpret_cast<PFN_vkUpdateDescriptorSets>(gdpa(dev, "vkUpdateDescriptorSets"));
    t.CreateCommandPool = reinterpret_cast<PFN_vkCreateCommandPool>(gdpa(dev, "vkCreateCommandPool"));
    t.DestroyCommandPool = reinterpret_cast<PFN_vkDestroyCommandPool>(gdpa(dev, "vkDestroyCommandPool"));
    t.AllocateCommandBuffers = reinterpret_cast<PFN_vkAllocateCommandBuffers>(gdpa(dev, "vkAllocateCommandBuffers"));
    t.FreeCommandBuffers = reinterpret_cast<PFN_vkFreeCommandBuffers>(gdpa(dev, "vkFreeCommandBuffers"));
    t.BeginCommandBuffer = reinterpret_cast<PFN_vkBeginCommandBuffer>(gdpa(dev, "vkBeginCommandBuffer"));
    t.EndCommandBuffer = reinterpret_cast<PFN_vkEndCommandBuffer>(gdpa(dev, "vkEndCommandBuffer"));
    t.CmdBindPipeline = reinterpret_cast<PFN_vkCmdBindPipeline>(gdpa(dev, "vkCmdBindPipeline"));
    t.CmdBindDescriptorSets = reinterpret_cast<PFN_vkCmdBindDescriptorSets>(gdpa(dev, "vkCmdBindDescriptorSets"));
    t.CmdDispatch = reinterpret_cast<PFN_vkCmdDispatch>(gdpa(dev, "vkCmdDispatch"));
    t.CmdPipelineBarrier = reinterpret_cast<PFN_vkCmdPipelineBarrier>(gdpa(dev, "vkCmdPipelineBarrier"));
    t.CmdCopyImage = reinterpret_cast<PFN_vkCmdCopyImage>(gdpa(dev, "vkCmdCopyImage"));
    t.CmdCopyImageToBuffer = reinterpret_cast<PFN_vkCmdCopyImageToBuffer>(gdpa(dev, "vkCmdCopyImageToBuffer"));
    t.CmdBlitImage = reinterpret_cast<PFN_vkCmdBlitImage>(gdpa(dev, "vkCmdBlitImage"));
    t.CmdClearColorImage = reinterpret_cast<PFN_vkCmdClearColorImage>(gdpa(dev, "vkCmdClearColorImage"));
    t.CreateFence = reinterpret_cast<PFN_vkCreateFence>(gdpa(dev, "vkCreateFence"));
    t.DestroyFence = reinterpret_cast<PFN_vkDestroyFence>(gdpa(dev, "vkDestroyFence"));
    t.WaitForFences = reinterpret_cast<PFN_vkWaitForFences>(gdpa(dev, "vkWaitForFences"));
    t.ResetFences = reinterpret_cast<PFN_vkResetFences>(gdpa(dev, "vkResetFences"));
    t.CreateSemaphore = reinterpret_cast<PFN_vkCreateSemaphore>(gdpa(dev, "vkCreateSemaphore"));
    t.DestroySemaphore = reinterpret_cast<PFN_vkDestroySemaphore>(gdpa(dev, "vkDestroySemaphore"));
}
