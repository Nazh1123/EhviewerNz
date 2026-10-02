#pragma once

namespace ehnz {
// ggml's compute barriers spin even with pool.poll == 0. A pool sized for the
// foreground can spend most of its time waiting after an OEM restricts the
// entire process to fewer CPUs. Never oversubscribe that allowed CPU set.
constexpr int inferenceThreads(int requested, int allowed) {
    const int maximum = requested < 1 ? 1 : (requested > 4 ? 4 : requested);
    return allowed < 1 ? 1 : (allowed < maximum ? allowed : maximum);
}
}
