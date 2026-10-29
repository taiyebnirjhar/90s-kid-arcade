package com.kofarcade.core.net

// How a room's phones reach each other, kept apart from the room's logic
// (seats, holds, takeovers, rollback) so the same room runs over more than
// one network: on one Wi-Fi or hotspot, TCP and UDP sockets ([LanTransport]);
// over the internet, later, WebRTC; in tests, a simulated network.
//
// A room needs three things of it:
// - [Link]: a reliable, ordered channel of messages to one other phone,
//   for everything but inputs (joining, seatings, game states, pauses);
// - [Datagrams]: quick, unreliable packets to and from every phone of the
//   game, for inputs (lost ones are sent again with the next);
// - [Transport]: listening for links, making them, and the room's key.

/** Where a phone of a room is reached: on Wi-Fi, its IP address and port. The room only stores and compares it. */
data class Address(val host: String, val port: Int) {
    override fun toString() = "$host:$port"
}

/** A reliable, ordered channel of messages to one other phone. */
interface Link {
    /** Sends [messages] back to back: nothing sent from another thread comes between them. Throws when the link is gone. */
    fun send(vararg messages: Pair<Byte, ByteArray>)

    /** Sends one message. */
    fun send(type: Byte, payload: ByteArray = ByteArray(0)) = send(type to payload)

    /** The next message: waits for it, at most [timeoutMs] (0: for ever). Throws when the link is gone or the wait ran out. */
    fun receive(): Pair<Byte, ByteArray>

    /** How long [receive] waits (0: for ever). */
    var timeoutMs: Int

    /** The other phone's host, as this phone reaches it. */
    val remoteHost: String

    /** This phone's host, as the other phone reaches it. */
    val localHost: String

    val isClosed: Boolean

    /** Ends the link: the other phone's [receive] throws once it has what was sent before. */
    fun close()
}

/** Where links come in. */
interface Listener {
    /** Where other phones reach it (with this phone's host). Still answers once closed. */
    val port: Int

    /** The next link in. Throws once closed. */
    fun accept(): Link

    fun close()
}

/** Quick, unreliable packets to and from the room's phones: some are lost, some come late or out of order. */
interface Datagrams {
    /** Where other phones send to (with this phone's host). */
    val port: Int

    /** Sends [bytes] to [to]. Throws if it can't be sent now (it's sent again with the next). */
    fun send(bytes: ByteArray, to: Address)

    /** The next packet, into [buf]: its length, or -1 when [timeoutMs] passed with none. Throws on a failure. */
    fun receive(buf: ByteArray, timeoutMs: Int): Int

    fun close()
}

/** One phone's way of reaching the others. */
interface Transport {
    /** Listens for links on [port], or the first free of the room ports from it (0: any). */
    fun listen(port: Int): Listener

    /** A link to the phone at [to]. Throws if it can't be reached in [timeoutMs]. */
    fun connect(to: Address, timeoutMs: Int): Link

    /** A new endpoint for inputs. */
    fun datagrams(): Datagrams

    /**
     * What friends type to join a room run at [host] (empty: this phone) and
     * [port]; empty when there's no key to give.
     */
    fun key(host: String, port: Int): String
}
