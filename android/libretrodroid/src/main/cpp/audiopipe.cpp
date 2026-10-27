/*
 *     Copyright (C) 2026  90s Kid Arcade
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#include "audiopipe.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace libretrodroid {

namespace {

// Rate control. The fill, averaged over this long (several frames' sawtooth)...
constexpr double AVERAGE_SECONDS = 0.2;
// ...off its target by a share of it (-1 to 1) plays up to half a percent
// faster or slower at once (under what the ear hears in a pitch)...
constexpr double KP = 0.01;
constexpr double MAX_P = 0.005;
// ...and a lasting difference between the rates (the game's frames against
// the device's clock) up to 1% more, built up over a few seconds.
constexpr double KI = 0.002;
constexpr double MAX_I = 0.01;
// A short block is stretched over its length if at least this share of it is there.
constexpr double MIN_STRETCH = 0.7;
// Fades take this many output frames (under 3 ms).
constexpr float FADE_FRAMES = 128.0f;

// Catmull-Rom: between p1 and p2, at t (0 to 1).
inline float cubic(float p0, float p1, float p2, float p3, float t) {
    float a = -0.5f * p0 + 1.5f * p1 - 1.5f * p2 + 0.5f * p3;
    float b = p0 - 2.5f * p1 + 2.0f * p2 - 0.5f * p3;
    float c = -0.5f * p0 + 0.5f * p2;
    return ((a * t + b) * t + c) * t + p1;
}

inline int16_t clip(float v) {
    return (int16_t) std::lrint(std::max(-32768.0f, std::min(32767.0f, v)));
}

}

AudioPipe::AudioPipe(int32_t capacityFrames, int32_t targetFrames)
    : capacityFrames(std::max(capacityFrames, 4)),
      targetFrames(std::max(1, std::min(targetFrames, std::max(capacityFrames, 4) - 1))),
      ring(new int16_t[(size_t) std::max(capacityFrames, 4) * 2]) {}

int32_t AudioPipe::available() const {
    return (int32_t) (written.load(std::memory_order_acquire) - taken.load(std::memory_order_acquire));
}

void AudioPipe::write(const int16_t* data, int32_t frames) {
    int64_t w = written.load(std::memory_order_relaxed);
    int64_t r = taken.load(std::memory_order_acquire);
    int32_t room = capacityFrames - (int32_t) (w - r);
    int32_t n = std::max(0, std::min(frames, room));
    for (int32_t i = 0; i < n;) {
        int32_t at = (int32_t) ((w + i) % capacityFrames);
        int32_t run = std::min(n - i, capacityFrames - at);
        std::memcpy(&ring[(size_t) at * 2], data + (size_t) i * 2, (size_t) run * 2 * sizeof(int16_t));
        i += run;
    }
    written.store(w + n, std::memory_order_release);
    if (n < frames) dropCount.fetch_add(frames - n, std::memory_order_relaxed);
}

bool AudioPipe::pop(float* frame) {
    int64_t r = taken.load(std::memory_order_relaxed);
    if (written.load(std::memory_order_acquire) <= r) return false;
    int32_t at = (int32_t) (r % capacityFrames);
    frame[0] = ring[(size_t) at * 2];
    frame[1] = ring[(size_t) at * 2 + 1];
    taken.store(r + 1, std::memory_order_release);
    return true;
}

// [frames] output frames, the game's sound advancing [step] frames each.
void AudioPipe::resample(int16_t* out, int32_t frames, double step) {
    for (int32_t i = 0; i < frames; i++) {
        while (position >= 1.0) {
            float next[2];
            if (!pop(next)) {
                // Never short here (read() made sure); stay on the last frame.
                position = 0.999;
                break;
            }
            for (int c = 0; c < 2; c++) {
                history[0][c] = history[1][c];
                history[1][c] = history[2][c];
                history[2][c] = history[3][c];
                history[3][c] = next[c];
            }
            position -= 1.0;
        }
        if (gain < 1.0f) gain = std::min(1.0f, gain + 1.0f / FADE_FRAMES);
        auto t = (float) position;
        for (int c = 0; c < 2; c++) {
            last[c] = cubic(history[0][c], history[1][c], history[2][c], history[3][c], t);
            out[i * 2 + c] = clip(last[c] * gain);
        }
        position += step;
    }
}

void AudioPipe::read(int16_t* out, int32_t frames, double ratio, int32_t outputRate) {
    if (frames <= 0) return;
    double seconds = (double) frames / std::max(outputRate, 1);
    int32_t available = this->available();

    // Rate control, on the fill averaged past each frame's sawtooth.
    if (averageFill < 0) averageFill = available;
    averageFill += (available - averageFill) * (1.0 - std::exp(-seconds / AVERAGE_SECONDS));
    double error = std::clamp((targetFrames - averageFill) / targetFrames, -1.0, 1.0);
    integral = std::clamp(integral + error * seconds, -MAX_I / KI, MAX_I / KI);
    double adjust = std::clamp(KP * error, -MAX_P, MAX_P) + KI * integral;
    lastAdjust.store(adjust, std::memory_order_relaxed);
    double step = std::max(ratio * (1.0 - adjust), 1e-6);

    if (starving) {
        if (available < targetFrames) {
            // Waiting to refill: what was playing fades out, then silence.
            for (int32_t i = 0; i < frames; i++) {
                gain = std::max(0.0f, gain - 1.0f / FADE_FRAMES);
                out[i * 2] = clip(last[0] * gain);
                out[i * 2 + 1] = clip(last[1] * gain);
            }
            return;
        }
        // Refilled: start again from the newest sound there, fading in.
        float first[2];
        pop(first);
        for (int c = 0; c < 2; c++) {
            history[0][c] = first[c];
            history[1][c] = first[c];
        }
        pop(history[2]);
        pop(history[3]);
        position = 0.0;
        gain = 0.0f;
        starving = false;
        available = this->available();
    }

    // The game's frames this block plays through (the last one waits for the next).
    auto need = (int32_t) std::floor(position + (frames - 1) * step);
    if (need <= available) {
        resample(out, frames, step);
        return;
    }
    shortCount.fetch_add(1, std::memory_order_relaxed);
    if (available > 0 && available >= need * MIN_STRETCH && frames > 1) {
        // A little short: what's there, a touch slower, over the whole block.
        resample(out, frames, std::max(0.0, (available - position) / (frames - 1)));
        return;
    }
    // Run out: what's there, then a fade out, and wait to refill.
    int32_t playable = std::clamp((int32_t) std::floor((available - position) / step) + 1, 0, frames);
    if (available - position < 0) playable = 0;
    resample(out, playable, step);
    for (int32_t i = playable; i < frames; i++) {
        gain = std::max(0.0f, gain - 1.0f / FADE_FRAMES);
        out[i * 2] = clip(last[0] * gain);
        out[i * 2 + 1] = clip(last[1] * gain);
    }
    starving = true;
}

}
