// Vulkan strip scheduling adapted from nihui/waifu2x-ncnn-vulkan, MIT (2019 nihui).
// Source revision 64914665c45893135c9e50c1c296170a121b9f77; see sr_vulkan.NOTICE.
#include "sr_vulkan.h"
#include "sr_shaders.h"
#include "command.h"
#include <android/bitmap.h>
#include <algorithm>
#include <cstring>

namespace {
// Restore a callback exception only after monitors and pixel locks are released.
// Calling AndroidBitmap_unlockPixels with a pending Java exception can abort CheckJNI.
struct DeferredException {
    JNIEnv* env;
    jthrowable value = nullptr;
    explicit DeferredException(JNIEnv* e) : env(e) {}
    void capture() {
        if (!env->ExceptionCheck()) return;
        auto exception = env->ExceptionOccurred();
        env->ExceptionClear();
        if (!value) value = exception;
        else env->DeleteLocalRef(exception);
    }
    ~DeferredException() {
        if (value) { env->Throw(value); env->DeleteLocalRef(value); }
    }
};
struct BitmapPixels {
    JNIEnv* env;
    jobject bitmap;
    AndroidBitmapInfo info{};
    void* data = nullptr;
    DeferredException& errors;
    BitmapPixels(JNIEnv* e, jobject b, DeferredException& error) : env(e), bitmap(b), errors(error) {
        const int status = AndroidBitmap_getInfo(env, bitmap, &info);
        errors.capture();
        if (status == ANDROID_BITMAP_RESULT_SUCCESS && info.format == ANDROID_BITMAP_FORMAT_RGBA_8888) {
            AndroidBitmap_lockPixels(env, bitmap, &data);
            errors.capture();
        }
    }
    ~BitmapPixels() { if (data) { AndroidBitmap_unlockPixels(env, bitmap); errors.capture(); } }
};
struct Allocators {
    const ncnn::VulkanDevice* device;
    ncnn::VkAllocator* blob;
    ncnn::VkAllocator* staging;
    explicit Allocators(const ncnn::VulkanDevice* d) : device(d),
        blob(d->acquire_blob_allocator()), staging(d->acquire_staging_allocator()) {}
    ~Allocators() {
        if (blob) device->reclaim_blob_allocator(blob);
        if (staging) device->reclaim_staging_allocator(staging);
    }
};
struct Monitor {
    JNIEnv* env;
    jobject value;
    bool entered;
    Monitor(JNIEnv* e, jobject v) : env(e), value(v), entered(env->MonitorEnter(value) == JNI_OK) {}
    ~Monitor() { if (entered) env->MonitorExit(value); }
};
}

