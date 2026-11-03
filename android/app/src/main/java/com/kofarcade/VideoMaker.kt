package com.kofarcade

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.view.Surface
import com.kofarcade.core.replay.AudioStretch
import com.swordfish.libretrodroid.LibretroDroid
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * Makes an MP4 of what the emulator draws and plays (docs/recording-plan.md,
 * R3), on its own thread: frames and sound are taken from the emulator's
 * capture (LibretroDroid.setCapture) and encoded by the phone's hardware
 * (H.264 and AAC). It looks and runs as the game does on the phone: 60
 * frames a second (the app shows every game at 60, its sound sped up to
 * match: [AudioStretch]), the picture smoothed as the game screen's is and
 * as wide as the player's Screen setting ([aspect], null: the whole frame).
 * Every frame is the next one in time: nothing's dropped (a replay waits
 * for this: LibretroDroid.captureBacklog), so picture and sound stay together.
 */
class VideoMaker(private val out: File, private val aspect: Float?, private val tall: Int = 1080) {
    @Volatile private var stopping = false
    @Volatile private var cancelled = false
    @Volatile var failed: Throwable? = null
        private set
    private var thread: Thread? = null

    fun start() {
        LibretroDroid.setCapture(true)
        thread = Thread({ run() }, "video maker").apply { start() }
    }

    /** What's been captured is finished into the file; false if it couldn't be made. */
    fun finish(): Boolean {
        stopping = true
        thread?.join()
        LibretroDroid.setCapture(false)
        return failed == null && !cancelled && out.length() > 0
    }

    fun cancel() {
        cancelled = true
        stopping = true
        thread?.join(3000)
        LibretroDroid.setCapture(false)
        out.delete()
    }

    private fun run() {
        try {
            make()
        } catch (t: Throwable) {
            failed = t
            out.delete()
        }
    }

