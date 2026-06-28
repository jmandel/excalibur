package dev.exe.kindleconverter.net

import java.net.Inet4Address
import java.net.NetworkInterface

/** A reachable address for the Kindle, with a hint about whether it looks like a hotspot gateway. */
data class LanAddress(val ip: String, val isHotspotLikely: Boolean) {
    fun url(port: Int) = "http://$ip:$port/"
}

private val HOTSPOT_PREFIXES = listOf("192.168.43.", "192.168.49.", "172.20.10.", "192.168.137.")

/**
 * Enumerate usable IPv4 addresses across all up interfaces (this catches the tethering
 * gateway address, which is where a tethered Kindle reaches us). Hotspot-range addresses
 * are surfaced first because that is the common "phone is the hotspot" case.
 */
fun discoverAddresses(): List<LanAddress> {
    val out = linkedSetOf<String>()
    runCatching {
        NetworkInterface.getNetworkInterfaces().toList().forEach { nif ->
            if (!nif.isUp || nif.isLoopback) return@forEach
            nif.inetAddresses.toList().filterIsInstance<Inet4Address>().forEach {
                if (!it.isLoopbackAddress) it.hostAddress?.let(out::add)
            }
        }
    }
    if (out.isEmpty()) out.add("127.0.0.1")
    return out.map { ip -> LanAddress(ip, HOTSPOT_PREFIXES.any(ip::startsWith)) }
        .sortedByDescending { it.isHotspotLikely }
}
