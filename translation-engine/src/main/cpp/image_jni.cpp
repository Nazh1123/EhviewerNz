#include <jni.h>
#include <algorithm>
#include <cmath>
#include <memory>
#include <mutex>
#include <vector>
#include <cstdio>
#include <unistd.h>
#include "net.h"
#include "cpu.h"
#include "gpu.h"
#include "ctc_decode.h"
#include "sr_vulkan.h"

namespace {
struct UtfPath {
    JNIEnv* env;
    jstring value;
    const char* text;
    UtfPath(JNIEnv* e, jstring v) : env(e), value(v), text(v ? e->GetStringUTFChars(v, nullptr) : nullptr) {}
    ~UtfPath() { if (text) env->ReleaseStringUTFChars(value, text); }
};

bool shape(JNIEnv* env, jfloatArray array, size_t expected) {
    return array && expected <= static_cast<size_t>(env->GetArrayLength(array));
}

bool input(JNIEnv* env, jfloatArray array, int width, int height, int channels, ncnn::Mat& tensor) {
    if (width <= 0 || height <= 0 || channels <= 0) return false;
    const size_t plane = static_cast<size_t>(width) * height;
    if (!shape(env, array, plane * channels)) return false;
    tensor.create(width, height, channels);
    if (tensor.empty()) return false;
    for (int c = 0; c < channels; ++c)
        env->GetFloatArrayRegion(array, c * plane, plane, tensor.channel(c));
    return !env->ExceptionCheck();
}

bool floatOutput(const ncnn::Mat& tensor, int channels) {
    return !tensor.empty() && tensor.elempack == 1 && tensor.elemsize == sizeof(float) && tensor.c >= channels;
}

std::unique_ptr<ncnn::Net> imageNet(int threads, bool half) {
    auto net = std::make_unique<ncnn::Net>();
    net->opt.num_threads = std::clamp(threads, 1, 4);
    net->opt.use_vulkan_compute = false;
    net->opt.use_fp16_storage = half;
    net->opt.use_fp16_packed = half;
    net->opt.use_fp16_arithmetic = half;
    net->opt.use_bf16_storage = false;
    return net;
}

int superResolutionGpu() {
    // Keep the process-owned device alive; destroying it when a reader closes can
    // invalidate other live NCNN nets or pending command buffers.
    static std::once_flag initialized;
    static int index = -1;
    std::call_once(initialized, [] {
        if (ncnn::create_gpu_instance() != 0) return;
        const int candidate = ncnn::get_default_gpu_index();
        if (candidate < 0 || candidate >= ncnn::get_gpu_count()) return;
        const auto* device = ncnn::get_gpu_device(candidate);
        if (!device || device->get_heap_budget() < 64) return;
        index = candidate;
    });
    return index;
}

std::unique_ptr<ncnn::Net> superResolutionNet(int threads) {
    const int index = superResolutionGpu();
    if (index < 0) return nullptr;
    auto net = imageNet(threads, false);
    net->set_vulkan_device(index);
    net->opt.use_vulkan_compute = true;
    // Match the CPU's fp32 intermediates. Half storage accumulates rounding across
    // the seven layers and can shift fine line/grayscale values on mobile GPUs.
    net->opt.use_fp16_packed = false;
    net->opt.use_fp16_storage = false;
    net->opt.use_fp16_arithmetic = false;
    return net;
}

using ReadFile = std::unique_ptr<FILE, decltype(&fclose)>;
ReadFile descriptorFile(int source) {
    int fd = dup(source);
    if (fd < 0) return ReadFile(nullptr, fclose);
    if (lseek(fd, 0, SEEK_SET) < 0) { close(fd); return ReadFile(nullptr, fclose); }
    FILE* file = fdopen(fd, "rb");
    if (!file) close(fd);
    return ReadFile(file, fclose);
}

const std::vector<float>& positions() {
    static const std::vector<float> table = [] {
        std::vector<float> values(2048 * 320);
        for (int pair = 0; pair < 160; ++pair) {
            const double frequency = std::pow(10000.0, -pair / 160.0);
            for (int time = 0; time < 2048; ++time) {
                values[time * 320 + pair * 2] = static_cast<float>(std::sin(time * frequency));
                values[time * 320 + pair * 2 + 1] = static_cast<float>(std::cos(time * frequency));
            }
        }
        return values;
    }();
    return table;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_hippo_ehviewer_translation_engine_ImageInference_open(JNIEnv* env, jobject,
        jstring param, jstring weights, jint threads, jboolean half) {
    UtfPath graph(env, param), binary(env, weights);
    if (!graph.text || !binary.text) return 0;
    auto net = imageNet(threads, half);
    if (net->load_param(graph.text) != 0 || net->load_model(binary.text) != 0) return 0;
    return reinterpret_cast<jlong>(net.release());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_hippo_ehviewer_translation_engine_ImageInference_openVulkan(JNIEnv* env, jobject,
        jstring param, jstring weights) {
    UtfPath graph(env, param), binary(env, weights);
    if (!graph.text || !binary.text) return 0;
    auto net = superResolutionNet(1);
    if (!net || net->load_param(graph.text) != 0 || net->load_model(binary.text) != 0 ||
        !net->opt.use_vulkan_compute) return 0;
    return reinterpret_cast<jlong>(net.release());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_hippo_ehviewer_translation_engine_ImageInference_openVulkanDescriptors(JNIEnv*, jobject,
        jint paramFd, jint weightsFd) {
    auto graph = descriptorFile(paramFd);
    auto binary = descriptorFile(weightsFd);
    if (!graph || !binary) return 0;
    auto net = superResolutionNet(1);
    if (!net || net->load_param(graph.get()) != 0 || net->load_model(binary.get()) != 0 ||
        !net->opt.use_vulkan_compute) return 0;
    return reinterpret_cast<jlong>(net.release());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_hippo_ehviewer_translation_engine_ImageInference_vulkanDeviceName(JNIEnv* env, jobject) {
    const int index = superResolutionGpu();
    return index < 0 ? nullptr : env->NewStringUTF(ncnn::get_gpu_info(index).device_name());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_hippo_ehviewer_translation_engine_SuperResolutionInference_open(JNIEnv* env, jobject,
        jstring param, jstring weights, jboolean halfStorage) {
    const int gpu = superResolutionGpu();
    if (gpu < 0) return 0;
    UtfPath graph(env, param), binary(env, weights);
    if (!graph.text || !binary.text) return 0;
    auto model = std::make_unique<ehnz::SuperResolutionVulkan>(gpu, halfStorage);
    return model->load(graph.text, binary.text) == 0 ? reinterpret_cast<jlong>(model.release()) : 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_hippo_ehviewer_translation_engine_SuperResolutionInference_openDescriptors(JNIEnv*, jobject,
        jint paramFd, jint weightsFd, jboolean halfStorage) {
    const int gpu = superResolutionGpu();
    if (gpu < 0) return 0;
    auto graph = descriptorFile(paramFd), binary = descriptorFile(weightsFd);
    if (!graph || !binary) return 0;
    auto model = std::make_unique<ehnz::SuperResolutionVulkan>(gpu, halfStorage);
    return model->load(graph.get(), binary.get()) == 0 ? reinterpret_cast<jlong>(model.release()) : 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hippo_ehviewer_translation_engine_SuperResolutionInference_render(JNIEnv* env, jobject,
        jlong handle, jobject source, jobject destination, jobject progress, jobject parallelLock) {
    return handle && source && destination && source != destination && progress && parallelLock &&
        reinterpret_cast<ehnz::SuperResolutionVulkan*>(handle)->render(env, source, destination, progress, parallelLock);
}

extern "C" JNIEXPORT void JNICALL
Java_com_hippo_ehviewer_translation_engine_SuperResolutionInference_close(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<ehnz::SuperResolutionVulkan*>(handle);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_hippo_ehviewer_translation_engine_ImageInference_openDescriptors(JNIEnv*, jobject,
        jint paramFd, jint weightsFd, jint threads, jboolean half) {
    auto graph = descriptorFile(paramFd);
    auto binary = descriptorFile(weightsFd);
    if (!graph || !binary) return 0;
    auto net = imageNet(threads, half);
    if (net->load_param(graph.get()) != 0 || net->load_model(binary.get()) != 0) return 0;
    return reinterpret_cast<jlong>(net.release());
}

extern "C" JNIEXPORT void JNICALL
Java_com_hippo_ehviewer_translation_engine_ImageInference_close(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<ncnn::Net*>(handle);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hippo_ehviewer_translation_engine_ImageInference_supportsFp16(JNIEnv*, jobject) {
    return ncnn::cpu_support_arm_asimdhp() != 0;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_hippo_ehviewer_translation_engine_ImageInference_detect(JNIEnv* env, jobject, jlong handle,
        jfloatArray pixels, jint width, jint height, jfloatArray logits, jfloatArray strokes) {
    if (!handle) return nullptr;
    ncnn::Mat image;
    if (!input(env, pixels, width, height, 3, image)) return nullptr;
    auto extractor = reinterpret_cast<ncnn::Net*>(handle)->create_extractor();
    ncnn::Mat db, mask;
    if (extractor.input("in0", image) != 0 || extractor.extract("out0", db) != 0 ||
        extractor.extract("out1", mask) != 0 || !floatOutput(db, 1) || !floatOutput(mask, 1) ||
        db.w != width || db.h != height || !shape(env, logits, static_cast<size_t>(db.w) * db.h) ||
        !shape(env, strokes, static_cast<size_t>(mask.w) * mask.h)) return nullptr;
    env->SetFloatArrayRegion(logits, 0, db.w * db.h, db.channel(0));
    env->SetFloatArrayRegion(strokes, 0, mask.w * mask.h, mask.channel(0));
    if (env->ExceptionCheck()) return nullptr;
    const jint size[] = {mask.w, mask.h};
    auto result = env->NewIntArray(2);
    if (result) env->SetIntArrayRegion(result, 0, 2, size);
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hippo_ehviewer_translation_engine_ImageInference_upscale(JNIEnv* env, jobject, jlong handle,
        jfloatArray pixels, jint width, jint height, jfloatArray output) {
    // Pinned waifu2x upconv_7 RGB: seven-pixel replicated border, valid convolutions.
    if (!handle || width < 16 || height < 16 || width > 270 || height > 270) return false;
    const int ow = (width - 14) * 2, oh = (height - 14) * 2;
    const size_t plane = static_cast<size_t>(ow) * oh;
    ncnn::Mat image, restored;
    if (!shape(env, output, plane * 3) || !input(env, pixels, width, height, 3, image)) return false;
    auto extractor = reinterpret_cast<ncnn::Net*>(handle)->create_extractor();
    if (extractor.input("Input1", image) != 0 || extractor.extract("Eltwise4", restored) != 0 ||
        !floatOutput(restored, 3) || restored.w != ow || restored.h != oh) return false;
    for (int c = 0; c < 3; ++c)
        env->SetFloatArrayRegion(output, c * plane, plane, restored.channel(c));
    return !env->ExceptionCheck();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hippo_ehviewer_translation_engine_ImageInference_inpaint(JNIEnv* env, jobject, jlong handle,
        jfloatArray pixels, jfloatArray holes, jint size, jfloatArray output) {
    if (!handle || size <= 0) return false;
    const size_t plane = static_cast<size_t>(size) * size;
    ncnn::Mat image, mask;
    if (!shape(env, output, plane * 3) || !input(env, pixels, size, size, 3, image) ||
        !input(env, holes, size, size, 1, mask)) return false;
    auto extractor = reinterpret_cast<ncnn::Net*>(handle)->create_extractor();
    ncnn::Mat restored;
    if (extractor.input("in0", image) != 0 || extractor.input("in1", mask) != 0 ||
        extractor.extract("out0", restored) != 0 || !floatOutput(restored, 3) ||
        restored.w != size || restored.h != size) return false;
    for (int channel = 0; channel < 3; ++channel)
        env->SetFloatArrayRegion(output, channel * plane, plane, restored.channel(channel));
    return !env->ExceptionCheck();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_hippo_ehviewer_translation_engine_ImageInference_recognize(JNIEnv* env, jobject, jlong handle,
        jfloatArray pixels, jint width, jintArray indices, jfloatArray probabilities) {
    const int steps = width / 4 - 1;
    if (!handle || steps < 1 || steps > 2048 || !indices || env->GetArrayLength(indices) < steps ||
        !shape(env, probabilities, steps)) return false;
    ncnn::Mat image;
    if (!input(env, pixels, width, 48, 3, image)) return false;
    ncnn::Mat pe(320, steps);
    if (pe.empty()) return false;
    std::copy_n(positions().data(), steps * 320, static_cast<float*>(pe.data));
    auto extractor = reinterpret_cast<ncnn::Net*>(handle)->create_extractor();
    ncnn::Mat scores;
    if (extractor.input("in0", image) != 0 || extractor.input("in1", pe) != 0 ||
        extractor.extract("out0", scores) != 0 || !floatOutput(scores, 1) ||
        scores.h != steps || scores.c != 1 || scores.w < 1) return false;
    std::vector<jint> ids(steps);
    std::vector<jfloat> confidence(steps);
    for (int time = 0; time < steps; ++time) {
        const float* row = scores.row(time);
        const float* best = std::max_element(row, row + scores.w);
        ids[time] = best - row;
        confidence[time] = ehnz::ctcLogProbability(row, scores.w, ids[time], time > 0 ? ids[time - 1] : -1);
    }
    env->SetIntArrayRegion(indices, 0, steps, ids.data());
    env->SetFloatArrayRegion(probabilities, 0, steps, confidence.data());
    return !env->ExceptionCheck();
}
