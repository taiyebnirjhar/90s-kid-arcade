// Host-side tests of MatchRecorder (recording a match), run by CI:
//   g++ -std=c++17 -Ilibretrodroid/src/main/cpp recorder_test.cpp recorder.cpp
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>

#include "recorder.h"

using libretrodroid::MatchRecorder;

static int failures = 0;

#define CHECK(cond, ...)                                        \
    do {                                                        \
        if (!(cond)) {                                          \
            failures++;                                         \
            std::printf("  FAIL %s:%d: ", __FILE__, __LINE__);  \
            std::printf(__VA_ARGS__);                           \
            std::printf("\n");                                  \
        }                                                       \
    } while (0)

static uint32_t get32(const std::vector<uint8_t>& b, size_t at) {
    return b[at] | (b[at + 1] << 8) | (b[at + 2] << 16) | ((uint32_t) b[at + 3] << 24);
}

static void aMatchComesOutAsItWasRecorded() {
    std::printf("A match comes out as it was recorded\n");
    MatchRecorder r;
    r.start(500, 2, {1, 2, 3});
    CHECK(r.getStatus() == MatchRecorder::RECORDING, "recording");
    for (int f = 500; f < 740; f++) {
        uint16_t masks[2] = {(uint16_t) f, (uint16_t) (f * 3)};
        r.addFrame(f, masks);
        if ((f + 1) % 120 == 0) r.addSum(f + 1, (int64_t) f * 1000003LL - 5);
    }
    auto b = r.take();
    CHECK(std::memcmp(b.data(), "90SKREC1", 8) == 0, "magic");
    CHECK(get32(b, 8) == 500 && get32(b, 12) == 2 && get32(b, 16) == 240, "first %u ports %u frames %u", get32(b, 8), get32(b, 12), get32(b, 16));
    CHECK(get32(b, 20) == 2 && get32(b, 24) == 3, "sums %u state %u", get32(b, 20), get32(b, 24));
    size_t inputs = 28;
    CHECK((b[inputs] | (b[inputs + 1] << 8)) == 500, "first input");
    CHECK((b[inputs + 2] | (b[inputs + 3] << 8)) == 1500, "second port");
    size_t sums = inputs + 240 * 2 * 2;
    CHECK(get32(b, sums) == 600, "first sum frame %u", get32(b, sums));
    int64_t sum = (int64_t) ((uint64_t) get32(b, sums + 4) | ((uint64_t) get32(b, sums + 8) << 32));
    CHECK(sum == 599LL * 1000003LL - 5, "first sum %lld", (long long) sum);
    CHECK(b.size() == sums + 2 * 12 + 3 && b.back() == 3, "the state last, size %zu", b.size());
    CHECK(r.getStatus() == MatchRecorder::NONE && r.take().empty(), "emptied");
}

static void framesOutOfOrderOrAfterTheEndAreLeftOut() {
    std::printf("Frames out of order, or after the end, are left out\n");
    MatchRecorder r;
    r.start(0, 1, {9});
    uint16_t m = 7;
    r.addFrame(1, &m);           // a gap
    r.addFrame(0, &m);
    r.addFrame(0, &m);           // again
    r.addSum(5, 1);              // not run yet
    r.addSum(1, 2);
    r.addSum(1, 3);              // again
    CHECK(r.frames() == 1 && r.nextFrame() == 1, "one frame, got %zu", r.frames());
    r.end();
    r.addFrame(1, &m);
    CHECK(r.getStatus() == MatchRecorder::ENDED && r.frames() == 1, "ended at one frame");
    auto b = r.take();
    CHECK(get32(b, 16) == 1 && get32(b, 20) == 1, "kept: 1 frame, 1 sum");
}

static void noStateNoRecording() {
    std::printf("No game state, no recording\n");
    MatchRecorder r;
    r.start(0, 2, {});
    uint16_t m[2] = {1, 1};
    r.addFrame(0, m);
    CHECK(r.getStatus() == MatchRecorder::NONE && r.take().empty(), "nothing");
}

static void aVeryLongRecordingEndsByItself() {
    std::printf("A very long recording ends by itself\n");
    MatchRecorder r;
    r.start(0, 1, {1});
    uint16_t m = 0;
    for (size_t f = 0; f < MatchRecorder::MAX_FRAMES + 10; f++) r.addFrame((int32_t) f, &m);
    CHECK(r.getStatus() == MatchRecorder::ENDED && r.frames() == MatchRecorder::MAX_FRAMES, "ended at the limit");
}

static void theChecksumIsOnlinePlays() {
    // FNV-1a's step, from the start value phones have always compared
    // (not FNV's own): changing it would split phones of different versions.
    std::printf("The checksum is the one online play has always compared\n");
    const uint8_t bytes[] = {'a'};
    CHECK(libretrodroid::ramChecksum(bytes, 1) == 0x44bd8ad473cd9906ULL, "the sum of \"a\"");
    CHECK(libretrodroid::ramChecksum(bytes, 0) == 1469598103934665603ULL, "empty");
}

int main() {
    std::printf("Recording matches\n");
    aMatchComesOutAsItWasRecorded();
    framesOutOfOrderOrAfterTheEndAreLeftOut();
    noStateNoRecording();
    aVeryLongRecordingEndsByItself();
    theChecksumIsOnlinePlays();
    if (failures > 0) {
        std::printf("%d failed\n", failures);
        return 1;
    }
    std::printf("All passed\n");
    return 0;
}
