# libnd_fg.so: frame generation (android/fg/ndfg.cpp, third_party/win-fg). Built with the NDK by
# tools/android/build_fg.sh; tools/android/build_app.py puts it in the APK.
cmake_minimum_required(VERSION 3.18)
project(nd_fg CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(WINFG ${CMAKE_CURRENT_SOURCE_DIR}/../../third_party/win-fg)
add_library(nd_fg SHARED ndfg.cpp ${WINFG}/framegen.cpp)
target_include_directories(nd_fg PRIVATE ${CMAKE_CURRENT_SOURCE_DIR} ${WINFG})
# Vulkan through libvulkan.so's vkGetInstanceProcAddr (dlopen), as win-fg's dispatch tables expect
target_compile_definitions(nd_fg PRIVATE VK_NO_PROTOTYPES=1 VK_USE_PLATFORM_ANDROID_KHR)
target_compile_options(nd_fg PRIVATE -O2 -Wall -Wno-unused-function)
target_link_libraries(nd_fg android nativewindow EGL GLESv3 log)
target_link_options(nd_fg PRIVATE -Wl,-z,max-page-size=16384 -Wl,--no-undefined)
