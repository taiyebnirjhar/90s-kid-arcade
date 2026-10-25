package com.kofarcade.core.net

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

// A room's key, what friends type to join: the owner's address on the local
// network (Wi-Fi or hotspot) and which of the room ports it listens on. So
// joining needs no searching, no server and no internet: the phone connects
// straight to the owner. Hotspots and home Wi-Fi use 192.168.x.y or
// 172.16-31.x.y (5 characters) or 10.x.y.z (6); anything else takes 8.
//
// Bits, lowest first: the kind of address (2), the port (3), the address.

private const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ" // 32: no 0 / O, 1 / I

private class Kind(val prefix: Long, val bits: Int, val chars: Int)

private val KINDS = listOf(
    Kind(0xC0A8_0000L, 16, 5), // 192.168.0.0/16
    Kind(0xAC10_0000L, 20, 5), // 172.16.0.0/12
    Kind(0x0A00_0000L, 24, 6), // 10.0.0.0/8
    Kind(0L, 32, 8),           // anything
)

/** The key for a room at [address]:[port], or null if the port isn't a room port. */
fun roomKey(address: Inet4Address, port: Int): String? {
    val portIndex = port - TCP_PORT
    if (portIndex !in 0 until TCP_PORTS) return null
    val ip = address.address.fold(0L) { n, b -> (n shl 8) or (b.toLong() and 0xFF) }
    val kind = KINDS.indexOfFirst { k -> k.bits == 32 || (ip ushr k.bits) == (k.prefix ushr k.bits) }
    val k = KINDS[kind]
    var n = ((ip and ((1L shl k.bits) - 1)) shl 5) or (portIndex.toLong() shl 2) or kind.toLong()
    val chars = buildString {
        repeat(k.chars) {
            append(ALPHABET[(n and 31).toInt()])
            n = n ushr 5
        }
    }
    return chars.chunked(3).joinToString("-")
}

/**
 * Where a room is from what was typed: a room key ("K7Q-2M", any case,
 * dashes and spaces optional) or an address ("192.168.43.1"). Null if it's
 * neither.
 */
fun parseRoomKey(text: String): Pair<InetAddress, Int>? {
    val t = text.trim()
    if (t.count { it == '.' } == 3 && t.all { it.isDigit() || it == '.' }) {
        return runCatching { InetAddress.getByName(t) to TCP_PORT }.getOrNull()
    }
    val chars = t.uppercase().filter { it.isLetterOrDigit() }
    if (chars.isEmpty() || chars.any { it !in ALPHABET }) return null
    var n = 0L
    chars.reversed().forEach { n = (n shl 5) or ALPHABET.indexOf(it).toLong() }
    val kind = (n and 3).toInt()
    val k = KINDS[kind]
    if (chars.length != k.chars) return null
    val port = TCP_PORT + ((n ushr 2) and 7).toInt()
    val bits = n ushr 5
    if (bits ushr k.bits != 0L) return null
    val ip = if (k.bits == 32) bits else k.prefix or bits
    val bytes = ByteArray(4) { i -> (ip ushr (24 - 8 * i)).toByte() }
    return InetAddress.getByAddress(bytes) to port
}

/**
 * This phone's address on the local network, for its room key: the hotspot
 * it runs, else the Wi-Fi it's on. Never the mobile data network.
 */
fun lanAddress(): Inet4Address? = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .filterNot { nif -> MOBILE.any { nif.name.startsWith(it) } }
        .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nif.name to it } }
        .filter { (_, a) -> a.isSiteLocalAddress }
        .minByOrNull { (name, _) -> PREFERRED.indexOfFirst { name.startsWith(it) }.let { if (it < 0) PREFERRED.size else it } }
        ?.second
}.getOrNull()

// Hotspot interfaces first, then Wi-Fi, then wired / USB.
private val PREFERRED = listOf("ap", "swlan", "softap", "wlan", "eth", "rndis", "usb")
private val MOBILE = listOf("rmnet", "ccmni", "radio", "v4-", "tun", "dummy", "clat", "ims")
