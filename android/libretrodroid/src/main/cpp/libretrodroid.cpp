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

#include <jni.h>

#include <EGL/egl.h>

#include <string>
#include <utility>
#include <vector>
#include <chrono>
#include <unordered_set>

#include "libretrodroid.h"
#include "utils/libretrodroidexception.h"
#include "log.h"
#include "core.h"
#include "audio.h"
#include "video.h"
#include "renderers/renderer.h"
#include "fpssync.h"
#include "input.h"
#include "rumble.h"
#include "shadermanager.h"
#include "utils/javautils.h"
#include "environment.h"
#include "renderers/es3/framebufferrenderer.h"
#include "renderers/es2/imagerendereres2.h"
#include "renderers/es3/imagerendereres3.h"
#include "utils/utils.h"
#include "utils/rect.h"
#include "errorcodes.h"
#include "vfs/vfs.h"

namespace libretrodroid {

uintptr_t LibretroDroid::callback_get_current_framebuffer() {
    return LibretroDroid::getInstance().handleGetCurrentFrameBuffer();
}

void LibretroDroid::callback_hw_video_refresh(
    const void *data,
    unsigned width,
    unsigned height,
    size_t pitch
) {
    LOGD("hw video refresh callback called %i %i", width, height);
    LibretroDroid::getInstance().handleVideoRefresh(data, width, height, pitch);
}

void LibretroDroid::callback_audio_sample(int16_t left, int16_t right) {
    LOGE("callback audio sample (left, right) has been called");
}

size_t LibretroDroid::callback_set_audio_sample_batch(const int16_t *data, size_t frames) {
    return LibretroDroid::getInstance().handleAudioCallback(data, frames);
}

void LibretroDroid::callback_retro_set_input_poll() {
    // Do nothing in here...
}

int16_t LibretroDroid::callback_set_input_state(
    unsigned int port,
    unsigned int device,
    unsigned int index,
    unsigned int id
) {
    return LibretroDroid::getInstance().handleSetInputState(port, device, index, id);
}

void LibretroDroid::updateAudioSampleRateMultiplier() {
    if (audio) {
        audio->setPlaybackSpeed(frameSpeed);
    }
}

// TODO... Do we really need this?
void LibretroDroid::resetGlobalVariables() {
    core = nullptr;
    audio = nullptr;
    video = nullptr;
    fpsSync = nullptr;
    input = nullptr;
    rumble = nullptr;
}

int LibretroDroid::availableDisks() {
    return Environment::getInstance().getRetroDiskControlCallback() != nullptr
           ? Environment::getInstance().getRetroDiskControlCallback()->get_num_images()
           : 0;
}

int LibretroDroid::currentDisk() {
    return Environment::getInstance().getRetroDiskControlCallback() != nullptr
           ? Environment::getInstance().getRetroDiskControlCallback()->get_image_index()
           : 0;
}

void LibretroDroid::changeDisk(unsigned int index) {
    if (Environment::getInstance().getRetroDiskControlCallback() == nullptr) {
        LOGE("Cannot swap disk. This platform does not support it.");
        return;
    }

    if (index < 0 || index >= Environment::getInstance().getRetroDiskControlCallback()->get_num_images()) {
        LOGE("Requested image index is not valid.");
        return;
    }

    if (Environment::getInstance().getRetroDiskControlCallback()->get_image_index() != index) {
        Environment::getInstance().getRetroDiskControlCallback()->set_eject_state(true);
        Environment::getInstance().getRetroDiskControlCallback()->set_image_index((unsigned) index);
        Environment::getInstance().getRetroDiskControlCallback()->set_eject_state(false);
    }
}

void LibretroDroid::updateVariable(const Variable& variable) {
    Environment::getInstance().updateVariable(variable.key, variable.value);
}

std::vector<Variable> LibretroDroid::getVariables() {
    return Environment::getInstance().getVariables();
}

std::vector<std::vector<struct Controller>> LibretroDroid::getControllers() {
    return Environment::getInstance().getControllers();
}

void LibretroDroid::setControllerType(unsigned int port, unsigned int type) {
    core->retro_set_controller_port_device(port, type);
}

bool LibretroDroid::unserializeState(int8_t *data, size_t size) {
    std::lock_guard<std::mutex> lock(coreLock);

    return core->retro_unserialize(data, size);
}

JNIEXPORT jboolean JNICALL LibretroDroid::unserializeSRAM(int8_t* data, size_t size) {
    std::lock_guard<std::mutex> lock(coreLock);

    size_t sramSize = core->retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    void *sramState = core->retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);

