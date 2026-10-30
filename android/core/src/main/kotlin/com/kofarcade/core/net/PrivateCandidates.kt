package com.kofarcade.core.net

/**
 * What a phone in an open room may tell strangers about how to reach it
 * (its ICE candidates): the relay's address, and its address on its own
 * Wi-Fi or hotspot (10.x, 172.16-31.x, 192.168.x, a .local name), which
 * means nothing outside that network. Never its internet address: no
 * server-reflexive candidates, no public host addresses (a phone's IPv6 on
 * mobile data is public), and the relay's candidate goes with its "related
 * address" (the internet address it was made from) blanked.
 *
 * So strangers elsewhere reach each other only through the relay, while
 * phones on one Wi-Fi find each other's local address and connect
 * directly: no detour to the relay, no lag.
 *
 * The other side's checks can't learn more: a check sent to the relay from
 * our internet address is dropped there (the relay only passes on what
 * comes from an address it was told about, our local one).
 */
object PrivateCandidates {
    /** [candidate] ("candidate:… typ host …") as it may be shared, or null if it mustn't be. */
    fun candidate(candidate: String): String? {
        val prefix = if (candidate.startsWith("a=")) "a=" else ""
        val f = candidate.removePrefix(prefix).trim().split(Regex("\\s+")).toMutableList()
        // candidate:<foundation> <component> <transport> <priority> <address> <port> typ <type> [raddr <a> rport <p>] …
        val typAt = f.indexOf("typ")
        if (typAt < 0 || f.size < 8) return null
        val address = f[4]
        when (f.getOrNull(typAt + 1)) {
            "relay" -> Unit
            "host" -> if (!localOnly(address)) return null
            else -> return null // srflx, prflx: the internet address itself
        }
        // The related address is where a candidate came from (a relay's: our internet address).
        f.indexOf("raddr").takeIf { it >= 0 && it + 1 < f.size }?.let { f[it + 1] = "0.0.0.0" }
        f.indexOf("rport").takeIf { it >= 0 && it + 1 < f.size }?.let { f[it + 1] = "0" }
        return prefix + f.joinToString(" ")
    }

    /** An offer or answer with only shareable candidates, and no internet address in its connection lines. */
    fun sdp(sdp: String): String {
        val nl = if ("\r\n" in sdp) "\r\n" else "\n"
        return sdp.split(nl).mapNotNull { line ->
            when {
                line.startsWith("a=candidate:") -> candidate(line)
                // c=IN IP4 <address>: the default way, which may be the internet address.
                line.startsWith("c=IN ") -> {
                    val parts = line.split(" ")
                    if (parts.size >= 3 && !localOnly(parts[2])) "${parts[0]} ${parts[1]} ${if (parts[1] == "IP6") "::" else "0.0.0.0"}" else line
                }
                // a=rtcp:<port> IN IP4 <address>
                line.startsWith("a=rtcp:") && " IN " in line -> {
                    val parts = line.split(" ")
                    if (parts.size >= 4 && !localOnly(parts[3])) "${parts[0]} ${parts[1]} ${parts[2]} ${if (parts[2] == "IP6") "::" else "0.0.0.0"}" else line
                }
                else -> line
            }
        }.joinToString(nl)
    }

    /** An address only good on its own network: a private or link-local IPv4, an mDNS name, or none at all. */
    fun localOnly(address: String): Boolean {
        if (address.endsWith(".local", ignoreCase = true)) return true
        if (address == "0.0.0.0" || address == "::") return true
        val o = address.split(".").map { it.toIntOrNull() ?: return false }
        if (o.size != 4) return false
        return o[0] == 10 ||
            (o[0] == 172 && o[1] in 16..31) ||
            (o[0] == 192 && o[1] == 168) ||
            (o[0] == 169 && o[1] == 254)
    }
}
