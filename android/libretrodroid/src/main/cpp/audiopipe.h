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

#ifndef LIBRETRODROID_AUDIOPIPE_H
#define LIBRETRODROID_AUDIOPIPE_H

#include <atomic>
#include <cstdint>
#include <memory>

namespace libretrodroid {

/**
 * The game's sound on its way to the speaker. The game writes a frame's
 * sound at a time (on its thread, 60 times a second); the audio device
 * reads small blocks (on its own thread, every few milliseconds). In
 * between is a buffer, kept at its target by playing a touch faster or
 * slower (rate control), and the sound is resampled to the device's rate
 * on the way out.
 *
 * Crisp, and never a gap:
 * - Rate control follows the buffer's average fill. The fill itself saws up
 *   and down with every frame's sound; following that wobbled the pitch 60
 *   times a second, a roughness on high notes.
 * - The resampler (cubic) carries its position from block to block.
 * - When the game falls a little behind, the sound that's there is stretched
 *   over the block rather than cut off. If it runs out, it fades out (no
 *   click), and only comes back, fading in, once the buffer has refilled to
 *   its target: one short pause, not a stutter on every frame after.
 */
class AudioPipe {
public:
    // [capacityFrames], [targetFrames]: the game's stereo frames.
    AudioPipe(int32_t capacityFrames, int32_t targetFrames);

    // The game's thread: interleaved stereo. What doesn't fit is dropped (counted).
    void write(const int16_t* data, int32_t frames);

    // The device's thread: [frames] stereo frames into [out] (at [outputRate]),
    // taking the game's sound at [ratio] of its frames per output frame
    // (before rate control).
    void read(int16_t* out, int32_t frames, double ratio, int32_t outputRate);

    int32_t available() const;
    int32_t capacity() const { return capacityFrames; }
    int32_t target() const { return targetFrames; }

    // Since the start: blocks that ran short (stretched, or faded out), and
    // the game's frames dropped for want of room.
    int64_t shortBlocks() const { return shortCount.load(std::memory_order_relaxed); }
    int64_t droppedFrames() const { return dropCount.load(std::memory_order_relaxed); }
    // The rate control's adjustment now (0.01: playing 1% slower).
    double adjustment() const { return lastAdjust.load(std::memory_order_relaxed); }

private:
    bool pop(float* frame);
    void resample(int16_t* out, int32_t frames, double step);

    const int32_t capacityFrames;
    const int32_t targetFrames;
    std::unique_ptr<int16_t[]> ring;
    std::atomic<int64_t> written{0};
    std::atomic<int64_t> taken{0};

    // The device's thread only.
    float history[4][2] = {};   // the game's last four frames: playing between the middle two
    double position = 0.0;      // how far between them
    bool starving = true;       // run out: waiting to refill, silent
    float gain = 0.0f;          // fading in or out
    float last[2] = {};         // the last frame played, to fade out from
    double averageFill = -1.0;
    double integral = 0.0;

    std::atomic<int64_t> shortCount{0};
    std::atomic<int64_t> dropCount{0};
    std::atomic<double> lastAdjust{0.0};
};

}

#endif //LIBRETRODROID_AUDIOPIPE_H