    if (sramState == nullptr) {
        LOGE("Cannot load SRAM: nullptr in retro_get_memory_data");
        return false;
    }

    if (size > sramSize) {
        LOGE("Cannot load SRAM: size mismatch");
        return false;
    }

    memcpy(sramState, data, size);

    return true;
}

size_t LibretroDroid::getMemorySize(unsigned id) {
    std::lock_guard<std::mutex> lock(coreLock);
    if (!core) return 0;
    return core->retro_get_memory_size(id);
}

bool LibretroDroid::readMemory(unsigned id, size_t offset, int8_t* dst, size_t length) {
    // Taking coreLock keeps this from overlapping retro_run or destroy().
    std::lock_guard<std::mutex> lock(coreLock);
    if (!core) return false;
    auto* mem = (int8_t*) core->retro_get_memory_data(id);
    size_t size = core->retro_get_memory_size(id);
    if (mem == nullptr || offset > size || length > size - offset) return false;
    memcpy(dst, mem + offset, length);
    return true;
}

std::vector<int32_t> LibretroDroid::grabFrame() {
    std::lock_guard<std::mutex> lock(grabLock);
    std::vector<int32_t> result;
    result.swap(grabbed);
    grabWanted = true;
    return result;
}

void LibretroDroid::grab(const void* data, unsigned width, unsigned height, size_t pitch) {
    std::lock_guard<std::mutex> lock(grabLock);
    grabWanted = false;
    grabbed.resize(2 + (size_t) width * height);
    grabbed[0] = (int32_t) width;
    grabbed[1] = (int32_t) height;
    int format = Environment::getInstance().getPixelFormat();
    for (unsigned y = 0; y < height; y++) {
        auto* row = (const uint8_t*) data + y * pitch;
        int32_t* out = grabbed.data() + 2 + (size_t) y * width;
        for (unsigned x = 0; x < width; x++) {
            uint32_t r, g, b;
            if (format == RETRO_PIXEL_FORMAT_XRGB8888) {
                uint32_t p = ((const uint32_t*) row)[x];
                r = (p >> 16) & 0xFF; g = (p >> 8) & 0xFF; b = p & 0xFF;
            } else {
                uint32_t p = ((const uint16_t*) row)[x];
                if (format == RETRO_PIXEL_FORMAT_RGB565) {
                    r = (p >> 11) & 0x1F; g = (p >> 5) & 0x3F; b = p & 0x1F;
                    g = (g << 2) | (g >> 4);
                } else {
                    r = (p >> 10) & 0x1F; g = (p >> 5) & 0x1F; b = p & 0x1F;
                    g = (g << 3) | (g >> 2);
                }
                r = (r << 3) | (r >> 2);
                b = (b << 3) | (b >> 2);
            }
            out[x] = (int32_t) (0xFF000000u | (r << 16) | (g << 8) | b);
        }
    }
}

std::pair<int8_t*, size_t> LibretroDroid::serializeSRAM() {
    std::lock_guard<std::mutex> lock(coreLock);

    size_t size = core->retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    auto* data = new int8_t[size];
    memcpy(data, (int8_t*) core->retro_get_memory_data(RETRO_MEMORY_SAVE_RAM), size);

    return std::pair(data, size);
}

void LibretroDroid::onSurfaceChanged(unsigned int width, unsigned int height) {
    LOGD("Performing libretrodroid onSurfaceChanged");
    video->updateScreenSize(width, height);
}

void LibretroDroid::onSurfaceCreated() {
    LOGD("Performing libretrodroid onSurfaceCreated");

    struct retro_system_av_info system_av_info {};
    core->retro_get_system_av_info(&system_av_info);

    video = nullptr;

    Video::RenderingOptions renderingOptions {
        Environment::getInstance().isUseHwAcceleration(),
        system_av_info.geometry.base_width,
        system_av_info.geometry.base_height,
        Environment::getInstance().isUseDepth(),
        Environment::getInstance().isUseStencil(),
        openglESVersion,
        Environment::getInstance().getPixelFormat()
    };

    auto newVideo = new Video(
        renderingOptions,
        fragmentShaderConfig,
        Environment::getInstance().isBottomLeftOrigin(),
        Environment::getInstance().getScreenRotation(),
        skipDuplicateFrames,
        immersiveModeEnabled,
        viewportRect,
        immersiveModeConfig,
        viewportAlignment
    );

    video = std::unique_ptr<Video>(newVideo);

    if (Environment::getInstance().getHwContextReset() != nullptr) {
        Environment::getInstance().getHwContextReset()();
    }
}

