#include <jni.h>
#include <sched.h>
#include <cerrno>
#include <cstring>
#include "../../main/cpp/llama_cpu_policy.h"

// Debug-only test support. Restrict the JNI caller, never another process or UI
// thread, so tests can reproduce OEM CPU limits without root or shell privileges.
extern "C" JNIEXPORT void JNICALL
Java_com_hippo_ehviewer_translation_NativeCpuBackgroundDeviceTest_setAffinity(JNIEnv *env, jobject, jintArray cpus) {
    cpu_set_t mask{};
    const int count = env->GetArrayLength(cpus);
    for (int i = 0; i < count; ++i) {
        jint cpu;
        env->GetIntArrayRegion(cpus, i, 1, &cpu);
        if (cpu < 0 || cpu >= CPU_SETSIZE) {
            env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Invalid test CPU");
            return;
        }
        CPU_SET(cpu, &mask);
    }
    if (sched_setaffinity(0, sizeof(mask), &mask))
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), strerror(errno));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_hippo_ehviewer_translation_NativeCpuBackgroundDeviceTest_policyThreads(JNIEnv *, jobject, jint requested, jint allowed) {
    return ehnz::inferenceThreads(requested, allowed);
}
