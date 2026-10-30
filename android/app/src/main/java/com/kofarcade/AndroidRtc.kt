package com.kofarcade

import android.content.Context
import com.kofarcade.core.net.Candidate
import com.kofarcade.core.net.IceServer
import com.kofarcade.core.net.PathInfo
import com.kofarcade.core.net.PeerState
import com.kofarcade.core.net.RtcFactory
import com.kofarcade.core.net.RtcPeer
import com.kofarcade.core.net.Sdp
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture

/**
 * Google's WebRTC (stream-webrtc-android) behind core's [RtcFactory]: the
 * internet rooms' connections. Data channels only (no camera or mic), so
 * nothing asks for permissions. One factory for the app.
 */
class AndroidRtc private constructor(context: Context) : RtcFactory {
    private val factory: PeerConnectionFactory

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions(),
        )
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    override fun peer(ice: List<IceServer>, events: RtcPeer.Events): RtcPeer = Peer(ice, events, relayOnly = false)

    override fun peer(ice: List<IceServer>, events: RtcPeer.Events, relayOnly: Boolean): RtcPeer = Peer(ice, events, relayOnly)

    private inner class Peer(ice: List<IceServer>, private val events: RtcPeer.Events, relayOnly: Boolean) : RtcPeer, PeerConnection.Observer {
        private val pc: PeerConnection
        private val channels: Array<DataChannel>

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
                // Only the relay's addresses are offered: the other phone never learns ours.
                if (relayOnly) iceTransportsType = PeerConnection.IceTransportsType.RELAY
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

        override fun offer(restart: Boolean): CompletableFuture<Sdp> {
            val done = CompletableFuture<Sdp>()
            pc.createOffer(object : Observer(done) {
                override fun onCreateSuccess(sdp: SessionDescription) = setLocal(sdp, done)
            }, constraints(restart))
            return done
        }

        override fun answer(offer: Sdp): CompletableFuture<Sdp> {
            val done = CompletableFuture<Sdp>()
            pc.setRemoteDescription(object : Observer(done) {
                override fun onSetSuccess() {
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
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
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
