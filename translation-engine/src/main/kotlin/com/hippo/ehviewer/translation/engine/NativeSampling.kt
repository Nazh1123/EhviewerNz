package com.hippo.ehviewer.translation.engine

/** JNI values are explicit; unknown imported models keep deterministic sampling. */
enum class NativeSampling(val id: Int) {
    GREEDY(0), HY_TRANSLATION(1), HY_MT2_MANGA(2),
}
