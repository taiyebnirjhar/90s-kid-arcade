package com.kofarcade.core.net

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
fun roomKey(address: String, port: Int): String? {
    val portIndex = port - TCP_PORT
    if (portIndex !in 0 until TCP_PORTS) return null
    val ip = ipv4(address) ?: return null
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
fun parseRoomKey(text: String): Pair<String, Int>? {
    val t = text.trim()
    if (t.count { it == '.' } == 3 && t.all { it.isDigit() || it == '.' }) {
        return ipv4(t)?.let { t to TCP_PORT }
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
    return (0 until 4).joinToString(".") { i -> ((ip ushr (24 - 8 * i)) and 0xFF).toString() } to port
}

/** "192.168.1.5" as a number (the first part highest), or null if it isn't one. */
fun ipv4(text: String): Long? {
    val parts = text.split('.').map { it.toIntOrNull() ?: return null }
    if (parts.size != 4 || parts.any { it !in 0..255 }) return null
    return parts.fold(0L) { n, b -> (n shl 8) or b.toLong() }
}

/** A private (LAN) address: 10/8, 172.16/12, 192.168/16. */
private fun isSiteLocal(ip: Long): Boolean =
    ip ushr 24 == 10L || ip ushr 20 == 0xAC1L || ip ushr 16 == 0xC0A8L

/**
 * This phone's address on the local network, for its room key: the hotspot
 * it runs, else the Wi-Fi it's on. Never the mobile data network.
 */
fun lanAddress(): String? = lanInterfaces()
    .filterNot { nif -> MOBILE.any { nif.name.startsWith(it) } }
    .filter { nif -> ipv4(nif.address)?.let(::isSiteLocal) == true }
    .minByOrNull { nif -> PREFERRED.indexOfFirst { nif.name.startsWith(it) }.let { if (it < 0) PREFERRED.size else it } }
    ?.address

// Hotspot interfaces first, then Wi-Fi, then wired / USB. (Android: ap, wlan…;
// iPhone: its hotspot is bridge100, Wi-Fi en0.)
private val PREFERRED = listOf("ap", "swlan", "softap", "bridge", "wlan", "en", "eth", "rndis", "usb")
// Never the mobile data network (iPhone: pdp_ip; utun: VPNs).
private val MOBILE = listOf("rmnet", "ccmni", "radio", "v4-", "tun", "dummy", "clat", "ims", "pdp_ip", "utun", "ipsec")
