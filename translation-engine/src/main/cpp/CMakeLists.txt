cmake_minimum_required(VERSION 3.22.1)
project(ehnz_translation LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

if(NOT ANDROID OR NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "Translation currently supports Android arm64-v8a")
endif()

include(FetchContent)
set(CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG} -O2")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -O2")

set(NCNN_SHARED_LIB OFF CACHE BOOL "" FORCE)
set(NCNN_VULKAN ON CACHE BOOL "" FORCE)
# Load the system Vulkan driver dynamically so CPU-only devices can still load OCR.
set(NCNN_SIMPLEVK ON CACHE BOOL "" FORCE)
set(NCNN_SYSTEM_GLSLANG OFF CACHE BOOL "" FORCE)
set(NCNN_SIMPLEOMP ON CACHE BOOL "" FORCE)
set(NCNN_OPENMP ON CACHE BOOL "" FORCE)
set(NCNN_RUNTIME_CPU ON CACHE BOOL "" FORCE)
set(NCNN_INT8 OFF CACHE BOOL "" FORCE)
set(NCNN_BF16 OFF CACHE BOOL "" FORCE)
foreach(feature ARM82DOT ARM82FP16FML ARM84BF16 ARM84I8MM ARM86SVE ARM86SVE2 ARM86SVEBF16 ARM86SVEI8MM ARM86SVEF32MM)
    set(NCNN_${feature} OFF CACHE BOOL "" FORCE)
endforeach()
foreach(component TOOLS EXAMPLES BENCHMARK TESTS)
    set(NCNN_BUILD_${component} OFF CACHE BOOL "" FORCE)
endforeach()
set(NCNN_INSTALL_SDK OFF CACHE BOOL "" FORCE)
FetchContent_Declare(ncnn
    URL https://codeload.github.com/Tencent/ncnn/tar.gz/refs/tags/20260526
    URL_HASH SHA256=da1ade826bc22858a9fb87ae052789bbd614d042b3ec2c22e6544ca83db6bc04)
FetchContent_GetProperties(ncnn)
if(NOT ncnn_POPULATED)
    FetchContent_Populate(ncnn)
endif()
# GitHub source archives omit submodules. Pin ncnn 20260526's exact glslang commit.
FetchContent_Declare(ehnz_glslang
    URL https://codeload.github.com/nihui/glslang/tar.gz/fe88f421038e1bb0a25cd5c1b2dfe505db82d08f
    URL_HASH SHA256=dc78c7f2c479779db66c60582bf8c4173b75b89f689b7edaff3a97d8820dbecd
    SOURCE_DIR ${ncnn_SOURCE_DIR}/glslang)
FetchContent_GetProperties(ehnz_glslang)
if(NOT ehnz_glslang_POPULATED)
    FetchContent_Populate(ehnz_glslang)
endif()
add_subdirectory(${ncnn_SOURCE_DIR} ${ncnn_BINARY_DIR})
# Android provides pthread entry points in libc.
foreach(property LINK_LIBRARIES INTERFACE_LINK_LIBRARIES)
    get_target_property(ncnn_links ncnn ${property})
    list(REMOVE_ITEM ncnn_links pthread)
    set_target_properties(ncnn PROPERTIES ${property} "${ncnn_links}")
endforeach()
file(READ "${CMAKE_CURRENT_SOURCE_DIR}/sr_preproc.comp" SR_PREPROC)
file(READ "${CMAKE_CURRENT_SOURCE_DIR}/sr_postproc.comp" SR_POSTPROC)
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS sr_preproc.comp sr_postproc.comp)
configure_file(sr_shaders.h.in "${CMAKE_CURRENT_BINARY_DIR}/sr_shaders.h" @ONLY)
add_library(ehnz_image SHARED image_jni.cpp sr_vulkan.cpp)
target_include_directories(ehnz_image PRIVATE "${CMAKE_CURRENT_BINARY_DIR}")
target_link_libraries(ehnz_image PRIVATE ncnn log jnigraphics)
target_link_options(ehnz_image PRIVATE "-Wl,-z,max-page-size=16384")

set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
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
FetchContent_Declare(llama
    URL https://codeload.github.com/ggml-org/llama.cpp/tar.gz/48de2a1bcb5b8fdbc8dc41b4c562850ab8d528d2
    URL_HASH SHA256=61d1b28e021f082df9edc8ca42e8f02d9402d3a6e1362b866883ddf0f7d0d76d)
FetchContent_MakeAvailable(llama)
if(NOT TARGET ggml-cpu-android_armv8.0_1)
    message(FATAL_ERROR "The portable ARMv8 CPU backend must be built")
endif()
set(TRANSLATION_JINJA_SOURCES
    ${llama_SOURCE_DIR}/common/jinja/lexer.cpp
    ${llama_SOURCE_DIR}/common/jinja/parser.cpp
    ${llama_SOURCE_DIR}/common/jinja/runtime.cpp
    ${llama_SOURCE_DIR}/common/jinja/value.cpp
    ${llama_SOURCE_DIR}/common/jinja/string.cpp
    ${llama_SOURCE_DIR}/common/json.cpp
    ${llama_SOURCE_DIR}/common/unicode.cpp)
# Only the standalone template interpreter, without llama's HTTP/server/common library.
add_library(ehnz_llama SHARED llama_jni.cpp ${TRANSLATION_JINJA_SOURCES})
target_include_directories(ehnz_llama PRIVATE ${llama_SOURCE_DIR}/common ${llama_SOURCE_DIR}/vendor)
target_link_libraries(ehnz_llama PRIVATE llama log dl)
target_link_options(ehnz_llama PRIVATE "-Wl,-z,max-page-size=16384")

if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    # Independent pinned upstream implementation for same-device comparison only.
    # No reference implementation or TTA shaders are compiled into release builds.
    set(SR_REFERENCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../androidTest/cpp/upstream")
    foreach(shader waifu2x_preproc waifu2x_postproc waifu2x_preproc_tta waifu2x_postproc_tta)
        file(READ "${SR_REFERENCE_DIR}/${shader}.comp" REFERENCE_SHADER HEX)
        string(REGEX REPLACE "(..)" "0x\\1," REFERENCE_SHADER_BYTES "${REFERENCE_SHADER}")
        file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/${shader}.comp.hex.h"
            "static const char ${shader}_comp_data[] = {${REFERENCE_SHADER_BYTES}};")
    endforeach()
    target_sources(ehnz_image PRIVATE "${SR_REFERENCE_DIR}/waifu2x.cpp" ../../androidTest/cpp/sr_reference_jni.cpp)
    target_include_directories(ehnz_image PRIVATE "${SR_REFERENCE_DIR}")
    add_library(ehnz_translation_test SHARED ../../test/cpp/cpu_affinity_jni.cpp)
    target_link_libraries(ehnz_translation_test PRIVATE dl)
    target_link_options(ehnz_translation_test PRIVATE "-Wl,-z,max-page-size=16384")
endif()
