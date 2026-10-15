// Debug-only adapter. The upstream Vulkan pipeline/shaders are unmodified.
#include <jni.h>
#include <android/bitmap.h>
#include <memory>
#include "waifu2x.h"

namespace {
struct BitmapPixels {
    JNIEnv* env; jobject bitmap; AndroidBitmapInfo info{}; void* data = nullptr;
    BitmapPixels(JNIEnv* e, jobject b) : env(e), bitmap(b) {
        if (!AndroidBitmap_getInfo(env, bitmap, &info) && info.format == ANDROID_BITMAP_FORMAT_RGBA_8888)
            AndroidBitmap_lockPixels(env, bitmap, &data);
    }
    ~BitmapPixels() { if (data) AndroidBitmap_unlockPixels(env, bitmap); }
};
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_hippo_ehviewer_superresolution_SuperResolutionReference_open(JNIEnv* env, jobject, jstring param, jstring weights) {
    if (ncnn::create_gpu_instance() || ncnn::get_default_gpu_index() < 0) return 0;
    auto engine = std::make_unique<Waifu2x>(ncnn::get_default_gpu_index(), false, 1);
    engine->noise = -1; engine->scale = 2; engine->tilesize = 128; engine->prepadding = 7;
    const char* graph = env->GetStringUTFChars(param, nullptr);
    const char* binary = env->GetStringUTFChars(weights, nullptr);
    const int result = graph && binary ? engine->load(graph, binary) : -1;
    if (graph) env->ReleaseStringUTFChars(param, graph);
    if (binary) env->ReleaseStringUTFChars(weights, binary);
    return result == 0 ? reinterpret_cast<jlong>(engine.release()) : 0;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_hippo_ehviewer_superresolution_SuperResolutionReference_render(JNIEnv* env, jobject, jlong handle,
        jobject source, jobject destination) {
    if (!handle) return false;
    BitmapPixels in(env, source), out(env, destination);
    if (!in.data || !out.data || out.info.width != in.info.width * 2 || out.info.height != in.info.height * 2) return false;
    ncnn::Mat rgb(in.info.width, in.info.height, (size_t)3u, 3);
    ncnn::Mat result(out.info.width, out.info.height, (size_t)3u, 3);
    for (unsigned y = 0; y < in.info.height; ++y) {
        auto* row = rgb.row<unsigned char>(y);
        const auto* pixels = static_cast<const unsigned char*>(in.data) + y * in.info.stride;
        for (unsigned x = 0; x < in.info.width; ++x) {
            if (pixels[x * 4 + 3] != 255) return false; // Exact opaque comparison; alpha handled by production adapter.
            for (int c = 0; c < 3; ++c) row[x * 3 + c] = pixels[x * 4 + c];
        }
    }
    if (reinterpret_cast<Waifu2x*>(handle)->process(rgb, result)) return false;
    for (unsigned y = 0; y < out.info.height; ++y) {
        const auto* row = result.row<unsigned char>(y);
        auto* pixels = static_cast<unsigned char*>(out.data) + y * out.info.stride;
        for (unsigned x = 0; x < out.info.width; ++x) {
            for (int c = 0; c < 3; ++c) pixels[x * 4 + c] = row[x * 3 + c];
            pixels[x * 4 + 3] = 255;
        }
    }
    return true;
}
extern "C" JNIEXPORT void JNICALL
Java_com_hippo_ehviewer_superresolution_SuperResolutionReference_close(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Waifu2x*>(handle);
}
