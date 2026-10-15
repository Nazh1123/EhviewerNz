#pragma once
#include <jni.h>
#include <memory>
#include "net.h"
#include "pipeline.h"

namespace ehnz {
// The waifu2x Vulkan strip pipeline, restricted to our pinned 2x RGB model.
class SuperResolutionVulkan {
public:
    SuperResolutionVulkan(int gpu, bool halfStorage);
    int load(const char* param, const char* weights);
    int load(FILE* param, FILE* weights);
    bool render(JNIEnv* env, jobject source, jobject destination, jobject progress, jobject parallelLock);
private:
    bool initializeShaders();
    ncnn::Net net;
    std::unique_ptr<ncnn::Pipeline> preproc;
    std::unique_ptr<ncnn::Pipeline> postproc;
};
}
