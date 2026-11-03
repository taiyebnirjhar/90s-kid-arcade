#ifndef LIBRETRODROID_RECORDER_H
#define LIBRETRODROID_RECORDER_H

#include <cstddef>
#include <cstdint>
#include <utility>
#include <vector>

namespace libretrodroid {

// A checksum of the game's RAM (FNV-1a's step, from the start value phones
// have always used): online play compares it between phones every 2
// seconds, and a saved match keeps it to check its replay. Never change it:
// phones of different versions would stop agreeing.
inline uint64_t ramChecksum(const uint8_t* mem, size_t size) {
    uint64_t hash = 1469598103934665603ULL;
    for (size_t i = 0; i < size; i++) {
        hash ^= mem[i];
        hash *= 1099511628211ULL;
    }
    return hash;
}

// Recording a match (90s Kid Arcade): the game's state at its first frame,
// then every player's input for every frame as the game ran it (after any
// rollback: the frames that count), and the RAM checksums along the way.
// Run again from that state with those inputs, the game plays the same
// match, frame for frame; the checksums prove it. Not thread-safe: the
// emulator calls it under its own locks.
class MatchRecorder {
public:
    enum Status { NONE = 0, RECORDING = 1, ENDED = 2 };

    // A recording no longer than this (3 hours at 60 frames a second) ends by itself.
    static constexpr size_t MAX_FRAMES = 60 * 60 * 60 * 3;

    // From [firstFrame] on, with [ports] players' inputs a frame; whatever was there is dropped.
    void start(int32_t firstFrame, int ports, std::vector<uint8_t> state);

    // The next frame's inputs ([ports] of them). Out of order, or once ended: ignored.
    void addFrame(int32_t frame, const uint16_t* masks);

    // The checksum after the frames before [frame] ran (frame is a multiple
    // of 120 in practice). Only for frames already recorded.
    void addSum(int32_t frame, int64_t sum);

    // Something changed the game outside its inputs (a state loaded, a
    // reset, a cheat, the room started over): what's recorded stays, nothing more is added.
    void end() { if (status == RECORDING) status = ENDED; }

    Status getStatus() const { return status; }
    int32_t nextFrame() const { return first + (int32_t) frames(); }
    size_t frames() const { return ports > 0 ? masks.size() / ports : 0; }

    // Everything recorded, in one block, and the recorder emptied:
    //   "90SKREC1", first frame, ports, frames, sums, state size (each 4
    //   bytes, little-endian), then the inputs (frame by frame, 2 bytes a
    //   port), the sums (4-byte frame, 8-byte sum), then the state.
    // Empty when there's nothing.
    std::vector<uint8_t> take();

private:
    Status status = NONE;
    int32_t first = 0;
    int ports = 0;
    std::vector<uint16_t> masks;
    std::vector<std::pair<int32_t, int64_t>> sums;
    std::vector<uint8_t> state;
};

} //namespace libretrodroid

#endif //LIBRETRODROID_RECORDER_H