void LibretroDroid::onMotionEvent(
    unsigned int port,
    unsigned int source,
    float xAxis,
    float yAxis
) {
    LOGD("Received motion event: %d %.2f, %.2f", source, xAxis, yAxis);
    if (input) {
        input->onMotionEvent(port, source, xAxis, yAxis);
    }
}

void LibretroDroid::onTouchEvent(float xAxis, float yAxis) {
    LOGD("Received touch event: %.2f, %.2f", xAxis, yAxis);
    if (input && video) {
        auto [x, y] = video->getLayout().getRelativePosition(xAxis, yAxis);
        input->onMotionEvent(0, Input::MOTION_SOURCE_POINTER, x, y);
    }
}

void LibretroDroid::onKeyEvent(unsigned int port, int action, int keyCode) {
    LOGD("Received key event with action (%d) and keycode (%d)", action, keyCode);
    if (input) {
        input->onKeyEvent(port, action, keyCode);
    }
}

void LibretroDroid::create(
    unsigned int GLESVersion,
    const std::string& soFilePath,
    const std::string& systemDir,
    const std::string& savesDir,
    std::vector<Variable> variables,
    const ShaderManager::Config& shaderConfig,
    float refreshRate,
    bool lowLatencyAudio,
    bool enableVirtualFileSystem,
    bool enableMicrophone,
    bool duplicateFrames,
    std::optional<ImmersiveMode::Config> immersiveModeConfig,
    const std::string& language
) {
    LOGD("Performing libretrodroid create");

    resetGlobalVariables();

    Environment::getInstance().initialize(systemDir, savesDir, &callback_get_current_framebuffer);
    Environment::getInstance().setLanguage(language);
    Environment::getInstance().setEnableVirtualFileSystem(enableVirtualFileSystem);
    Environment::getInstance().setEnableMicrophone(enableMicrophone);

    openglESVersion = GLESVersion;
    screenRefreshRate = refreshRate;
    newScreenRefreshRate = 0;
    skipDuplicateFrames = duplicateFrames;
    immersiveModeEnabled = GLESVersion >= 3 && immersiveModeConfig.has_value();
    this->immersiveModeConfig = immersiveModeConfig.value_or(ImmersiveMode::Config{});
    audioEnabled = true;
    frameSpeed = 1;

    core = std::make_unique<Core>(soFilePath);

    core->retro_set_video_refresh(&callback_hw_video_refresh);
    core->retro_set_environment(&Environment::callback_environment);
    core->retro_set_audio_sample(&callback_audio_sample);
    core->retro_set_audio_sample_batch(&callback_set_audio_sample_batch);
    core->retro_set_input_poll(&callback_retro_set_input_poll);
    core->retro_set_input_state(&callback_set_input_state);

    std::for_each(variables.begin(), variables.end(), [&](const Variable& v) {
        updateVariable(v);
    });

    core->retro_init();

    preferLowLatencyAudio = lowLatencyAudio;

    // HW accelerated cores are only supported on opengles 3.
    if (Environment::getInstance().isUseHwAcceleration() && openglESVersion < 3) {
        throw LibretroDroidError("OpenGL ES 3 is required for this Core", ERROR_GL_NOT_COMPATIBLE);
    }

    fragmentShaderConfig = shaderConfig;

    rumble = std::make_unique<Rumble>();
}

void LibretroDroid::loadGameFromPath(const std::string& gamePath) {
    LOGD("Performing libretrodroid loadGameFromPath");
    struct retro_system_info system_info {};
    core->retro_get_system_info(&system_info);

    struct retro_game_info game_info {};
    game_info.path = Utils::cloneToCString(gamePath);
    game_info.meta = nullptr;

    if (system_info.need_fullpath) {
        game_info.data = nullptr;
        game_info.size = 0;
    } else {
        struct Utils::ReadResult file = Utils::readFileAsBytes(gamePath);
        game_info.data = file.data;
        game_info.size = file.size;
    }

    bool result = core->retro_load_game(&game_info);
    if (!result) {
        LOGE("Cannot load game. Leaving.");
        throw std::runtime_error("Cannot load game");
    }

    afterGameLoad();
}

