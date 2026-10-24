"""Minimal headless libretro front end for FBNeo: run frames, press buttons,
take screenshots, read 68K RAM, save/load states. Used to check the KOF RAM
map (docs/kof-ram.md) against the real games.

Layout it expects (nothing here is committed: bring your own core and ROMs):
  $EMU/fbneo_libretro.so   buildbot.libretro.com/nightly/linux/x86_64/latest/fbneo_libretro.so.zip
  $EMU/roms/neogeo.zip, $EMU/roms/<set>.zip
  $EMU/shots/              screenshots

  from emu import Emu
  e = Emu("kof97"); e.run(900); e.tap("COIN"); e.tap("START")
  e.byte(0x108171)         # P1's fighter
"""
import ctypes as C
import os
from PIL import Image

EMU = os.environ.get("EMU", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "emu"))
# RetroPad ids; FBNeo's Neo Geo map: A=B(0) B=A(8) C=Y(1) D=X(9)
PAD = {"A": 0, "B": 8, "C": 1, "D": 9, "COIN": 2, "START": 3, "UP": 4, "DOWN": 5, "LEFT": 6, "RIGHT": 7}
# Capcom six buttons as the app's A-F (PadMap.CAPCOM_6): LP MP HP LK MK HK.
CAPCOM_6 = dict(PAD, A=1, B=9, C=10, D=0, E=8, F=11)

ENV_CB = C.CFUNCTYPE(C.c_bool, C.c_uint, C.c_void_p)
VIDEO_CB = C.CFUNCTYPE(None, C.c_void_p, C.c_uint, C.c_uint, C.c_size_t)
AUDIO_CB = C.CFUNCTYPE(None, C.c_int16, C.c_int16)
AUDIO_BATCH_CB = C.CFUNCTYPE(C.c_size_t, C.c_void_p, C.c_size_t)
POLL_CB = C.CFUNCTYPE(None)
STATE_CB = C.CFUNCTYPE(C.c_int16, C.c_uint, C.c_uint, C.c_uint, C.c_uint)


class GameInfo(C.Structure):
    _fields_ = [("path", C.c_char_p), ("data", C.c_void_p), ("size", C.c_size_t), ("meta", C.c_char_p)]


class Emu:
    def __init__(self, game):
        # Street Fighter III (CPS-3): SH-2 RAM from 0x02000000 in 32-bit words.
        cps3 = game.startswith("sfiii")
        self.pad = CAPCOM_6 if cps3 else PAD
        self.base, self.swap = (0x02000000, 3) if cps3 else (0x100000, 1)
        self.lib = C.CDLL(os.path.join(EMU, "fbneo_libretro.so"))
        self.fmt = 0
        self.frame = None
        self.held = [set(), set()]
        self.sysdir = C.c_char_p(os.path.join(EMU, "roms").encode())
        self.savedir = C.c_char_p(os.path.join(EMU, "saves").encode())
        os.makedirs(os.path.join(EMU, "saves"), exist_ok=True)
        os.makedirs(os.path.join(EMU, "shots"), exist_ok=True)
        self._cbs = [ENV_CB(self._env), VIDEO_CB(self._video), AUDIO_CB(lambda l, r: None),
                     AUDIO_BATCH_CB(lambda d, n: n), POLL_CB(lambda: None), STATE_CB(self._state)]
        L = self.lib
        L.retro_set_environment(self._cbs[0])
        L.retro_set_video_refresh(self._cbs[1])
        L.retro_set_audio_sample(self._cbs[2])
        L.retro_set_audio_sample_batch(self._cbs[3])
        L.retro_set_input_poll(self._cbs[4])
        L.retro_set_input_state(self._cbs[5])
        L.retro_init()
        info = GameInfo(os.path.join(EMU, "roms", game + ".zip").encode(), None, 0, None)
        if not L.retro_load_game(C.byref(info)):
            raise RuntimeError("load failed")
        L.retro_get_memory_data.restype = C.c_void_p
        L.retro_get_memory_size.restype = C.c_size_t
        L.retro_serialize_size.restype = C.c_size_t
        L.retro_serialize.restype = C.c_bool
        L.retro_unserialize.restype = C.c_bool

    def _env(self, cmd, data):
        cmd &= 0xFFFF
        if cmd in (9, 31):  # system / save directory
            C.cast(data, C.POINTER(C.c_char_p))[0] = (self.sysdir if cmd == 9 else self.savedir).value
            return True
        if cmd == 10:  # pixel format
            self.fmt = C.cast(data, C.POINTER(C.c_int))[0]
            return True
        if cmd == 3:  # can dupe
            C.cast(data, C.POINTER(C.c_bool))[0] = True
            return True
        if cmd == 17:  # variable update
            C.cast(data, C.POINTER(C.c_bool))[0] = False
            return True
        return False

    def _video(self, data, w, h, pitch):
        if not data:
            return
        bpp = 4 if self.fmt == 1 else 2
        raw = C.string_at(data, pitch * h)
        if bpp == 4:
            img = Image.frombuffer("RGBX", (w, h), raw, "raw", "BGRX", pitch, 1).convert("RGB")
        else:
            img = Image.frombuffer("RGB", (w, h), raw, "raw", "BGR;16", pitch, 1)
        self.frame = img

    def _state(self, port, device, index, id):
        if device != 1 or port > 1:
            return 0
        return 1 if id in {self.pad[b] for b in self.held[port]} else 0

    def run(self, frames=1, *buttons, port=0):
        self.held[port] = set(buttons)
        for _ in range(frames):
            self.lib.retro_run()
        self.held[port] = set()

    def tap(self, button, hold=4, after=8, port=0):
        self.run(hold, button, port=port)
        self.run(after)

    def shot(self, name):
        path = os.path.join(EMU, "shots", name + ".png")
        self.frame.resize((self.frame.width * 2, self.frame.height * 2), Image.NEAREST).save(path)
        return path

    def ram(self):
        p = self.lib.retro_get_memory_data(2)
        n = self.lib.retro_get_memory_size(2)
        return C.string_at(p, n)

    def byte(self, addr, ram=None):
        ram = ram or self.ram()
        return ram[(addr - self.base) ^ self.swap]

    def save(self):
        n = self.lib.retro_serialize_size()
        buf = C.create_string_buffer(n)
        assert self.lib.retro_serialize(buf, n)
        return buf.raw

    def load(self, state):
        buf = C.create_string_buffer(state, len(state))
        assert self.lib.retro_unserialize(buf, len(state))
