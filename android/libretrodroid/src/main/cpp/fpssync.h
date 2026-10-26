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

namespace libretrodroid {

typedef std::chrono::steady_clock::time_point TimePoint;
typedef std::chrono::duration<long, std::micro> Duration;

class FPSSync {
public:
    // [timerRate]: when frames don't go with the screen's, the rate they
    // go at (0: the game's own).
    FPSSync(double contentRefreshRate, double screenRefreshRate, double timerRate = 0);
    ~FPSSync() { }

    void reset();
    unsigned advanceFrames();
    void wait();
    double getTimeStretchFactor();
    bool usesVSync() const { return useVSync; }
    // Frames by the clock: the next [over] frames together take [frames] frames longer.
    void stretch(int frames, int over);
private:

    double screenRefreshRate;
    double contentRefreshRate;
    double timerRate;
    bool useVSync;
    int stretchLeft = 0;
    Duration stretchEach = Duration(0);
    const double FPS_TOLERANCE = 5;

    const TimePoint MIN_TIME = TimePoint::min();
    void start();

    TimePoint lastFrame = MIN_TIME;
    Duration sampleInterval;
};

}


#endif //LIBRETRODROID_FPSSYNC_H
