cmake_minimum_required(VERSION 3.22.1)
project(ehnz_translation LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

if(NOT ANDROID OR NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "Translation currently supports Android arm64-v8a")
endif()

add_subdirectory("${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/yakuyomi-engine/engine/src/main/cpp"
                 "${CMAKE_CURRENT_BINARY_DIR}/yakuyomi")

include(FetchContent)
# Inference must remain usable in the debug APK as well.
set(CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG} -O2")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -O2")
# Ship portable and accelerated CPU backends; ggml selects a compatible variant
# at runtime instead of forcing ARMv8.6 instructions on every arm64 phone.
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# Let each variant set its own ISA. A cached device-specific override would also
# raise the baseline backend's requirements and defeat runtime feature selection.
set(GGML_CPU_ARM_ARCH "" CACHE STRING "" FORCE)
set(GGML_CPU ON CACHE BOOL "" FORCE)
foreach(backend CUDA HIP METAL VULKAN OPENCL HEXAGON BLAS RPC)
    set(GGML_${backend} OFF CACHE BOOL "" FORCE)
endforeach()
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
# A fixed, checksum-verified source archive; no floating branch or runtime download.
FetchContent_Declare(llama
    URL https://codeload.github.com/ggml-org/llama.cpp/tar.gz/48de2a1bcb5b8fdbc8dc41b4c562850ab8d528d2
    URL_HASH SHA256=61d1b28e021f082df9edc8ca42e8f02d9402d3a6e1362b866883ddf0f7d0d76d)
FetchContent_MakeAvailable(llama)
if(NOT TARGET ggml-cpu-android_armv8.0_1)
    message(FATAL_ERROR "The portable ARMv8 CPU backend must be built")
endif()
add_library(ehnz_llama SHARED llama_jni.cpp)
target_link_libraries(ehnz_llama PRIVATE llama log dl)
target_link_options(ehnz_llama PRIVATE "-Wl,-z,max-page-size=16384")

if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_library(ehnz_translation_test SHARED ../../test/cpp/cpu_affinity_jni.cpp)
    target_link_libraries(ehnz_translation_test PRIVATE dl)
    target_link_options(ehnz_translation_test PRIVATE "-Wl,-z,max-page-size=16384")
endif()
