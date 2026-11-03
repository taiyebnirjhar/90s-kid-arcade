#include "recorder.h"

#include <cstring>

namespace libretrodroid {

void MatchRecorder::start(int32_t firstFrame, int portCount, std::vector<uint8_t> startState) {
    first = firstFrame;
    ports = portCount < 1 ? 1 : portCount;
    masks.clear();
    sums.clear();
    state = std::move(startState);
    status = state.empty() ? NONE : RECORDING;
}

void MatchRecorder::addFrame(int32_t frame, const uint16_t* frameMasks) {
    if (status != RECORDING || frame != nextFrame()) return;
    masks.insert(masks.end(), frameMasks, frameMasks + ports);
    if (frames() >= MAX_FRAMES) status = ENDED;
}

void MatchRecorder::addSum(int32_t frame, int64_t sum) {
    if (status == NONE || frame <= first || frame > nextFrame()) return;
    if (!sums.empty() && frame <= sums.back().first) return;
    sums.emplace_back(frame, sum);
}

static void put32(std::vector<uint8_t>& out, uint32_t v) {
    for (int i = 0; i < 4; i++) out.push_back((uint8_t) (v >> (8 * i)));
}

std::vector<uint8_t> MatchRecorder::take() {
    std::vector<uint8_t> out;
    if (status != NONE && !state.empty()) {
        out.reserve(8 + 20 + masks.size() * 2 + sums.size() * 12 + state.size());
        const char* magic = "90SKREC1";
        out.insert(out.end(), magic, magic + 8);
        put32(out, (uint32_t) first);
        put32(out, (uint32_t) ports);
        put32(out, (uint32_t) frames());
        put32(out, (uint32_t) sums.size());
        put32(out, (uint32_t) state.size());
        for (uint16_t m : masks) {
            out.push_back((uint8_t) m);
            out.push_back((uint8_t) (m >> 8));
        }
        for (auto& [frame, sum] : sums) {
            put32(out, (uint32_t) frame);
            put32(out, (uint32_t) (uint64_t) sum);
            put32(out, (uint32_t) ((uint64_t) sum >> 32));
        }
        out.insert(out.end(), state.begin(), state.end());
    }
    status = NONE;
    masks.clear();
    masks.shrink_to_fit();
    sums.clear();
    state.clear();
    state.shrink_to_fit();
    ports = 0;
    return out;
}

} //namespace libretrodroid