void LibretroDroid::loadGameFromBytes(const int8_t *data, size_t size) {
    LOGD("Performing libretrodroid loadGameFromBytes");

    struct retro_system_info system_info {};
    core->retro_get_system_info(&system_info);

    struct retro_game_info game_info {};
    game_info.path = nullptr;
    game_info.meta = nullptr;

    if (system_info.need_fullpath) {
        game_info.data = nullptr;
        game_info.size = 0;
    } else {
        game_info.data = data;
        game_info.size = size;
    }

    bool result = core->retro_load_game(&game_info);
    if (!result) {
        LOGE("Cannot load game. Leaving.");
        throw std::runtime_error("Cannot load game");
    }

    afterGameLoad();
}

void LibretroDroid::loadGameFromVirtualFiles(std::vector<VFSFile> virtualFiles) {
    LOGD("Performing libretrodroid loadGameFromVirtualFiles");
    struct retro_system_info system_info {};
    core->retro_get_system_info(&system_info);

    if (virtualFiles.empty()) {
        LOGE("Calling loadGameFromVirtualFiles without any file.");
        throw std::runtime_error("Calling loadGameFromVirtualFiles without any file.");
    }

    std::string firstFilePath = virtualFiles[0].getFileName();
    int firstFileFD = virtualFiles[0].getFD();

    bool loadUsingVFS = system_info.need_fullpath || virtualFiles.size() > 1;

    struct retro_game_info game_info {};
    game_info.path = Utils::cloneToCString(firstFilePath);
    game_info.meta = nullptr;

    if (loadUsingVFS) {
        VFS::getInstance().initialize(std::move(virtualFiles));
    }

    if (loadUsingVFS) {
        game_info.data = nullptr;
        game_info.size = 0;
    } else {
        struct Utils::ReadResult file = Utils::readFileAsBytes(firstFileFD);
        game_info.data = file.data;
        game_info.size = file.size;
    }

    bool result = core->retro_load_game(&game_info);
    if (!result) {
        LOGE("Cannot load game. Leaving.");
        throw std::runtime_error("Cannot load game");
    }

    afterGameLoad();
}

void LibretroDroid::destroy() {
    std::lock_guard<std::mutex> lock(coreLock);

    LOGD("Performing libretrodroid destroy");

    if (Environment::getInstance().getHwContextDestroy() != nullptr) {
        Environment::getInstance().getHwContextDestroy()();
    }

    core->retro_unload_game();
    core->retro_deinit();

    video = nullptr;
    core = nullptr;
    rumble = nullptr;
    fpsSync = nullptr;
    audio = nullptr;

    Environment::getInstance().deinitialize();
    VFS::getInstance().deinitialize();
}

void LibretroDroid::resume() {
    LOGD("Performing libretrodroid resume");

    input = std::make_unique<Input>();

    fpsSync->reset();
    audio->start();
    refreshAspectRatio();
}

void LibretroDroid::pause() {
    LOGD("Performing libretrodroid pause");
    audio->stop();

    input = nullptr;
}

void LibretroDroid::step() {
    std::lock_guard<std::mutex> lock(coreLock);
    auto started = std::chrono::steady_clock::now();

    LOGD("Stepping into retro_run()");

    float screenRate = newScreenRefreshRate.exchange(0);
    if (screenRate > 0 && fpsSync) fpsSync->setScreenRefreshRate(screenRate);

    if (frozen) {
        if (video) video->renderFrame();
        // The pace goes on (a frame's time, not run), so it's not behind when it's back.
        unsigned due = fpsSync ? fpsSync->advanceFrames() : 1;
        stepFrames = due > 0 ? 1 : 0;
        frameWorkNanos = std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - started).count();
        if (fpsSync) fpsSync->wait();
        return;
    }

    unsigned frames = 1;
    if (fpsSync) {
        unsigned requestedFrames = fpsSync->advanceFrames();

        // If the application runs too slow it's better to just skip those frames.
        frames = std::min(requestedFrames, 2u);

        // The frames' rate changed (the screen's did): the sound follows.
        if (audio && fpsSync->getFrameRate() != appliedFrameRate) {
            appliedFrameRate = fpsSync->getFrameRate();
            audio->setTimeStretch(fpsSync->getTimeStretchFactor());
        }
    }
    stepFrames = (int) frames;

    if (netplay) {
        stepNetplay(frames);
        // For the connection log: a frame with many replayed is a hitch.
        auto took = std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - started).count();
        std::lock_guard<std::mutex> netLock(netMutex);
        netSlowestFrameUs = std::max(netSlowestFrameUs, (int64_t) took);
    } else {
        for (size_t i = 0; i < frames * frameSpeed; i++)
            core->retro_run();
    }

    if (video && !video->rendersInVideoCallback()) {
        video->renderFrame();
    }

    // The frame's work, without the wait for the next one.
    frameWorkNanos = std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - started).count();

    if (fpsSync) {
        fpsSync->wait();
    }

    if (rumble && rumbleEnabled) {
        rumble->fetchFromEnvironment();
    }

    // Some games override the core geometry at runtime. These fields get updated in retro_run().
    if (video && Environment::getInstance().isGameGeometryUpdated()) {
        Environment::getInstance().clearGameGeometryUpdated();

        video->updateRendererSize(
            Environment::getInstance().getGameGeometryWidth(),
            Environment::getInstance().getGameGeometryHeight()
        );

        dirtyVideo = true;
    }

    if (video && Environment::getInstance().isScreenRotationUpdated()) {
        Environment::getInstance().clearScreenRotationUpdated();

        video->updateRotation(Environment::getInstance().getScreenRotation());
    }
}

