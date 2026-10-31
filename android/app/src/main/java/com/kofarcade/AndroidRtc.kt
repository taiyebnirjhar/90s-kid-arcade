package com.kofarcade

import android.content.Context
import com.kofarcade.core.net.Candidate
import com.kofarcade.core.net.IceServer
import com.kofarcade.core.net.PathInfo
import com.kofarcade.core.net.PeerState
import com.kofarcade.core.net.RtcFactory
import com.kofarcade.core.net.RtcPeer
import com.kofarcade.core.net.Sdp
import android.media.AudioAttributes
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.MediaStreamTrack
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture

/**
 * Google's WebRTC (stream-webrtc-android) behind core's [RtcFactory]: the
 * internet rooms' connections. Data channels, and an audio track each way
 * for voice: it only receives until the player turns their mic on (the
 * game screen asks for the microphone then), so the mic isn't recorded
 * before. One factory for the app.
 */
class AndroidRtc private constructor(context: Context) : RtcFactory {
    private val factory: PeerConnectionFactory

    init {
        val app = context.applicationContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions())
        // Voices play like the game (the phone's media volume), with the
        // phone's own echo canceller and noise suppressor on the mic.
        val audio = JavaAudioDeviceModule.builder(app)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder().setAudioDeviceModule(audio).createPeerConnectionFactory()
    }

    // The mic: one track for every connection, made the first time voice is on.
    private var micSource: AudioSource? = null
    private var micTrack: AudioTrack? = null

    @Synchronized
    private fun mic(): AudioTrack = micTrack ?: run {
        val source = factory.createAudioSource(
            MediaConstraints().apply {
                for (k in listOf("googEchoCancellation", "googNoiseSuppression", "googAutoGainControl", "googHighpassFilter")) {
                    mandatory += MediaConstraints.KeyValuePair(k, "true")
                }
            },
        )
        micSource = source
        factory.createAudioTrack("mic", source).also { micTrack = it }
    }

    /** Push to talk: the mic track sends only while [talking] (the connections stay set up for voice). */
    fun talking(talking: Boolean) {
        micTrack?.setEnabled(talking)
    }

    override fun peer(ice: List<IceServer>, events: RtcPeer.Events): RtcPeer = Peer(ice, events)

    private inner class Peer(ice: List<IceServer>, private val events: RtcPeer.Events) : RtcPeer, PeerConnection.Observer {
        private val pc: PeerConnection
        private val channels: Array<DataChannel>
        @Volatile private var sendVoice = false
        @Volatile private var volume = 1.0

        init {
            val config = PeerConnection.RTCConfiguration(
                ice.map { s ->
                    PeerConnection.IceServer.builder(s.urls).apply {
                        s.username?.let(::setUsername)
                        s.credential?.let(::setPassword)
                    }.createIceServer()
                },
            ).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                // Keeps looking for ways while connected: a new network is found quicker.
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            }
            pc = requireNotNull(factory.createPeerConnection(config, this)) { "WebRTC couldn't make a connection" }
            channels = arrayOf(
                pc.createDataChannel("links", DataChannel.Init().apply {
                    negotiated = true
                    id = RtcPeer.RELIABLE
                    ordered = true
                }),
                pc.createDataChannel("inputs", DataChannel.Init().apply {
                    negotiated = true
                    id = RtcPeer.QUICK
                    ordered = false
                    maxRetransmits = 0
                }),
            )
            channels.forEachIndexed { i, dc ->
                dc.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(previousAmount: Long) {}

                    override fun onStateChange() {
                        if (dc.state() == DataChannel.State.OPEN) events.onOpen(i)
                    }

                    override fun onMessage(buffer: DataChannel.Buffer) {
                        val data = ByteArray(buffer.data.remaining())
                        buffer.data.get(data)
                        events.onMessage(i, data)
                    }
                })
            }
        }

        private fun constraints(restart: Boolean) = MediaConstraints().apply {
            if (restart) mandatory += MediaConstraints.KeyValuePair("IceRestart", "true")
        }

        // The audio transceiver: the offerer adds it with its first offer; the
        // answerer's comes with the offer (none from an app before voice).
        private fun audio(): RtpTransceiver? = pc.transceivers.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO && !it.isStopped }

        // The voice this phone wants, on the transceiver: sending its mic, or only listening.
        private fun applyVoice(t: RtpTransceiver) {
            if (sendVoice) {
                t.sender.setTrack(mic(), false)
                t.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
            } else {
                t.sender.setTrack(null, false)
                t.direction = RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
            }
        }

        override fun setVoice(send: Boolean) {
            sendVoice = send
        }

        override fun setVoiceVolume(volume: Double) {
            this.volume = volume
            (audio()?.receiver?.track() as? AudioTrack)?.setVolume(volume)
        }

        override fun voiceLevel(): CompletableFuture<Double?> {
            val done = CompletableFuture<Double?>()
            val receiver = audio()?.receiver
            if (receiver == null) return CompletableFuture.completedFuture(null)
            pc.getStats(receiver) { report ->
                val inbound = report.statsMap.values.firstOrNull { it.type == "inbound-rtp" }
                done.complete((inbound?.members?.get("audioLevel") as? Number)?.toDouble())
            }
            return done
        }

        override fun offer(restart: Boolean): CompletableFuture<Sdp> {
            val done = CompletableFuture<Sdp>()
            val t = audio() ?: pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
            )
            applyVoice(t)
            pc.createOffer(object : Observer(done) {
                override fun onCreateSuccess(sdp: SessionDescription) = setLocal(sdp, done)
            }, constraints(restart))
            return done
        }

        override fun answer(offer: Sdp): CompletableFuture<Sdp> {
            val done = CompletableFuture<Sdp>()
            pc.setRemoteDescription(object : Observer(done) {
                override fun onSetSuccess() {
                    audio()?.let(::applyVoice)
                    pc.createAnswer(object : Observer(done) {
                        override fun onCreateSuccess(sdp: SessionDescription) = setLocal(sdp, done)
                    }, constraints(false))
                }
            }, SessionDescription(SessionDescription.Type.OFFER, offer.sdp))
            return done
        }

        private fun setLocal(sdp: SessionDescription, done: CompletableFuture<Sdp>) {
            pc.setLocalDescription(object : Observer(done) {
                override fun onSetSuccess() {
                    done.complete(Sdp(sdp.type.canonicalForm(), sdp.description))
                }
            }, sdp)
        }

        override fun accept(answer: Sdp): CompletableFuture<Unit> {
            val done = CompletableFuture<Unit>()
            pc.setRemoteDescription(object : Observer(done) {
                override fun onSetSuccess() {
                    done.complete(Unit)
                }
            }, SessionDescription(SessionDescription.Type.ANSWER, answer.sdp))
            return done
        }

        override fun addCandidate(candidate: Candidate) {
            pc.addIceCandidate(IceCandidate(candidate.mid, candidate.index, candidate.sdp))
        }

        override fun send(channel: Int, data: ByteArray): Boolean {
            val dc = channels[channel]
            if (dc.state() != DataChannel.State.OPEN) return false
            return dc.send(DataChannel.Buffer(ByteBuffer.wrap(data), true))
        }

        override fun buffered(channel: Int): Long = channels[channel].bufferedAmount()

        override fun path(): CompletableFuture<PathInfo?> {
            val done = CompletableFuture<PathInfo?>()
            pc.getStats { report ->
                val stats = report.statsMap
                val pair = stats.values.firstOrNull { it.type == "candidate-pair" && it.members["nominated"] == true && it.members["state"] == "succeeded" }
                if (pair == null) {
                    done.complete(null)
                    return@getStats
                }
                fun type(id: Any?) = stats[id as? String]?.members?.get("candidateType") as? String
                val local = type(pair.members["localCandidateId"])
                val remote = type(pair.members["remoteCandidateId"])
                val kind = when {
                    local == "relay" || remote == "relay" -> "relay"
                    local == "host" && remote == "host" -> "nearby"
                    else -> "direct"
                }
                val rtt = (pair.members["currentRoundTripTime"] as? Double)?.let { (it * 1000).toInt() }
                done.complete(PathInfo(kind, rtt))
            }
            return done
        }

        override fun close() {
            channels.forEach {
                it.unregisterObserver()
                it.close()
            }
            pc.close()
        }

        // ----- PeerConnection.Observer -----

        override fun onIceCandidate(candidate: IceCandidate) =
            events.onCandidate(Candidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp))

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) = events.onState(
            when (newState) {
                PeerConnection.PeerConnectionState.NEW -> PeerState.NEW
                PeerConnection.PeerConnectionState.CONNECTING -> PeerState.CONNECTING
                PeerConnection.PeerConnectionState.CONNECTED -> PeerState.CONNECTED
                PeerConnection.PeerConnectionState.DISCONNECTED -> PeerState.DISCONNECTED
                PeerConnection.PeerConnectionState.FAILED -> PeerState.FAILED
                PeerConnection.PeerConnectionState.CLOSED -> PeerState.CLOSED
            },
        )

        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(channel: DataChannel) {}
        override fun onRenegotiationNeeded() {}
        // The other phone's voice: played at its volume (0 for one muted here).
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
            (receiver.track() as? AudioTrack)?.setVolume(volume)
        }
    }

    /** An SDP step that fails [done] on failure; success is the subclass's. */
    private abstract class Observer(private val done: CompletableFuture<*>) : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {
            done.completeExceptionally(IllegalStateException(error ?: "WebRTC: create failed"))
        }

        override fun onSetFailure(error: String?) {
            done.completeExceptionally(IllegalStateException(error ?: "WebRTC: set failed"))
        }
    }

    companion object {
        @Volatile private var instance: AndroidRtc? = null

        fun get(context: Context): AndroidRtc = instance ?: synchronized(this) {
            instance ?: AndroidRtc(context).also { instance = it }
        }
    }
}
