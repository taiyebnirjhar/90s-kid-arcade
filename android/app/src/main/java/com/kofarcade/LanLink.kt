package com.kofarcade

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Keeps multiplayer on the Wi-Fi the phones share.
 *
 * When a phone's Wi-Fi gets weak or briefly drops, Android moves its traffic
 * to mobile data, and may stay there long after the Wi-Fi is back. A room's
 * phones are only reachable over the Wi-Fi, so everything sent then is lost,
 * and the phone can't get back into the room: on a router's Wi-Fi it drops
 * and doesn't heal. (On a phone's hotspot Android keeps local traffic local
 * by itself, so that never happened there.)
 *
 * So while a game is on, the app's traffic is tied to the Wi-Fi network the
 * room is on: the one whose subnet holds [target] (the host's address, or a
 * host's own). If that Wi-Fi drops and comes back, it's tied to it again at
 * once and [onBack] runs, so a phone that lost the room tries again straight
 * away. On a hotspot this phone runs, nothing is tied (it needn't be).
 */
class LanLink(context: Context, private val target: () -> String?, private val onBack: () -> Unit = {}) {
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val wifi = HashMap<Network, LinkProperties>()
    @Volatile private var bound: Network? = null
    @Volatile private var stopped = false
    private val looked = java.util.concurrent.CountDownLatch(1)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            val isNew = synchronized(wifi) { wifi.put(network, linkProperties) == null }
            update(backNow = isNew)
        }

        override fun onLost(network: Network) {
            synchronized(wifi) { wifi.remove(network) }
            update(backNow = false)
        }
    }

    init {
        runCatching {
            connectivity?.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    // A hotspot without internet is still where the room is.
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                callback,
            )
        }
    }

    /** The room's host may have changed (a takeover): tie to its network. */
    fun refresh() = update(backNow = false)

    /** Waits (up to [ms]) until the Wi-Fi networks are known and the link is made. */
    fun await(ms: Long) {
        looked.await(ms, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun update(backNow: Boolean) {
        if (stopped) return
        try {
            tie(backNow)
        } finally {
            looked.countDown()
        }
    }

    private fun tie(backNow: Boolean) {
        val address = target()?.let { runCatching { InetAddress.getByName(it) as? Inet4Address }.getOrNull() }
        val network = address?.let { a -> synchronized(wifi) { wifi.entries.firstOrNull { (_, lp) -> lp.holds(a) }?.key } }
        // Wi-Fi gone for now: stay tied to it (sends fail at once instead of
        // vanishing into mobile data) until it's back as a new network.
        if (network == null && address != null && bound != null && synchronized(wifi) { wifi.isEmpty() }) return
        if (network != bound) {
            bound = network
            runCatching { connectivity?.bindProcessToNetwork(network) }
            if (network != null && backNow) onBack()
        }
    }

    fun stop() {
        stopped = true
        runCatching { connectivity?.unregisterNetworkCallback(callback) }
        runCatching { connectivity?.bindProcessToNetwork(null) }
        bound = null
    }

    private companion object {
        // [address] is on this network's subnet.
        fun LinkProperties.holds(address: Inet4Address): Boolean = linkAddresses.any { la ->
            val own = la.address as? Inet4Address ?: return@any false
            val bits = la.prefixLength
            if (bits !in 1..32) return@any false
            val mask = if (bits == 32) -1 else ((-1) shl (32 - bits))
            (own.toInt() and mask) == (address.toInt() and mask)
        }

        fun Inet4Address.toInt(): Int = address.fold(0) { n, b -> (n shl 8) or (b.toInt() and 0xFF) }
    }
}