float LibretroDroid::getAspectRatio() {
    float gameAspectRatio = Environment::getInstance().retrieveGameSpecificAspectRatio();
    return gameAspectRatio > 0 ? gameAspectRatio : defaultAspectRatio;
}

void LibretroDroid::refreshAspectRatio() {
    video->updateAspectRatio(getAspectRatio());
}

void LibretroDroid::setRumbleEnabled(bool enabled) {
    rumbleEnabled = enabled;
}

bool LibretroDroid::isRumbleEnabled() const {
    return rumbleEnabled;
}

void LibretroDroid::setFrameSpeed(unsigned int speed) {
    frameSpeed = speed;
    updateAudioSampleRateMultiplier();
}

void LibretroDroid::setAudioEnabled(bool enabled) {
    audioEnabled = enabled;
}

void LibretroDroid::setVolume(float newVolume) {
    volume = newVolume;
    if (audio) audio->setVolume(newVolume);
}

void LibretroDroid::setShaderConfig(ShaderManager::Config shaderConfig) {
    fragmentShaderConfig = std::move(shaderConfig);
    if (video) {
        video->updateShaderType(fragmentShaderConfig);
    }
}

void LibretroDroid::handleVideoRefresh(
    const void *data,
    unsigned int width,
    unsigned int height,
    size_t pitch
) {
    if (grabWanted && data != nullptr && data != RETRO_HW_FRAME_BUFFER_VALID) {
        grab(data, width, height, pitch);
    }
    if (video) {
        video->onNewFrame(data, width, height, pitch);

        if (video->rendersInVideoCallback()) {
            video->renderFrame();
        }
    }
}

size_t LibretroDroid::handleAudioCallback(const int16_t *data, size_t frames) {
    if (audio && audioEnabled) {
        audio->write(data, frames);
    }
    return frames;
}

int16_t LibretroDroid::handleSetInputState(
    unsigned int port,
    unsigned int device,
    unsigned int index,
    unsigned int id
) {
    if (netplay) {
        if (device != RETRO_DEVICE_JOYPAD || port >= (unsigned) netPlayers) return 0;
        if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return (int16_t) netMask[port];
        return (netMask[port] >> id) & 1;
    }
    if (input) {
        return input->getInputState(port, device, index, id);
    }
    return 0;
}

uintptr_t LibretroDroid::handleGetCurrentFrameBuffer() {
    if (video) {
        return video->getCurrentFramebuffer();
    }
    return 0;
}

void LibretroDroid::reset() {
    std::lock_guard<std::mutex> lock(coreLock);

    core->retro_reset();
}

std::pair<int8_t*, size_t> LibretroDroid::serializeState() {
    std::lock_guard<std::mutex> lock(coreLock);

    size_t size = core->retro_serialize_size();
    auto data = new int8_t[size];

    core->retro_serialize(data, size);

    return std::pair(data, size);
}

void LibretroDroid::resetCheat() {
    std::lock_guard<std::mutex> lock(coreLock);

    core->retro_cheat_reset();
}

void LibretroDroid::setCheat(unsigned index, bool enabled, const std::string& code) {
    std::lock_guard<std::mutex> lock(coreLock);

    core->retro_cheat_set(index, enabled, Utils::cloneToCString(code));
}

bool LibretroDroid::requiresVideoRefresh() const {
    return dirtyVideo;
}

