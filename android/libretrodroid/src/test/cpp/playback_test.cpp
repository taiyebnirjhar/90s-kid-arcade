// Frame pacing and the game's sound, tested on a computer: a screen ticking
// at its rate, frames that sometimes run late or hold up, the game's sound
// written a frame at a time and read by an audio device in small blocks, as
// LibretroDroid does on a phone (FPSSync, AudioPipe). And the same with the
// sound path as it was, to show what changed.
//
// From android/ (as CI does):
//   g++ -std=c++17 -O2 -Ilibretrodroid/src/test/cpp/stub -Ilibretrodroid/src/main/cpp
//     libretrodroid/src/test/cpp/playback_test.cpp libretrodroid/src/main/cpp/fpssync.cpp
//     libretrodroid/src/main/cpp/audiopipe.cpp -o playback_test && ./playback_test

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <functional>
#include <random>
#include <vector>

#include "audiopipe.h"
#include "fpssync.h"

using namespace libretrodroid;

namespace {

int failures = 0;

#define CHECK(cond, ...)                                        \
    do {                                                        \
        if (!(cond)) {                                          \
            failures++;                                         \
            std::printf("FAILED (line %d): ", __LINE__);        \
            std::printf(__VA_ARGS__);                           \
            std::printf("\n");                                  \
        }                                                       \
    } while (0)

constexpr double NEO_GEO = 59.1856;   // the game's frame rate
constexpr double CORE_RATE = 48000;   // its sound's
constexpr int DEVICE_RATE = 48000;    // the phone's speaker
constexpr int BLOCK = 192;            // a low-latency audio block (4 ms)

TimePoint at(double seconds) {
    return TimePoint(std::chrono::duration_cast<TimePoint::duration>(std::chrono::duration<double>(seconds)));
}

double seconds(TimePoint t) {
    return std::chrono::duration<double>(t.time_since_epoch()).count();
}

// ----- The screen and the GL thread -----

struct Run {
    double screenHz = 60;                 // the screen's real rate
    double toldHz = -1;                   // what Android says it is (-1: the real one)
    double timerRate = 0;                 // FPSSync's (0: the game's)
    double lateChance = 0;                // a frame's work runs past a tick
    double holdAt = -1, holdFor = 0;      // a hold-up (a long frame) at this time
    double changeAt = -1, changeHz = 0;   // the screen's rate changes then...
    bool tellChange = true;               // ...and Android says so
    double jitterMs = 0.3;
    double length = 60;
    unsigned seed = 1;
};

struct Ticked {
    long frames = 0;             // game frames run
    long ticks = 0;
    long uneven = 0;             // frames shown for a different number of ticks than the one before
    std::vector<std::pair<double, int>> steps;   // (time, frames run)
};

// The GL thread: a step (FPSSync says how many frames), its work, by the
// clock a sleep until the next frame's due, then the screen's next tick.
// [legacy]: as before, one frame a tick, never made up.
Ticked tick(const Run& run, bool legacy = false) {
    std::mt19937 random(run.seed);
    std::uniform_real_distribution<double> unit(0, 1);
    double told = run.toldHz > 0 ? run.toldHz : run.screenHz;
    FPSSync sync(NEO_GEO, told, run.timerRate);
    Ticked out;
    double hz = run.screenHz;
    double t = 1.0;
    double lastShown = t;
    int lastSpan = -1;
    bool changed = false;
    while (t < 1.0 + run.length) {
        if (!changed && run.changeAt > 0 && t - 1.0 >= run.changeAt) {
            changed = true;
            hz = run.changeHz;
            if (run.tellChange) sync.setScreenRefreshRate(hz);
        }
        unsigned n = legacy ? 1 : std::min(sync.advanceFrames(at(t)), 2u);
        out.frames += n;
        out.ticks++;
        out.steps.emplace_back(t, (int) n);
        if (n > 0) {
            int span = (int) std::lround((t - lastShown) * hz);
            if (lastSpan >= 0 && span != lastSpan && out.frames > 10) out.uneven++;
            lastSpan = span;
            lastShown = t;
        }
        double work = 0.004;
        if (unit(random) < run.lateChance) work = 1.3 / hz;
        if (run.holdAt > 0 && t - 1.0 >= run.holdAt && t - 1.0 < run.holdAt + 1.5 / hz) work = run.holdFor;
        double ready = t + work;
        if (!legacy && !sync.usesVSync()) ready = std::max(ready, seconds(sync.nextFrameTime()));
        // The next tick of the screen after it's ready.
        double next = std::ceil((ready - 1.0) * hz - 1e-9) / hz + 1.0;
        if (next <= t) next = t + 1.0 / hz;
        t = next + (unit(random) - 0.5) * run.jitterMs / 1000;
    }
    return out;
}

// ----- The sound -----

// The game's sound: a 440 Hz tone, continuous from frame to frame.
struct Tone {
    double phase = 0;
    void next(int16_t* out, int frames) {
        for (int i = 0; i < frames; i++) {
            auto v = (int16_t) std::lround(8000 * std::sin(phase));
            out[i * 2] = v;
            out[i * 2 + 1] = v;
            phase += 2 * M_PI * 440 / CORE_RATE;
        }
    }
};

struct Heard {
    long shortBlocks = 0;      // blocks with a gap, stretch or fade
    long dropped = 0;          // the game's sound frames lost
    long clicks = 0;           // jumps no 446 Hz tone makes
    double maxJump = 0;
    double wobble = 0;         // the rate control's swing (pitch), after settling
    double latencyMs = 0;      // average buffer
    double silentMs = 0;
};

// The sound path as it was: a FIFO, rate control on its raw fill, a linear
// resampler started afresh each block, silence when short; and the game's
// sound rate stretched the wrong way (content / screen).
struct OldPath {
    std::vector<int16_t> fifo;
    size_t capacity;
    double base, framesToSubmit = 0, errorIntegral = 0;
    long shortBlocks = 0, dropped = 0;
    OldPath(double frameRate) {
        double stretch = NEO_GEO / frameRate;    // inverted, as it was
        double inputRate = CORE_RATE * stretch;
        double maxLatency = std::max(4 / NEO_GEO * 1000, 32.0);
        capacity = (size_t) ((int) (inputRate / (500.0 / maxLatency)) / 2 * 2) / 2;
        base = inputRate / DEVICE_RATE;
    }
    void write(const int16_t* data, int frames) {
        size_t room = capacity - fifo.size() / 2;
        size_t n = std::min((size_t) frames, room);
        fifo.insert(fifo.end(), data, data + n * 2);
        dropped += (long) (frames - n);
    }
    double adjust = 0;
    void read(int16_t* out, int frames) {
        double err = ((double) capacity - 2.0 * (fifo.size() / 2)) / capacity;
        double dt = 0.001 * frames;
        errorIntegral += err * dt;
        adjust = std::clamp(0.006 * err, -0.003, 0.003) + std::clamp(0.00002 * errorIntegral, -0.02, 0.02);
        framesToSubmit += frames * base * (1.0 - adjust);
        int take = (int) std::lround(framesToSubmit);
        framesToSubmit -= take;
        std::vector<int16_t> in((size_t) std::max(take, 1) * 2, 0);
        int got = std::min(take, (int) (fifo.size() / 2));
        std::copy(fifo.begin(), fifo.begin() + got * 2, in.begin());
        fifo.erase(fifo.begin(), fifo.begin() + got * 2);
        if (got < take) shortBlocks++;
        for (int i = 0; i < frames; i++) {
            double pos = (double) i / frames * std::max(take, 1);
            int a = (int) pos, b = std::min(a + 1, std::max(take, 1) - 1);
            double f = pos - a;
            for (int c = 0; c < 2; c++) out[i * 2 + c] = (int16_t) (in[b * 2 + c] * f + in[a * 2 + c] * (1 - f));
        }
    }
    int available() const { return (int) (fifo.size() / 2); }
};

// Plays [ticked]'s frames' sound through the speaker, every block in time order.
template <typename Path>
Heard listen(const Ticked& ticked, Path& path, std::function<void(int16_t*, int)> read, std::function<double()> adjust,
             double length) {
    Tone tone;
    Heard heard;
    double perFrame = CORE_RATE / NEO_GEO, owed = 0;
    std::vector<int16_t> frame(2000 * 2), block(BLOCK * 2);
    size_t next = 0;
    double previous = 0, latency = 0, sumAdjust = 0, sumAdjust2 = 0;
    long blocks = 0, settled = 0;
    // A 446 Hz tone (the 440 stretched) moves at most this much from one sample to the next.
    double slope = 8000 * 2 * M_PI * 450 / DEVICE_RATE;
    for (double t = 1.0; t < 1.0 + length; t += (double) BLOCK / DEVICE_RATE) {
        while (next < ticked.steps.size() && ticked.steps[next].first <= t) {
            for (int f = 0; f < ticked.steps[next].second; f++) {
                owed += perFrame;
                int n = (int) owed;
                owed -= n;
                tone.next(frame.data(), n);
                path.write(frame.data(), n);
            }
            next++;
        }
        read(block.data(), BLOCK);
        blocks++;
        bool settledNow = t > 6.0;
        long silent = 0;
        for (int i = 0; i < BLOCK; i++) {
            double v = block[i * 2];
            double jump = std::abs(v - previous);
            if (settledNow) {
                heard.maxJump = std::max(heard.maxJump, jump);
                if (jump > slope * 1.6 + 8000.0 / 100) heard.clicks++;
            }
            if (v == 0 && previous == 0) silent++;
            previous = v;
        }
        if (settledNow) {
            settled++;
            heard.silentMs += 1000.0 * silent / DEVICE_RATE;
            double a = adjust();
            sumAdjust += a;
            sumAdjust2 += a * a;
            latency += 1000.0 * path.available() / CORE_RATE;
        }
    }
    double mean = sumAdjust / std::max(settled, 1L);
    heard.wobble = std::sqrt(std::max(0.0, sumAdjust2 / std::max(settled, 1L) - mean * mean));
    heard.latencyMs = latency / std::max(settled, 1L);
    return heard;
}

Heard listenNew(const Ticked& ticked, double frameRate, double length) {
    double framesPerVideoFrame = CORE_RATE / NEO_GEO;
    AudioPipe pipe((int32_t) std::lround(framesPerVideoFrame * 6), (int32_t) std::lround(framesPerVideoFrame * 2));
    double ratio = CORE_RATE * (frameRate / NEO_GEO) / DEVICE_RATE;
    long before = 0;
    Heard h = listen(ticked, pipe, [&](int16_t* out, int n) { pipe.read(out, n, ratio, DEVICE_RATE); },
                     [&] { return pipe.adjustment(); }, length);
    (void) before;
    h.shortBlocks = pipe.shortBlocks();
    h.dropped = pipe.droppedFrames();
    return h;
}

Heard listenOld(const Ticked& ticked, double frameRate, double length) {
    OldPath path(frameRate);
    Heard h = listen(ticked, path, [&](int16_t* out, int n) { path.read(out, n); }, [&] { return path.adjust; }, length);
    h.shortBlocks = path.shortBlocks;
    h.dropped = path.dropped;
    return h;
}

void print(const char* what, const Heard& h) {
    std::printf("  %-44s short blocks %5ld, dropped %6ld, clicks %5ld, silent %6.0f ms, pitch wobble %.4f%%, latency %4.0f ms\n",
                what, h.shortBlocks, h.dropped, h.clicks, h.silentMs, h.wobble * 100, h.latencyMs);
}

// ----- Tests -----

void framesGoWithTheScreenWhenTheyCan() {
    struct Case { double screen, timer; int ticksPerFrame; double rate; };
    Case cases[] = {
        {60, 0, 1, 60}, {120, 0, 2, 60}, {180, 0, 3, 60}, {59.94, 0, 1, 59.94},
        {90, 0, 0, NEO_GEO}, {144, 0, 0, NEO_GEO},
        // In a multiplayer room: 60 a second on every phone.
        {90, 60, 0, 60}, {120, 60, 2, 60}, {144, 60, 0, 60},
    };
    for (auto& c : cases) {
        FPSSync sync(NEO_GEO, c.screen, c.timer);
        CHECK(sync.getTicksPerFrame() == c.ticksPerFrame, "%.2f Hz: %d ticks a frame, not %d", c.screen, sync.getTicksPerFrame(), c.ticksPerFrame);
        CHECK(std::abs(sync.getFrameRate() - c.rate) < 0.01, "%.2f Hz: %.3f frames a second, not %.3f", c.screen, sync.getFrameRate(), c.rate);
    }
}

void aHundredAndTwentyHertzScreenShowsEveryFrameForTwoTicks() {
    Run run;
    run.screenHz = 120;
    run.jitterMs = 1.0;
    Ticked t = tick(run);
    CHECK(std::abs(t.frames - 60 * run.length) <= 2, "%ld frames in %.0f s", t.frames, run.length);
    CHECK(t.uneven == 0, "%ld frames shown longer or shorter than the others", t.uneven);
    // As before (by the clock at 59.19): every ~37th frame showed for 3 ticks.
    Run before = run;
    before.toldHz = 90;   // anything not a multiple: by the clock
    Ticked old = tick(before);
    std::printf("  120 Hz screen: frames shown unevenly, by the clock (before) %ld, with the screen (now) %ld\n", old.uneven, t.uneven);
}

void aFrameThatRunsLateIsMadeUp() {
    Run run;
    run.lateChance = 0.03;
    Ticked now = tick(run);
    Ticked before = tick(run, true);
    std::printf("  60 Hz, 3%% of frames late: %ld frames in %.0f s now, %ld before (%.0f expected)\n",
                now.frames, run.length, before.frames, 60 * run.length);
    CHECK(std::abs(now.frames - 60 * run.length) <= 2, "%ld frames", now.frames);
    CHECK(before.frames < 60 * run.length * 0.985, "before: %ld", before.frames);
}

void aScreenThatSpeedsUpUnsaidDoesntSpeedTheGameUp() {
    Run run;
    run.changeAt = 10;
    run.changeHz = 120;
    run.tellChange = false;
    run.length = 30;
    Ticked t = tick(run);
    // 10 s at 60, then 20 s by the clock at the game's rate: never 120 a second.
    double expected = 10 * 60 + 20 * NEO_GEO;
    CHECK(std::abs(t.frames - expected) < 40, "%ld frames, expected about %.0f", t.frames, expected);
    // Told: with the screen again, a frame every other tick.
    run.tellChange = true;
    Ticked told = tick(run);
    CHECK(std::abs(told.frames - 30 * 60) <= 3, "told: %ld frames", told.frames);
}

void soundStaysSteadyAndCrisp() {
    Run run;
    run.lateChance = 0.03;
    Ticked now = tick(run);
    Ticked before = tick(run, true);
    Heard heardNow = listenNew(now, 60, run.length);
    Heard heardBefore = listenOld(before, 60, run.length);
    std::printf("  60 Hz screen, 3%% of frames late, a minute:\n");
    print("before", heardBefore);
    print("now", heardNow);
    CHECK(heardNow.shortBlocks == 0, "%ld short blocks", heardNow.shortBlocks);
    CHECK(heardNow.dropped == 0, "%ld frames dropped", heardNow.dropped);
    CHECK(heardNow.clicks == 0, "%ld clicks (largest jump %.0f)", heardNow.clicks, heardNow.maxJump);
    CHECK(heardNow.wobble < 0.0005, "pitch wobbles %.4f%%", heardNow.wobble * 100);
    CHECK(heardNow.latencyMs > 20 && heardNow.latencyMs < 50, "latency %.0f ms", heardNow.latencyMs);

    // A 120 Hz screen, with the screen at 60 a second.
    Run fast = run;
    fast.screenHz = 120;
    Heard heard120 = listenNew(tick(fast), 60, fast.length);
    print("now, 120 Hz screen", heard120);
    CHECK(heard120.shortBlocks == 0 && heard120.dropped == 0 && heard120.clicks == 0, "120 Hz");

    // 90 Hz: by the clock at the game's own rate.
    Run ninety = run;
    ninety.screenHz = 90;
    Heard heard90 = listenNew(tick(ninety), NEO_GEO, ninety.length);
    print("now, 90 Hz screen", heard90);
    CHECK(heard90.shortBlocks == 0 && heard90.dropped == 0 && heard90.clicks == 0, "90 Hz");
}

void aHoldUpFadesOutAndBackWithoutAClick() {
    Run run;
    run.holdAt = 20;
    run.holdFor = 0.12;   // the game stands still for 120 ms
    Ticked t = tick(run);
    Heard now = listenNew(t, 60, run.length);
    Heard before = listenOld(tick(run, true), 60, run.length);
    std::printf("  a 120 ms hold-up:\n");
    print("before", before);
    print("now", now);
    CHECK(now.clicks == 0, "%ld clicks (largest jump %.0f)", now.clicks, now.maxJump);
    CHECK(now.silentMs < 200, "silent %.0f ms", now.silentMs);
}

}

int main() {
    std::printf("Frame pacing and sound\n");
    framesGoWithTheScreenWhenTheyCan();
    aHundredAndTwentyHertzScreenShowsEveryFrameForTwoTicks();
    aFrameThatRunsLateIsMadeUp();
    aScreenThatSpeedsUpUnsaidDoesntSpeedTheGameUp();
    soundStaysSteadyAndCrisp();
    aHoldUpFadesOutAndBackWithoutAClick();
    if (failures > 0) {
        std::printf("%d failed\n", failures);
        return 1;
    }
    std::printf("All passed\n");
    return 0;
}