namespace ehnz {
SuperResolutionVulkan::SuperResolutionVulkan(int gpu, bool halfStorage, bool halfArithmetic, int batch) : batchTiles(std::clamp(batch, 1, 4)) {
    net.set_vulkan_device(gpu);
    halfStorage = halfStorage && net.vulkan_device()->info.support_fp16_storage();
    net.opt.num_threads = 1;
    net.opt.use_vulkan_compute = true;
    net.opt.use_fp16_packed = halfStorage;
    net.opt.use_fp16_storage = halfStorage;
    net.opt.use_fp16_arithmetic = halfStorage && halfArithmetic && net.vulkan_device()->info.support_fp16_arithmetic();
    net.opt.use_bf16_storage = false;
    // Packed RGBA uint32 buffers avoid requiring the optional 8-bit storage extension.
    net.opt.use_int8_storage = false;
}
int SuperResolutionVulkan::load(const char* param, const char* weights) {
    return net.load_param(param) || net.load_model(weights) || !initializeShaders() ? -1 : 0;
}
int SuperResolutionVulkan::load(FILE* param, FILE* weights) {
    return net.load_param(param) || net.load_model(weights) || !initializeShaders() ? -1 : 0;
}
bool SuperResolutionVulkan::initializeShaders() {
    if (!net.opt.use_vulkan_compute) return false;
    auto device = net.vulkan_device();
    preproc = std::make_unique<ncnn::Pipeline>(device);
    postproc = std::make_unique<ncnn::Pipeline>(device);
    preproc->set_optimal_local_size_xyz(8, 8, 3);
    postproc->set_optimal_local_size_xyz(8, 8, 1);
    std::vector<uint32_t> code;
    if (ncnn::compile_spirv_module(sr_preproc, net.opt, code) ||
        preproc->create(code.data(), code.size() * sizeof(uint32_t), {})) return false;
    code.clear();
    return !ncnn::compile_spirv_module(sr_postproc, net.opt, code) &&
        !postproc->create(code.data(), code.size() * sizeof(uint32_t), {});
}

bool SuperResolutionVulkan::render(JNIEnv* env, jobject source, jobject destination,
                                   jobject progress, jobject parallelLock) {
    return renderRegion(env, source, destination, 0, 0, progress, parallelLock);
}
bool SuperResolutionVulkan::renderRegion(JNIEnv* env, jobject source, jobject destination,
                                        int left, int top, jobject progress, jobject parallelLock) {
    DeferredException errors(env);
    BitmapPixels input(env, source, errors), output(env, destination, errors);
    if (errors.value) return false;
    if (!input.data || !output.data || input.info.width == 0 || input.info.height == 0 ||
        left < 0 || top < 0 || output.info.width % 2 || output.info.height % 2 ||
        left + output.info.width / 2 > input.info.width || top + output.info.height / 2 > input.info.height) return false;
    const auto method = env->GetMethodID(env->GetObjectClass(progress), "onProgress", "(II)Z");
    if (!method) { errors.capture(); return false; }
    constexpr int tile = 128;
    const int width = output.info.width / 2, height = output.info.height / 2;
    const int total = ((width + tile - 1) / tile) * ((height + tile - 1) / tile);
    int completed = 0;
    auto active = [&]() {
        const bool keepGoing = env->CallBooleanMethod(progress, method, completed, total);
        errors.capture();
        return !errors.value && keepGoing;
    };
    const auto* device = net.vulkan_device();
    Allocators allocators(device);
    if (!allocators.blob || !allocators.staging) return false;
    auto opt = net.opt;
    opt.blob_vkallocator = opt.workspace_vkallocator = allocators.blob;
    opt.staging_vkallocator = allocators.staging;
    const size_t tensorBytes = (opt.use_fp16_storage || opt.use_fp16_packed) ? 2u : 4u;
    for (int y = top; y < top + height; y += tile) {
        if (!active()) return false;
        const int coreHeight = std::min(tile, top + height - y);
        const int paddedHeight = (coreHeight + 1) / 2 * 2 + 14;
        const int sourceTop = std::max(0, y - 7);
        const int sourceBottom = std::min((int)input.info.height, y - 7 + paddedHeight);
        const int sourceLeft = std::max(0, left - 7);
        const int sourceRight = std::min((int)input.info.width, left + width + 8);
        ncnn::Mat strip(sourceRight - sourceLeft, sourceBottom - sourceTop, (size_t)4u, 1);
        if (strip.empty()) return false;
        for (int row = 0; row < strip.h; ++row)
            memcpy(strip.row<unsigned char>(row), static_cast<unsigned char*>(input.data) +
                (sourceTop + row) * input.info.stride + sourceLeft * 4, strip.w * 4);
        ncnn::VkCompute cmd(device);
        ncnn::VkMat inputGpu, outputGpu;
        cmd.record_clone(strip, inputGpu, opt);
        if (cmd.submit_and_wait()) return false;
        cmd.reset();
        outputGpu.create(width * 2, coreHeight * 2, (size_t)4u, 1, allocators.blob);
        if (outputGpu.empty()) return false;
        for (int startX = left; startX < left + width; startX += tile * batchTiles) {
            // Same admission lock as CPU detection/inpainting, released after each tile.
            // A canceled page waiting behind a translator never submits another GPU tile.
            if (!active()) return false;
            {
                Monitor admission(env, parallelLock);
                if (!admission.entered) { errors.capture(); return false; }
                if (!active()) return false;
                std::vector<ncnn::VkMat> retained;
                int submitted = 0;
                for (int x = startX; x < std::min(left + width, startX + tile * batchTiles); x += tile) {
                const int coreWidth = std::min(tile, left + width - x);
                const int paddedWidth = (coreWidth + 1) / 2 * 2 + 14;
                ncnn::VkMat tensor(paddedWidth, paddedHeight, 3, tensorBytes, 1, allocators.blob);
                if (tensor.empty()) return false;
                std::vector<ncnn::vk_constant_type> pre(8);
                pre[0].i = inputGpu.w; pre[1].i = inputGpu.h;
                pre[2].i = tensor.w; pre[3].i = tensor.h; pre[4].i = tensor.cstep;
                pre[5].i = x - sourceLeft - 7; pre[6].i = y - sourceTop - 7;
                pre[7].i = (input.info.flags & ANDROID_BITMAP_FLAGS_ALPHA_MASK) == ANDROID_BITMAP_FLAGS_ALPHA_PREMUL;
                cmd.record_pipeline(preproc.get(), {inputGpu, tensor}, pre, tensor);
                auto extractor = net.create_extractor();
                extractor.set_blob_vkallocator(allocators.blob);
                extractor.set_workspace_vkallocator(allocators.blob);
                extractor.set_staging_vkallocator(allocators.staging);
                ncnn::VkMat restored;
                if (extractor.input("Input1", tensor) || extractor.extract("Eltwise4", restored, cmd) ||
                    restored.empty() || restored.w != (paddedWidth - 14) * 2 ||
                    restored.h != (paddedHeight - 14) * 2 || restored.c != 3 || restored.elempack != 1)
                    return false;
                std::vector<ncnn::vk_constant_type> post(6);
                post[0].i = restored.w; post[1].i = restored.cstep;
                post[2].i = outputGpu.w; post[3].i = outputGpu.h;
                post[4].i = (x - left) * 2; post[5].i = coreWidth * 2;
                ncnn::VkMat dispatcher;
                dispatcher.w = coreWidth * 2; dispatcher.h = coreHeight * 2; dispatcher.c = 1;
                cmd.record_pipeline(postproc.get(), {restored, outputGpu}, post, dispatcher);
                retained.push_back(tensor); retained.push_back(restored);
                ++submitted;
                }
                if (cmd.submit_and_wait()) return false;
                cmd.reset();
                completed += submitted;
            }
            if (!active()) return false;
        }
        ncnn::Mat downloaded;
        cmd.record_clone(outputGpu, downloaded, opt);
        if (cmd.submit_and_wait() || downloaded.empty()) return false;
        for (int row = 0; row < downloaded.h; ++row) {
            const auto* rgba = downloaded.row<uint32_t>(row);
            for (int x = 0; x < downloaded.w; ++x) if ((rgba[x] >> 24) != 255u) return false;
            memcpy(static_cast<unsigned char*>(output.data) + ((y - top) * 2 + row) * output.info.stride,
                rgba, downloaded.w * 4);
        }
    }
    return true;
}
}
