#include "../../main/cpp/ctc_decode.h"
#include <algorithm>
#include <cassert>
#include <iostream>
#include <random>
#include <vector>

int main() {
    // Blanks, repeats, repeated characters separated by blank, and consecutive
    // different characters must preserve the original CTC confidence exactly.
    const std::vector<int> sequence{0, 0, 3, 3, 0, 3, 9, 9, 0, 0, 500, 501, 0};
    std::mt19937 generator(42);
    std::uniform_real_distribution<float> value(-10, 0);
    for (int symbols : {512, 8192}) {
        std::vector<float> scores(symbols);
        int previous = -1, kept = 0;
        double reference = 0, actual = 0;
        for (int expected : sequence) {
            std::generate(scores.begin(), scores.end(), [&] { return value(generator); });
            scores[expected] = 5;
            const int id = std::max_element(scores.begin(), scores.end()) - scores.begin();
            // Independent dense softmax reference, including skipped timesteps.
            double sum = 0;
            for (float score : scores) sum += std::exp(static_cast<double>(score));
            const double dense = std::log(std::exp(static_cast<double>(scores[id])) / sum);
            const float optimized = ehnz::ctcLogProbability(scores.data(), symbols, id, previous);
            if (id != 0 && id != previous) {
                assert(std::abs(optimized - dense) < 1e-6);
                reference += dense;
                actual += optimized;
                ++kept;
            } else {
                assert(optimized == 0);
            }
            previous = id;
        }
        assert(kept == 5);
        assert(std::abs(std::exp(reference / kept) - std::exp(actual / kept)) < 1e-6);
    }
    std::cout << "CTC dense/optimized confidence parity passed\n";
}
