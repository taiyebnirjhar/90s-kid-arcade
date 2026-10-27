/*
 *     Copyright (C) 2019  Filippo Scognamiglio
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

#ifndef LIBRETRODROID_FPSSYNC_H
#define LIBRETRODROID_FPSSYNC_H

#include <chrono>
#include <thread>
#include <atomic>
#include <cstdint>

namespace libretrodroid {

typedef std::chrono::steady_clock::time_point TimePoint;
typedef std::chrono::duration<long, std::micro> Duration;

/**
 * When the game's frames run. Each step comes with a tick of the screen
 * (the GL thread is held to the screen's refresh), and says how many frames
 * to run in it: 0, 1, or 2.
 *
 * - With the screen, when its rate is about the game's or a multiple of it
 *   (60 Hz: a frame a tick; 120 Hz: a frame every other tick): every frame
 *   shows for the same time, smooth. A frame that ran late, past a tick, is
 *   made up straight away (two run in the next step), so the game keeps its
 *   pace and its sound never falls behind. (It used to be lost: the sound
 *   ran short of a frame's worth each time, and ran dry.)
 * - Otherwise (90 Hz, 144 Hz) by the clock, at the game's rate, or the one
 *   asked for ([timerRate]: 60 in a multiplayer room).
 * The screen's rate can change while playing (phones switch between 60 and
 * 90 or 120 Hz): the new one is told ([setScreenRefreshRate]), and ticks
 * coming much quicker than the screen's said rate go by the clock.
 */
class FPSSync {
public:
    FPSSync(double contentRefreshRate, double screenRefreshRate, double timerRate = 0);
    ~FPSSync() { }

    void reset();
    unsigned advanceFrames() { return advanceFrames(std::chrono::steady_clock::now()); }
    unsigned advanceFrames(TimePoint now);
    // By the clock: until the next frame is due.
    void wait();
    // What the game's frames run at, over its own rate (its sound goes as much faster).
    double getTimeStretchFactor() const;
    double getFrameRate() const { return frameRate; }
    bool usesVSync() const { return ticksPerFrame > 0; }
    int getTicksPerFrame() const { return ticksPerFrame; }
    // By the clock: the next [over] frames together take [frames] frames longer.
    void stretch(int frames, int over);
    // The screen's rate changed; applied at the next step.
    void setScreenRefreshRate(double rate) { newScreenRate = rate; }
    // Frames run late (made up) so far.
    int64_t getCaughtUp() const { return caughtUp; }
    // By the clock: when the next frame is due.
    TimePoint nextFrameTime() const { return lastFrame; }

private:
    void choose(double screen);

    double screenRefreshRate;
    double contentRefreshRate;
    double timerRate;
    double frameRate = 60.0;
    // 0: by the clock; else a frame every this many screen ticks.
    int ticksPerFrame = 0;
    const double FPS_TOLERANCE = 5;
    const TimePoint MIN_TIME = TimePoint::min();

    // By the clock.
    TimePoint lastFrame = MIN_TIME;
    Duration sampleInterval;
    int stretchLeft = 0;
    Duration stretchEach = Duration(0);

    // With the screen.
    TimePoint lastTick = MIN_TIME;
    int tickCount = 0;
    int quickTicks = 0;

    std::atomic<double> newScreenRate{0};
    std::atomic<int64_t> caughtUp{0};
};

}


#endif //LIBRETRODROID_FPSSYNC_H
