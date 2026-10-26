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

#include <algorithm>

#include "log.h"

#include "audio.h"
#include <cmath>
#include <memory>

namespace libretrodroid {

Audio::Audio(double sampleRate, double refreshRate, bool preferLowLatencyAudio) {
    LOGI("Audio initialization has been called with input sample rate %f", sampleRate);

    this->sampleRate = sampleRate;
    contentRefreshRate = refreshRate;
    audioLatencySettings = findBestLatencySettings(preferLowLatencyAudio);

    // Room for one and a half times the latency asked for, kept at half of
    // it: the game's sound of a frame or two late, or early, never runs it
    // dry or over.
    double framesPerVideoFrame = sampleRate / std::max(refreshRate, 1.0);
    auto videoFrames = (double) audioLatencySettings->bufferSizeInVideoFrames;
    pipe = std::make_unique<AudioPipe>(
        (int32_t) std::lround(framesPerVideoFrame * videoFrames * 1.5),
        (int32_t) std::lround(framesPerVideoFrame * videoFrames * 0.5)
    );
    initializeStream();
}

bool Audio::initializeStream() {
    LOGI("Using low latency stream: %d", audioLatencySettings->useLowLatencyStream);

    oboe::AudioStreamBuilder builder;
    builder.setChannelCount(2);
    builder.setDirection(oboe::Direction::Output);
    builder.setFormat(oboe::AudioFormat::I16);
    builder.setDataCallback(this);
    builder.setErrorCallback(this);

    if (audioLatencySettings->useLowLatencyStream) {
        builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
    } else {
        // A fifth of the latency asked for.
        builder.setFramesPerCallback((int32_t) (sampleRate * audioLatencySettings->bufferSizeInVideoFrames / contentRefreshRate / 5));
    }

    oboe::Result result = builder.openManagedStream(stream);
    if (result == oboe::Result::OK) {
        latencyTuner = std::make_unique<oboe::LatencyTuner>(*stream);
        return true;
    } else {
        LOGE("Failed to create stream. Error: %s", oboe::convertToText(result));
        stream = nullptr;
        latencyTuner = nullptr;
        return false;
    }
}

std::unique_ptr<Audio::AudioLatencySettings> Audio::findBestLatencySettings(bool preferLowLatencyAudio) {
    if (oboe::AudioStreamBuilder::isAAudioRecommended() && preferLowLatencyAudio) {
        return std::make_unique<AudioLatencySettings>(LOW_LATENCY_SETTINGS);
    } else {
        return std::make_unique<AudioLatencySettings>(DEFAULT_LATENCY_SETTINGS);
    }
}

void Audio::start() {
    startRequested = true;
    if (stream != nullptr)
        stream->requestStart();
}

void Audio::stop() {
    startRequested = false;
    if (stream != nullptr)
        stream->requestStop();
}

void Audio::write(const int16_t *data, size_t frames) {
    pipe->write(data, (int32_t) frames);
}

void Audio::setPlaybackSpeed(const double newPlaybackSpeed) {
    playbackSpeed = newPlaybackSpeed;
}

void Audio::setVolume(float newVolume) {
    volume = std::max(0.0f, std::min(1.0f, newVolume));
}

void Audio::setTimeStretch(double factor) {
    if (factor > 0) timeStretch = factor;
}

std::array<int64_t, 2> Audio::getStats() const {
    return {pipe->shortBlocks(), pipe->droppedFrames()};
}

oboe::DataCallbackResult Audio::onAudioReady(oboe::AudioStream *oboeStream, void *audioData, int32_t numFrames) {
    int32_t outputRate = oboeStream->getSampleRate();
    double ratio = sampleRate * timeStretch.load() * playbackSpeed.load() / std::max(outputRate, 1);

    auto outputArray = reinterpret_cast<int16_t *>(audioData);
    pipe->read(outputArray, numFrames, ratio, outputRate);

    // The app's volume (the game's own level at 1).
    float gain = volume.load();
    if (gain < 0.999f) {
        for (int32_t i = 0; i < numFrames * 2; i++) {
            outputArray[i] = (int16_t) (outputArray[i] * gain);
        }
    }

    if (latencyTuner) latencyTuner->tune();

    return oboe::DataCallbackResult::Continue;
}

void Audio::onErrorAfterClose(oboe::AudioStream* oldStream, oboe::Result result) {
    AudioStreamErrorCallback::onErrorAfterClose(oldStream, result);
    LOGI("Stream error in oboe::onErrorAfterClose %s", oboe::convertToText(result));

    if (result != oboe::Result::ErrorDisconnected)
        return;

    initializeStream();
    if (startRequested) {
        start();
    }
}

} //namespace libretrodroid
