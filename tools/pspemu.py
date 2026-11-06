"""Minimal headless libretro front end for PPSSPP (the PSP core the app runs):
an OpenGL context on a hidden window (glfw; run under Xvfb), frames, buttons
and the analog stick as the app sends them, screenshots, save states, and the
sound's sample count (to compare the game's pace with its sound).

Layout it expects (nothing here is committed: bring your own core and game):
  $EMU/psp/ppsspp_libretro.so  buildbot.libretro.com/nightly/linux/x86_64/latest/ppsspp_libretro.so.zip
  $EMU/psp/system/PPSSPP/      buildbot.libretro.com/assets/system/PPSSPP.zip, unpacked
  $EMU/psp/logshim.so          the core's log, if wanted: gcc -shared -fPIC (see logshim.c in the docstring of _env)
  $EMU/shots/                  screenshots

  Xvfb :99 & DISPLAY=:99 python3
  from pspemu import Psp
  p = Psp("/path/game.iso"); p.run(600); p.tap("START"); p.shot("menu")
"""
import ctypes as C
import os

import glfw
from PIL import Image

EMU = os.environ.get("EMU", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "emu"))
# RetroPad ids; PPSSPP: B = cross, A = circle, Y = square, X = triangle.
PAD = {"B": 0, "Y": 1, "SELECT": 2, "START": 3, "UP": 4, "DOWN": 5, "LEFT": 6, "RIGHT": 7,
       "A": 8, "X": 9, "L": 10, "R": 11}
# The app's arcade keys (PspProfile.keyCode): A square, B cross, C triangle, D circle, E L, F R, COIN SELECT.
APP = {"SQUARE": 1, "CROSS": 0, "TRIANGLE": 9, "CIRCLE": 8, "L": 10, "R": 11, "SELECT": 2, "START": 3,
       "UP": 4, "DOWN": 5, "LEFT": 6, "RIGHT": 7}

ENV_CB = C.CFUNCTYPE(C.c_bool, C.c_uint, C.c_void_p)
VIDEO_CB = C.CFUNCTYPE(None, C.c_void_p, C.c_uint, C.c_uint, C.c_size_t)
AUDIO_CB = C.CFUNCTYPE(None, C.c_int16, C.c_int16)
AUDIO_BATCH_CB = C.CFUNCTYPE(C.c_size_t, C.c_void_p, C.c_size_t)
POLL_CB = C.CFUNCTYPE(None)
STATE_CB = C.CFUNCTYPE(C.c_int16, C.c_uint, C.c_uint, C.c_uint, C.c_uint)
GET_FB = C.CFUNCTYPE(C.c_size_t)
GET_PROC = C.CFUNCTYPE(C.c_void_p, C.c_char_p)
RESET = C.CFUNCTYPE(None)
LOG_CB = C.CFUNCTYPE(None, C.c_int, C.c_char_p)
HW_FRAME = C.c_void_p(-1).value

# What the app sets (PspSpeed.options), 1x, frame skip as given.
APP_OPTIONS = {
    "ppsspp_cpu_core": "JIT",
    "ppsspp_fast_memory": "enabled",
    "ppsspp_io_timing": "Fast",
    "ppsspp_internal_resolution": "480x272",
    "ppsspp_texture_scaling_level": "disabled",
    "ppsspp_texture_anisotropic_filtering": "disabled",
    "ppsspp_skip_gpu_readbacks": "enabled",
    "ppsspp_inflight_frames": "Up to 2",
    "ppsspp_cache_iso": "disabled",
    "ppsspp_button_preference": "Cross",
    "ppsspp_language": "English",
    "ppsspp_frameskip": "disabled",
    "ppsspp_frame_duplication": "enabled",
    "ppsspp_frameskiptype": "Number of frames",
    "ppsspp_auto_frameskip": "disabled",
}


class Variable(C.Structure):
    _fields_ = [("key", C.c_char_p), ("value", C.c_char_p)]


class GameInfo(C.Structure):
    _fields_ = [("path", C.c_char_p), ("data", C.c_void_p), ("size", C.c_size_t), ("meta", C.c_char_p)]


class HwRender(C.Structure):
    _fields_ = [("context_type", C.c_int), ("context_reset", C.c_void_p), ("get_current_framebuffer", C.c_void_p),
                ("get_proc_address", C.c_void_p), ("depth", C.c_bool), ("stencil", C.c_bool),
                ("bottom_left_origin", C.c_bool), ("version_major", C.c_uint), ("version_minor", C.c_uint),
                ("cache_context", C.c_bool), ("context_destroy", C.c_void_p), ("debug_context", C.c_bool)]


class Geometry(C.Structure):
    _fields_ = [("base_width", C.c_uint), ("base_height", C.c_uint), ("max_width", C.c_uint),
                ("max_height", C.c_uint), ("aspect_ratio", C.c_float)]


