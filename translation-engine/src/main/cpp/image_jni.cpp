#include <jni.h>
#include <algorithm>
#include <cmath>
#include <memory>
#include <vector>
#include "net.h"
#include "cpu.h"
#include "ctc_decode.h"

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
    auto net = std::make_unique<ncnn::Net>();
    net->opt.num_threads = std::clamp(static_cast<int>(threads), 1, 4);
    net->opt.use_vulkan_compute = false;
    net->opt.use_fp16_storage = half;
    net->opt.use_fp16_packed = half;
    net->opt.use_fp16_arithmetic = half;
    net->opt.use_bf16_storage = false;
    if (net->load_param(graph.text) != 0 || net->load_model(binary.text) != 0) return 0;
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
