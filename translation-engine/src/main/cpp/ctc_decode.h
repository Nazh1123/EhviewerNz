#pragma once

#include <cmath>

namespace ehnz {
// Blank and consecutive duplicate IDs never contribute to the decoded text's
// confidence. Only emitted characters need a vocabulary-wide normalization.
inline float ctcLogProbability(const float* scores, int count, int id, int previous) {
    if (id == 0 || id == previous) return 0;
    double denominator = 0;
    for (int symbol = 0; symbol < count; ++symbol)
        denominator += std::exp(static_cast<double>(scores[symbol] - scores[id]));
    return static_cast<float>(-std::log(denominator));
}
}