void LibretroDroid::clearRequiresVideoRefresh() {
    dirtyVideo = false;
}

void LibretroDroid::afterGameLoad() {
    struct retro_system_av_info system_av_info {};
    core->retro_get_system_av_info(&system_av_info);

    // Multiplayer: every phone at the same pace, or the faster one keeps
    // waiting for the others. Phones whose screens run at about 60 Hz go
    // with the screen; on others (90, 120 Hz…) frames go by the clock, at 60
    // a second too, rather than the game's own (59.19 for the Neo Geo).
    bool inRoom;
    {
        std::lock_guard<std::mutex> lock(netMutex);
        inRoom = netplay;
    }
    fpsSync = std::make_unique<FPSSync>(system_av_info.timing.fps, screenRefreshRate, inRoom ? NET_FRAME_RATE : 0);

    audio = std::make_unique<Audio>(
        system_av_info.timing.sample_rate,
        system_av_info.timing.fps,
        preferLowLatencyAudio
    );
    appliedFrameRate = fpsSync->getFrameRate();
    audio->setTimeStretch(fpsSync->getTimeStretchFactor());
    audio->setVolume(volume);

    updateAudioSampleRateMultiplier();

    defaultAspectRatio = findDefaultAspectRatio(system_av_info);
}

float LibretroDroid::findDefaultAspectRatio(const retro_system_av_info& system_av_info) {
    float result = system_av_info.geometry.aspect_ratio;
    if (result < 0) {
        result =
            (float) system_av_info.geometry.base_width / (float) system_av_info.geometry.base_height;
    }
    return result;
}

void LibretroDroid::handleRumbleUpdates(const std::function<void(int, float, float)> &handler) {
    if (rumble && rumbleEnabled) {
        rumble->handleRumbleUpdates(handler);
    }
}

void LibretroDroid::setViewport(Rect viewportRect) {
    this->viewportRect = viewportRect;

    if (video != nullptr) {
        video->updateViewportSize(viewportRect);
    }
}

void LibretroDroid::setViewportAlignment(unsigned int viewportAlignment) {
    this-> viewportAlignment = viewportAlignment;

    if (video) {
        this->video->updateViewportAlignment(viewportAlignment);
    }
}

void LibretroDroid::setNetplay(bool enabled, int localPlayer, int players, int maxRollback, int activeMask) {
    std::lock_guard<std::mutex> lock(netMutex);
    netplay = enabled;
    netPlayers = std::max(2, std::min(players, NET_MAX_PLAYERS));
    // -1: this phone watches; it has no input of its own.
    netLocalPlayer = localPlayer < 0 ? -1 : std::min(localPlayer, netPlayers - 1);
    netActive = (uint32_t) activeMask & ((1u << netPlayers) - 1);
    if (netLocalPlayer >= 0) netActive |= 1u << netLocalPlayer;
    netMaxRollback = std::max(0, std::min(maxRollback, NET_RING - 8));
    netFrame = 0;
    for (int p = 0; p < NET_MAX_PLAYERS; p++) {
        netKnown[p] = 0;
        netMask[p] = 0;
    }
    netFirstWrong = INT32_MAX;
    netSum = {-1, 0};
    netPendingSums.clear();
    netReplayed = 0;
    netRollbacks = 0;
    netStates.clear();
    netWaitFrames = 0;
    Environment::getInstance().audioVideoOverride = -1;
}

int32_t LibretroDroid::netRemoteKnown() const {
    int32_t known = INT32_MAX;
    for (int p = 0; p < netPlayers; p++) {
        // An empty seat is never waited for.
        if (p != netLocalPlayer && (netActive & (1u << p))) known = std::min(known, netKnown[p]);
    }
    return known;
}

bool LibretroDroid::pushLocalInput(int32_t frame, int32_t mask) {
    std::lock_guard<std::mutex> lock(netMutex);
    if (!netplay || netLocalPlayer < 0) return false;
    // In order, and not so far ahead that the ring would wrap.
    int32_t& known = netKnown[netLocalPlayer];
    if (frame != known || frame >= netFrame + NET_RING / 2) return false;
    netInput[netLocalPlayer][frame % NET_INPUT_RING] = (uint16_t) mask;
    known++;
    return true;
}

