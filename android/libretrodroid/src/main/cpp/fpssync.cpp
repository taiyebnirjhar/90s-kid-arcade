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

#include <cmath>
#include "fpssync.h"
#include "log.h"

namespace libretrodroid {

FPSSync::FPSSync(double contentRefreshRate, double screenRefreshRate, double timerRate) {
    this->contentRefreshRate = contentRefreshRate;
    this->timerRate = timerRate > 0 ? timerRate : contentRefreshRate;
    choose(screenRefreshRate);
    reset();
}

void FPSSync::choose(double screen) {
    screenRefreshRate = screen;
    ticksPerFrame = 0;
    for (int k = 1; k <= 4 && screen > 0; k++) {
        if (std::abs(screen / k - contentRefreshRate) < FPS_TOLERANCE) {
            ticksPerFrame = k;
            break;
        }
    }
    frameRate = ticksPerFrame > 0 ? screen / ticksPerFrame : timerRate;
    sampleInterval = std::chrono::microseconds((long) (1000000.0 / timerRate));
    LOGI("Frames for a %f fps game on a %f Hz screen: %s, %f a second", contentRefreshRate, screen,
         ticksPerFrame > 0 ? "with the screen" : "by the clock", frameRate);
}

unsigned FPSSync::advanceFrames(TimePoint now) {
    double changed = newScreenRate.exchange(0);
    if (changed > 0 && std::abs(changed - screenRefreshRate) > 0.5) {
        choose(changed);
        reset();
    }

    if (ticksPerFrame == 0) {
        if (lastFrame == MIN_TIME) {
            lastFrame = now;
        }
        if (stretchLeft > 0) {
            stretchLeft--;
            lastFrame += stretchEach;
        }
        long long frames = std::max<long long>((now - lastFrame) / sampleInterval, 1);
        lastFrame = lastFrame + sampleInterval * frames;
        if (frames > 1) caughtUp += 1;
        return (unsigned) frames;
    }

    if (lastTick == MIN_TIME) {
        lastTick = now;
        tickCount = 0;
        return 1;
    }
    double ticks = std::chrono::duration<double>(now - lastTick).count() * screenRefreshRate;
    lastTick = now;

    // Ticks far quicker than the screen's said rate, for half a second: it
    // changed without saying. By the clock until it says.
    quickTicks = ticks < 0.7 ? quickTicks + 1 : 0;
    if (quickTicks > 30) {
        LOGI("The screen ticks faster than %f Hz: frames by the clock", screenRefreshRate);
        ticksPerFrame = 0;
        frameRate = timerRate;
        lastFrame = now;
        return 1;
    }

    // A frame that ran late spans two ticks (or more): counted, so the next
    // step makes it up. A tick that came early (the screen's queue had room
    // after a hold-up) isn't one.
    tickCount += (int) std::lround(ticks);
    int frames = tickCount / ticksPerFrame;
    tickCount -= frames * ticksPerFrame;
    // More than one frame behind: the rest is let go (a long hold-up isn't raced through).
    if (frames > 1) caughtUp += 1;
    return (unsigned) std::min(frames, 2);
}

void FPSSync::reset() {
    lastFrame = MIN_TIME;
    lastTick = MIN_TIME;
    tickCount = 0;
    quickTicks = 0;
    stretchLeft = 0;
}

void FPSSync::stretch(int frames, int over) {
    if (ticksPerFrame > 0 || frames <= 0 || over <= 0) return;
    stretchLeft = over;
    stretchEach = sampleInterval * frames / over;
}

double FPSSync::getTimeStretchFactor() const {
    return frameRate / contentRefreshRate;
}

void FPSSync::wait() {
    if (ticksPerFrame > 0 || lastFrame == MIN_TIME) return;
    std::this_thread::sleep_until(lastFrame);
}

} //namespace libretrodroid
