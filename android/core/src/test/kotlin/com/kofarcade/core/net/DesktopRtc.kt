package com.kofarcade.core.net

import dev.onvoid.webrtc.CreateSessionDescriptionObserver
import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCDataChannel
import dev.onvoid.webrtc.RTCDataChannelBuffer
import dev.onvoid.webrtc.RTCDataChannelInit
import dev.onvoid.webrtc.RTCDataChannelObserver
import dev.onvoid.webrtc.RTCDataChannelState
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCIceServer
import dev.onvoid.webrtc.RTCOfferOptions
import dev.onvoid.webrtc.RTCAnswerOptions
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCPeerConnectionState
import dev.onvoid.webrtc.RTCSdpType
import dev.onvoid.webrtc.RTCSessionDescription
import dev.onvoid.webrtc.RTCStatsType
import dev.onvoid.webrtc.SetSessionDescriptionObserver
import dev.onvoid.webrtc.media.audio.AudioDeviceModule
import dev.onvoid.webrtc.media.audio.AudioLayer
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture

/**
 * Real WebRTC (Google's, the desktop build webrtc-java ships) behind core's
 * RtcFactory, for the tests: the same library as on Android, so the
 * transport's offers, answers, ICE and data channels are checked for real.
 * The app's adapter (AndroidRtc) is this one, line for line, on org.webrtc.
 */
class DesktopRtc : RtcFactory {
    private val factory = PeerConnectionFactory(AudioDeviceModule(AudioLayer.kDummyAudio))

    override fun peer(ice: List<IceServer>, events: RtcPeer.Events): RtcPeer = Peer(ice, events)

    private inner class Peer(ice: List<IceServer>, private val events: RtcPeer.Events) : RtcPeer, PeerConnectionObserver {
        private val pc: RTCPeerConnection
        private val channels: Array<RTCDataChannel>

        init {
            val config = RTCConfiguration().apply {
                iceServers = ice.map { s ->
                    RTCIceServer().apply {
                        urls = s.urls
                        username = s.username
                        password = s.credential
                    }
                }
            }
            pc = factory.createPeerConnection(config, this)
            channels = arrayOf(
                pc.createDataChannel("links", RTCDataChannelInit().apply {
                    negotiated = true
                    id = RtcPeer.RELIABLE
                    ordered = true
                }),
                pc.createDataChannel("inputs", RTCDataChannelInit().apply {
                    negotiated = true
                    id = RtcPeer.QUICK
                    ordered = false
                    maxRetransmits = 0
                }),
            )
            channels.forEachIndexed { i, dc ->
                dc.registerObserver(object : RTCDataChannelObserver {
                    override fun onBufferedAmountChange(previousAmount: Long) {}

                    override fun onStateChange() {
                        if (dc.state == RTCDataChannelState.OPEN) events.onOpen(i)
                    }

                    override fun onMessage(buffer: RTCDataChannelBuffer) {
                        val data = ByteArray(buffer.data.remaining())
                        buffer.data.get(data)
                        events.onMessage(i, data)
                    }
                })
            }
        }

        private fun failing(done: CompletableFuture<*>, then: (RTCSessionDescription) -> Unit) = object : CreateSessionDescriptionObserver {
            override fun onSuccess(description: RTCSessionDescription) = then(description)
            override fun onFailure(error: String) {
                done.completeExceptionally(IllegalStateException(error))
            }
        }

        private fun setting(done: CompletableFuture<*>, then: () -> Unit) = object : SetSessionDescriptionObserver {
            override fun onSuccess() = then()
            override fun onFailure(error: String) {
                done.completeExceptionally(IllegalStateException(error))
            }
        }

        private fun setLocal(sdp: RTCSessionDescription, done: CompletableFuture<Sdp>) =
            pc.setLocalDescription(sdp, setting(done) { done.complete(Sdp(sdp.sdpType.name.lowercase(), sdp.sdp)) })

        override fun offer(restart: Boolean): CompletableFuture<Sdp> {
            val done = CompletableFuture<Sdp>()
            pc.createOffer(RTCOfferOptions().apply { iceRestart = restart }, failing(done) { setLocal(it, done) })
            return done
        }

        override fun answer(offer: Sdp): CompletableFuture<Sdp> {
            val done = CompletableFuture<Sdp>()
            pc.setRemoteDescription(RTCSessionDescription(RTCSdpType.OFFER, offer.sdp), setting(done) {
                pc.createAnswer(RTCAnswerOptions(), failing(done) { setLocal(it, done) })
            })
            return done
        }

        override fun accept(answer: Sdp): CompletableFuture<Unit> {
            val done = CompletableFuture<Unit>()
            pc.setRemoteDescription(RTCSessionDescription(RTCSdpType.ANSWER, answer.sdp), setting(done) { done.complete(Unit) })
            return done
        }

        override fun addCandidate(candidate: Candidate) = pc.addIceCandidate(RTCIceCandidate(candidate.mid, candidate.index, candidate.sdp))

        override fun send(channel: Int, data: ByteArray): Boolean {
            val dc = channels[channel]
            if (dc.state != RTCDataChannelState.OPEN) return false
            return runCatching { dc.send(RTCDataChannelBuffer(ByteBuffer.wrap(data), true)) }.isSuccess
        }

        override fun buffered(channel: Int): Long = channels[channel].bufferedAmount

        override fun path(): CompletableFuture<PathInfo?> {
            val done = CompletableFuture<PathInfo?>()
            pc.getStats { report ->
                val stats = report.stats
                val pair = stats.values.firstOrNull {
                    it.type == RTCStatsType.CANDIDATE_PAIR && it.attributes["nominated"] == true && it.attributes["state"] == "succeeded"
                }
                if (pair == null) {
                    done.complete(null)
                    return@getStats
                }
                fun type(id: Any?) = stats[id as? String]?.attributes?.get("candidateType") as? String
                val local = type(pair.attributes["localCandidateId"])
                val remote = type(pair.attributes["remoteCandidateId"])
                val kind = when {
                    local == "relay" || remote == "relay" -> "relay"
                    local == "host" && remote == "host" -> "nearby"
                    else -> "direct"
                }
                done.complete(PathInfo(kind, (pair.attributes["currentRoundTripTime"] as? Double)?.let { (it * 1000).toInt() }))
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

        override fun onIceCandidate(candidate: RTCIceCandidate) = events.onCandidate(Candidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp))

        override fun onConnectionChange(state: RTCPeerConnectionState) = events.onState(
            when (state) {
                RTCPeerConnectionState.NEW -> PeerState.NEW
                RTCPeerConnectionState.CONNECTING -> PeerState.CONNECTING
                RTCPeerConnectionState.CONNECTED -> PeerState.CONNECTED
                RTCPeerConnectionState.DISCONNECTED -> PeerState.DISCONNECTED
                RTCPeerConnectionState.FAILED -> PeerState.FAILED
                RTCPeerConnectionState.CLOSED -> PeerState.CLOSED
            },
        )
    }

    companion object {
        /** Null where the desktop build can't load (another OS or CPU): those tests are skipped. */
        val orNull: DesktopRtc? by lazy {
            runCatching { DesktopRtc() }.onFailure { println("Real WebRTC not available here: $it") }.getOrNull()
        }
    }
}