bool LibretroDroid::pushRemoteInput(int player, int32_t frame, int32_t mask) {
    std::lock_guard<std::mutex> lock(netMutex);
    if (!netplay || player < 0 || player >= netPlayers || player == netLocalPlayer) return false;
    if (!(netActive & (1u << player))) return false;
    if (frame != netKnown[player]) return false;
    // A watcher's game fallen a whole ring behind: it'll be put back in step.
    if (frame >= netFrame + NET_INPUT_RING - 1) return false;
    netInput[player][frame % NET_INPUT_RING] = (uint16_t) mask;
    // Already run with a wrong guess: replay from there on the next step.
    if (frame < netFrame && netUsed[player][frame % NET_RING] != (uint16_t) mask) {
        netFirstWrong = std::min(netFirstWrong, frame);
    }
    netKnown[player]++;
    return true;
}

int32_t LibretroDroid::getNetFrame() {
    std::lock_guard<std::mutex> lock(netMutex);
    return netFrame;
}

std::array<int64_t, 2> LibretroDroid::getNetSum() {
    std::lock_guard<std::mutex> lock(netMutex);
    return netSum;
}

std::pair<int32_t, std::vector<uint8_t>> LibretroDroid::getNetState() {
    // The order step() takes them in.
    std::lock_guard<std::mutex> coreGuard(coreLock);
    std::lock_guard<std::mutex> lock(netMutex);
    int32_t frame = std::min(std::min(netRemoteKnown(), netFirstWrong), netFrame);
    if (frame == netFrame) {
        // Nothing run on a guess: the game as it is now.
        std::vector<uint8_t> state(core->retro_serialize_size());
        core->retro_serialize(state.data(), state.size());
        return {frame, std::move(state)};
    }
    // The state saved before running [frame] (replays save it again).
    if (frame <= netFrame - NET_RING || netStates.size() != NET_RING) return {frame, {}};
    return {frame, netStates[frame % NET_RING]};
}

void LibretroDroid::netStartAt(int32_t frame) {
    std::lock_guard<std::mutex> lock(netMutex);
    netFrame = frame;
    for (int p = 0; p < NET_MAX_PLAYERS; p++) netKnown[p] = frame;
    netFirstWrong = INT32_MAX;
    netSum = {-1, 0};
    netPendingSums.clear();
}

void LibretroDroid::netWait(int frames) {
    std::lock_guard<std::mutex> lock(netMutex);
    frames = std::min(frames, 4);
    // Frames by the clock: a little slower for a while, unseen. With the
    // screen: frames held (the screen's next tick can't come later).
    if (fpsSync && !fpsSync->usesVSync()) {
        fpsSync->stretch(frames, NET_STRETCH_FRAMES);
    } else {
        netWaitFrames = std::max(netWaitFrames, frames);
    }
}

void LibretroDroid::setScreenRefreshRate(float rate) {
    screenRefreshRate = rate;
    newScreenRefreshRate = rate;
}

std::array<int64_t, 4> LibretroDroid::getPlaybackStats() {
    std::lock_guard<std::mutex> lock(coreLock);
    auto sound = audio ? audio->getStats() : std::array<int64_t, 2>{0, 0};
    return {
        fpsSync ? fpsSync->getCaughtUp() : 0,
        sound[0],
        sound[1],
        fpsSync ? (int64_t) std::lround(fpsSync->getFrameRate() * 100) * 10 + fpsSync->getTicksPerFrame() : 0,
    };
}

std::array<int64_t, 3> LibretroDroid::getNetStats() {
    std::lock_guard<std::mutex> lock(netMutex);
    int64_t slowest = netSlowestFrameUs;
    netSlowestFrameUs = 0;
    return {netReplayed, netRollbacks, slowest};
}