class Timing(C.Structure):
    _fields_ = [("fps", C.c_double), ("sample_rate", C.c_double)]


class AvInfo(C.Structure):
    _fields_ = [("geometry", Geometry), ("timing", Timing)]


class LogCallback(C.Structure):
    _fields_ = [("log", C.c_void_p)]


class Psp:
    def __init__(self, game, options=None, log=False):
        self.options = {k.encode(): C.c_char_p(v.encode()) for k, v in {**APP_OPTIONS, **(options or {})}.items()}
        self.held = set()
        self.stick = (0, 0)
        self.frame = None
        self.samples = 0
        self.size = (480, 272)
        self.show_log = log
        if not glfw.init():
            raise RuntimeError("glfw: no display (run under Xvfb)")
        glfw.window_hint(glfw.VISIBLE, glfw.FALSE)
        glfw.window_hint(glfw.CONTEXT_VERSION_MAJOR, 3)
        glfw.window_hint(glfw.CONTEXT_VERSION_MINOR, 3)
        glfw.window_hint(glfw.OPENGL_PROFILE, glfw.OPENGL_CORE_PROFILE)
        self.win = glfw.create_window(960, 544, "psp", None, None)
        glfw.make_context_current(self.win)
        self._gl()
        base = os.path.join(EMU, "psp")
        self.sysdir = C.c_char_p(os.path.join(base, "system").encode())
        self.savedir = C.c_char_p(os.path.join(base, "saves").encode())
        os.makedirs(os.path.join(base, "saves"), exist_ok=True)
        os.makedirs(os.path.join(EMU, "shots"), exist_ok=True)
        self.lib = C.CDLL(os.path.join(base, "ppsspp_libretro.so"))
        self.hw = None
        self._cbs = [ENV_CB(self._env), VIDEO_CB(self._video), AUDIO_CB(self._sample),
                     AUDIO_BATCH_CB(self._batch), POLL_CB(lambda: None), STATE_CB(self._state),
                     GET_FB(lambda: self.fbo), GET_PROC(self._proc), LOG_CB(self._log)]
        L = self.lib
        L.retro_set_environment(self._cbs[0])
        L.retro_set_video_refresh(self._cbs[1])
        L.retro_set_audio_sample(self._cbs[2])
        L.retro_set_audio_sample_batch(self._cbs[3])
        L.retro_set_input_poll(self._cbs[4])
        L.retro_set_input_state(self._cbs[5])
        L.retro_init()
        info = GameInfo(os.path.abspath(game).encode(), None, 0, None)
        if not L.retro_load_game(C.byref(info)):
            raise RuntimeError("load failed")
        av = AvInfo()
        L.retro_get_system_av_info(C.byref(av))
        self.fps, self.rate = av.timing.fps, av.timing.sample_rate
        self._framebuffer(max(av.geometry.max_width, 480), max(av.geometry.max_height, 272))
        if self.hw and self.hw.context_reset:
            RESET(self.hw.context_reset)()
        L.retro_serialize_size.restype = C.c_size_t
        L.retro_serialize.restype = C.c_bool
        L.retro_unserialize.restype = C.c_bool

    # ----- OpenGL -----

    def _fn(self, name, res, *args):
        addr = glfw.get_proc_address(name)
        return C.CFUNCTYPE(res, *args)(addr)

    def _gl(self):
        u, i, p = C.c_uint, C.c_int, C.c_void_p
        self.glGenFramebuffers = self._fn("glGenFramebuffers", None, i, C.POINTER(u))
        self.glBindFramebuffer = self._fn("glBindFramebuffer", None, u, u)
        self.glGenTextures = self._fn("glGenTextures", None, i, C.POINTER(u))
        self.glBindTexture = self._fn("glBindTexture", None, u, u)
        self.glTexImage2D = self._fn("glTexImage2D", None, u, i, i, i, i, i, u, u, p)
        self.glFramebufferTexture2D = self._fn("glFramebufferTexture2D", None, u, u, u, u, i)
        self.glGenRenderbuffers = self._fn("glGenRenderbuffers", None, i, C.POINTER(u))
        self.glBindRenderbuffer = self._fn("glBindRenderbuffer", None, u, u)
        self.glRenderbufferStorage = self._fn("glRenderbufferStorage", None, u, u, i, i)
        self.glFramebufferRenderbuffer = self._fn("glFramebufferRenderbuffer", None, u, u, u, u)
        self.glReadPixels = self._fn("glReadPixels", None, i, i, i, i, u, u, p)
        self.glPixelStorei = self._fn("glPixelStorei", None, u, i)

    def _framebuffer(self, w, h):
        GL_FB, GL_TEX, GL_RB = 0x8D40, 0x0DE1, 0x8D41
        t, f, r = C.c_uint(), C.c_uint(), C.c_uint()
        self.glGenTextures(1, C.byref(t))
        self.glBindTexture(GL_TEX, t.value)
        self.glTexImage2D(GL_TEX, 0, 0x8058, w, h, 0, 0x1908, 0x1401, None)  # RGBA8
        self.glGenFramebuffers(1, C.byref(f))
        self.glBindFramebuffer(GL_FB, f.value)
        self.glFramebufferTexture2D(GL_FB, 0x8CE0, GL_TEX, t.value, 0)
        self.glGenRenderbuffers(1, C.byref(r))
        self.glBindRenderbuffer(GL_RB, r.value)
        self.glRenderbufferStorage(GL_RB, 0x88F0, w, h)  # DEPTH24_STENCIL8
        self.glFramebufferRenderbuffer(GL_FB, 0x821A, GL_RB, r.value)
        self.fbo = f.value

    def _proc(self, name):
        return glfw.get_proc_address(name.decode()) or None

    # ----- libretro callbacks -----

    def _log(self, level, text):
        if self.show_log or level >= 3:
            print("core:", text.decode(errors="replace").rstrip())

    def _env(self, cmd, data):
        cmd &= 0xFFFF
        if cmd in (9, 31):  # system / save directory
            C.cast(data, C.POINTER(C.c_char_p))[0] = (self.sysdir if cmd == 9 else self.savedir).value
            return True
        if cmd == 10:  # pixel format
            return True
        if cmd == 3:  # can dupe
            C.cast(data, C.POINTER(C.c_bool))[0] = True
            return True
        if cmd == 14:  # hardware rendering
            hw = C.cast(data, C.POINTER(HwRender)).contents
            if hw.context_type not in (1, 3):  # OpenGL, OpenGL core
                return False
            hw.get_current_framebuffer = C.cast(self._cbs[6], C.c_void_p)
            hw.get_proc_address = C.cast(self._cbs[7], C.c_void_p)
            self.hw = HwRender.from_buffer_copy(hw)
            return True
        if cmd == 56:  # preferred hardware rendering: OpenGL core
            C.cast(data, C.POINTER(C.c_uint))[0] = 3
            return True
        if cmd == 27 and self.show_log:  # log interface (printf-style: a small C shim prints it)
            shim = C.CDLL(os.path.join(EMU, "psp", "logshim.so"))
            C.cast(data, C.POINTER(LogCallback)).contents.log = C.cast(shim.retro_log, C.c_void_p)
            return True
        if cmd == 15:  # get variable
            var = C.cast(data, C.POINTER(Variable)).contents
            if var.key in self.options:
                var.value = self.options[var.key].value
                return True
            return False
        if cmd == 17:  # variable update
            C.cast(data, C.POINTER(C.c_bool))[0] = False
            return True
        return False

    def _video(self, data, w, h, pitch):
        if data == HW_FRAME or data is None and w:
            self.size = (w, h)
            self._shown = True

    def _sample(self, left, right):
        self.samples += 1

    def _batch(self, data, frames):
        self.samples += frames
        return frames

    def _state(self, port, device, index, id):
        if port != 0:
            return 0
        if device == 1:
            return 1 if id in {APP[b] for b in self.held} else 0
        if device == 5 and index == 0:  # left analog stick
            return int(self.stick[id] * 32767)
        return 0

    # ----- driving it -----

    def run(self, frames=1, *buttons, stick=(0, 0)):
        self.held = set(buttons)
        self.stick = stick
        for _ in range(frames):
            self.lib.retro_run()
        self.held = set()
        self.stick = (0, 0)

    def tap(self, button, hold=6, after=20):
        self.run(hold, button)
        self.run(after)

    def grab(self):
        w, h = self.size
        buf = C.create_string_buffer(w * h * 4)
        self.glBindFramebuffer(0x8D40, self.fbo)
        self.glPixelStorei(0x0D05, 1)
        self.glReadPixels(0, 0, w, h, 0x1908, 0x1401, buf)
        img = Image.frombuffer("RGBA", (w, h), buf.raw, "raw", "RGBA", 0, 1).convert("RGB")
        self.frame = img.transpose(Image.FLIP_TOP_BOTTOM)
        return self.frame

    def shot(self, name):
        path = os.path.join(EMU, "shots", name + ".png")
        self.grab().save(path)
        return path

    def save(self):
        n = self.lib.retro_serialize_size()
        buf = C.create_string_buffer(n)
        assert self.lib.retro_serialize(buf, n)
        return buf.raw

    def load(self, state):
        buf = C.create_string_buffer(state, len(state))
        assert self.lib.retro_unserialize(buf, len(state))