    private fun make() {
        val av = LibretroDroid.getAvInfo()
        val fps = av[0].takeIf { it > 1 } ?: VIDEO_FPS
        val rate = av[1].toInt().takeIf { it in 8000..48000 } ?: 48000
        val stretch = AudioStretch(fps / VIDEO_FPS)

        val (video, width, height) = videoEncoder(tall) ?: videoEncoder(720) ?: error("No H.264 encoder")
        val audio = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2)
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            f.setInteger(MediaFormat.KEY_BIT_RATE, 192_000)
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        val input = video.createInputSurface()
        video.start()
        audio.start()
        val gl = Gl(input, width, height, aspect ?: (width.toFloat() / height))
        val mux = Muxer(MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4))
        var samples = 0L
        try {
            while (!cancelled) {
                val sound = LibretroDroid.takeCapturedAudio()
                if (sound.isNotEmpty()) {
                    val fitted = stretch.process(sound)
                    feedAudio(audio, fitted, samples, rate)
                    samples += fitted.size / 2
                }
                val frame = LibretroDroid.takeCapturedFrame()
                if (frame.size > 3) {
                    gl.draw(frame)
                    EGLExt.eglPresentationTimeANDROID(gl.display, gl.surface, frame[0] * 1_000_000_000L / VIDEO_FPS.toLong())
                    EGL14.eglSwapBuffers(gl.display, gl.surface)
                } else if (stopping && sound.isEmpty()) {
                    break
                } else {
                    Thread.sleep(2)
                }
                mux.drain(video, Muxer.VIDEO, end = false)
                mux.drain(audio, Muxer.AUDIO, end = false)
            }
            if (cancelled) return
            video.signalEndOfInputStream()
            endAudio(audio, samples * 1_000_000L / rate)
            mux.drain(video, Muxer.VIDEO, end = true)
            mux.drain(audio, Muxer.AUDIO, end = true)
        } finally {
            runCatching { mux.stop() }
            gl.release()
            input.release()
            runCatching { video.stop() }
            runCatching { audio.stop() }
            video.release()
            audio.release()
        }
    }

    /** An H.264 encoder taking frames from a surface, at [lines] tall and 16:9; null if the phone's can't. */
    private fun videoEncoder(lines: Int): Triple<MediaCodec, Int, Int>? {
        val height = lines
        val width = lines * 16 / 9 / 16 * 16
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        return try {
            val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            // Pixel art at 60 a second: generous, so the pixels stay sharp.
            f.setInteger(MediaFormat.KEY_BIT_RATE, if (lines >= 1080) 12_000_000 else 7_000_000)
            f.setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            Triple(codec, width, height)
        } catch (e: Exception) {
            codec.release()
            null
        }
    }

    private fun feedAudio(codec: MediaCodec, sound: ShortArray, startSample: Long, rate: Int) {
        var at = 0
        while (at < sound.size && !cancelled) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index < 0) continue
            val buf = codec.getInputBuffer(index) ?: continue
            buf.clear()
            val count = minOf(sound.size - at, buf.remaining() / 2) and 1.inv()
            buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(sound, at, count)
            val us = (startSample + at / 2) * 1_000_000L / rate
            codec.queueInputBuffer(index, 0, count * 2, us, 0)
            at += count
        }
    }

    private fun endAudio(codec: MediaCodec, us: Long) {
        while (true) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) {
                codec.queueInputBuffer(index, 0, 0, us, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                return
            }
        }
    }

    /** The MP4: both tracks added once their formats are known; what comes before that waits. */
    private class Muxer(private val muxer: MediaMuxer) {
        private val tracks = IntArray(2) { -1 }
        private var started = false
        private val waiting = ArrayList<Triple<Int, ByteBuffer, MediaCodec.BufferInfo>>()
        private val info = MediaCodec.BufferInfo()

        fun drain(codec: MediaCodec, kind: Int, end: Boolean) {
            // At the end, until the encoder says it's done (or, if it never does, a few seconds).
            val giveUp = System.currentTimeMillis() + 5_000
            while (true) {
                val index = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end || System.currentTimeMillis() > giveUp) return
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        tracks[kind] = muxer.addTrack(codec.outputFormat)
                        if (tracks.all { it >= 0 }) {
                            muxer.start()
                            started = true
                            for ((k, data, i) in waiting) muxer.writeSampleData(tracks[k], data, i)
                            waiting.clear()
                        }
                    }
                    index >= 0 -> {
                        val data = codec.getOutputBuffer(index)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0) {
                            data.position(info.offset)
                            data.limit(info.offset + info.size)
                            if (started) {
                                muxer.writeSampleData(tracks[kind], data, info)
                            } else {
                                val copy = ByteBuffer.allocateDirect(info.size).put(data).also { it.flip() }
                                val i = MediaCodec.BufferInfo().also { it.set(0, info.size, info.presentationTimeUs, info.flags) }
                                waiting.add(Triple(kind, copy, i))
                            }
                        }
                        val last = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                        if (last) return
                    }
                }
            }
        }

        fun stop() {
            if (started) muxer.stop()
            muxer.release()
        }

        companion object {
            const val VIDEO = 0
            const val AUDIO = 1
        }
    }

    /**
     * Drawing into the encoder's surface: the game's picture as a texture,
     * as wide as [aspect] allows (dark bars beside it if narrower than the
     * frame), smoothed between pixels as the game screen draws it (its
     * default shader is plain bilinear).
     */
    private class Gl(window: Surface, private val width: Int, private val height: Int, private val aspect: Float) {
        val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: EGLContext
        val surface: EGLSurface
        private val program: Int
        private val texture: Int
        private var texW = 0
        private var texH = 0
        private var staging: IntBuffer = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer()
        private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).also { it.position(0) }

        init {
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL" }
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
            )
            check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "EGL config" }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            surface = EGL14.eglCreateWindowSurface(display, configs[0], window, intArrayOf(EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "EGL current" }
            program = link(VERTEX, FRAGMENT)
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            texture = t[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        /** [frame]: {number, width, height, ARGB pixels...} as the emulator captured it. */
        fun draw(frame: IntArray) {
            val w = frame[1]
            val h = frame[2]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            // ARGB ints in memory are B, G, R, A bytes: uploaded as RGBA, swapped back in the shader.
            if (staging.capacity() < w * h) {
                staging = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
            }
            staging.clear()
            staging.put(frame, 3, w * h)
            staging.position(0)
            val pixels = staging
            if (w != texW || h != texH) {
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
                texW = w
                texH = h
            } else {
                GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            }
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0.043f, 0.043f, 0.07f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            // The picture, as big as fits at its own shape, in the middle.
            var boxW = width
            var boxH = (width / aspect).toInt()
            if (boxH > height) {
                boxH = height
                boxW = (height * aspect).toInt()
            }
            GLES20.glViewport((width - boxW) / 2, (height - boxH) / 2, boxW, boxH)
            GLES20.glUseProgram(program)
            val pos = GLES20.glGetAttribLocation(program, "pos")
            GLES20.glEnableVertexAttribArray(pos)
            GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }

        private fun link(vs: String, fs: String): Int {
            fun shader(type: Int, src: String) = GLES20.glCreateShader(type).also {
                GLES20.glShaderSource(it, src)
                GLES20.glCompileShader(it)
                val ok = IntArray(1)
                GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, ok, 0)
                check(ok[0] != 0) { GLES20.glGetShaderInfoLog(it) }
            }
            return GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, shader(GLES20.GL_VERTEX_SHADER, vs))
                GLES20.glAttachShader(it, shader(GLES20.GL_FRAGMENT_SHADER, fs))
                GLES20.glLinkProgram(it)
            }
        }

        companion object {
            const val EGL_RECORDABLE_ANDROID = 0x3142
            const val VERTEX = """
                attribute vec2 pos;
                varying vec2 uv;
                void main() {
                    // The picture's top row first: the top of the frame.
                    uv = vec2((pos.x + 1.0) * 0.5, (1.0 - pos.y) * 0.5);
                    gl_Position = vec4(pos, 0.0, 1.0);
                }
            """
            const val FRAGMENT = """
                precision highp float;
                uniform sampler2D tex;
                varying vec2 uv;
                void main() {
                    gl_FragColor = vec4(texture2D(tex, uv).bgr, 1.0);
                }
            """
        }
    }

    private companion object {
        /** The app shows every game at 60 frames a second: so does its video. */
        const val VIDEO_FPS = 60.0
    }
}