// Runs one frame: saves the state before it if it runs on a guess (to come
// back to), applies this phone's input and the partner's (or the guess), and
// notes a checksum to compare once the frame's inputs are all real. With
// netMutex held.
void LibretroDroid::netSimulate(int32_t frame, bool replay) {
    // Only a frame run on a guess can be gone back to. Every input in (most
    // frames, on a good connection): no state to save, a frame that much quicker.
    if (frame >= netRemoteKnown()) {
        size_t size = core->retro_serialize_size();
        auto& state = netStates[frame % NET_RING];
        if (state.size() != size) state.resize(size);
        core->retro_serialize(state.data(), size);
    }

    // Each player's input for the frame, or their last one as the guess.
    for (int p = 0; p < netPlayers; p++) {
        uint16_t mask = 0;
        if (!(netActive & (1u << p))) {
            // An empty seat: nobody's pressing anything.
        } else if (frame < netKnown[p]) {
            mask = netInput[p][frame % NET_INPUT_RING];
        } else if (netKnown[p] > 0) {
            mask = netInput[p][(netKnown[p] - 1) % NET_INPUT_RING];
        }
        netUsed[p][frame % NET_RING] = mask;
        netMask[p] = mask;
    }

    // Replays are silent and undrawn; only the newest frame is seen and heard.
    Environment::getInstance().audioVideoOverride = replay ? 4 : -1;
    core->retro_run();
    Environment::getInstance().audioVideoOverride = -1;

    // Every 2 seconds, a checksum of the game's RAM (FNV-1a) for the partner
    // to compare: kept until the frame's inputs are confirmed.
    if ((frame + 1) % 120 == 0) {
        auto* mem = (uint8_t*) core->retro_get_memory_data(RETRO_MEMORY_SYSTEM_RAM);
        size_t memSize = core->retro_get_memory_size(RETRO_MEMORY_SYSTEM_RAM);
        if (mem != nullptr) {
            uint64_t hash = 1469598103934665603ULL;
            for (size_t j = 0; j < memSize; j++) {
                hash ^= mem[j];
                hash *= 1099511628211ULL;
            }
            bool found = false;
            for (auto& p : netPendingSums) {
                if (p.first == frame + 1) {
                    p.second = (int64_t) hash;
                    found = true;
                }
            }
            if (!found) netPendingSums.emplace_back(frame + 1, (int64_t) hash);
        }
    }
}

// Called from step() with coreLock held. Replays from the first wrong guess
// if there is one, then runs up to [wanted] new frames (what the display
// pacing asks for), each as far as the inputs allow.
void LibretroDroid::stepNetplay(unsigned wanted) {
    std::lock_guard<std::mutex> lock(netMutex);
    if (netStates.size() != NET_RING) netStates.resize(NET_RING);

    if (netFirstWrong < netFrame) {
        netRollbacks++;
        auto& state = netStates[netFirstWrong % NET_RING];
        core->retro_unserialize(state.data(), state.size());
        for (int32_t f = netFirstWrong; f < netFrame; f++) {
            netSimulate(f, true);
            netReplayed++;
        }
    }
    netFirstWrong = INT32_MAX;

    // Frames below netRemoteKnown() have now run with the real inputs.
    int32_t remoteKnown = netRemoteKnown();
    for (auto it = netPendingSums.begin(); it != netPendingSums.end();) {
        if (it->first - 1 < remoteKnown) {
            if (it->first > netSum[0]) netSum = {it->first, it->second};
            it = netPendingSums.erase(it);
        } else {
            ++it;
        }
    }

    // A wait holds frames that were due (a step between frames, on a 120 Hz
    // screen, has none to hold).
    while (netWaitFrames > 0 && wanted > 0) {
        netWaitFrames--;
        wanted--;
    }
    if (wanted == 0) return;
    if (netLocalPlayer < 0) {
        // Watching: only frames whose inputs are all in (no guessing, so
        // nothing to replay), catching up when they've piled up.
        if ((int64_t) remoteKnown - netFrame > NET_WATCH_FAR) {
            // Far behind (just in, or put back in step): straight to the
            // players' moment, silent and undrawn (the app covers it with
            // "Joining…"), as much as a few milliseconds a frame allow.
            auto until = std::chrono::steady_clock::now() + std::chrono::milliseconds(NET_WATCH_CATCH_UP_MS);
            while ((int64_t) remoteKnown - netFrame > NET_WATCH_NEAR && std::chrono::steady_clock::now() < until) {
                netSimulate(netFrame, true);
                netFrame++;
            }
        }
        if ((int64_t) remoteKnown - netFrame > 8) wanted++;
        for (unsigned i = 0; i < wanted && (int64_t) netFrame < (int64_t) remoteKnown; i++) {
            netSimulate(netFrame, false);
            netFrame++;
        }
        return;
    }
    // Catch up a frame when this phone is behind its own input.
    int32_t localKnown = netKnown[netLocalPlayer];
    if (localKnown - netFrame > 4) wanted++;
    for (unsigned i = 0; i < wanted; i++) {
        if (netFrame >= localKnown) return;                     // our input isn't sampled yet
        // Too far ahead of the slowest partner (none: alone in the room).
        if ((int64_t) netFrame >= (int64_t) remoteKnown + netMaxRollback) return;
        netSimulate(netFrame, false);
        netFrame++;
    }
}

} //namespace libretrodroid
