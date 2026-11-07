package org.example.project.core.utils

/**
 * Hosts that never leave the machine. Plain HTTP to one of these cannot be read on the shop
 * network, which is the only reason the HTTPS-only rule may exempt them.
 *
 * The Android emulator alias 10.0.2.2 is deliberately NOT here: in a real shop it is an ordinary
 * LAN address. Emulator development uses `adb reverse tcp:8080 tcp:8080` and `http://localhost`.
 *
 * Only canonical literals count: `127.a.b.c` with each octet 0–255 and no leading zeros (anything
 * else is not an address and would go to DNS), and brackets only around an IPv6 address.
 */
object LoopbackHosts {
    fun isLoopback(host: String): Boolean {
        val trimmed = host.trim()
        val isBracketedIpv6 = trimmed.startsWith("[") && trimmed.endsWith("]") && ':' in trimmed
        val normalized = (if (isBracketedIpv6) trimmed.substring(1, trimmed.length - 1) else trimmed).lowercase()
        return normalized == "localhost" ||
            normalized == "::1" ||
            normalized == "0:0:0:0:0:0:0:1" ||
            isIpv4Loopback(normalized)
    }

    private fun isIpv4Loopback(host: String): Boolean {
        val octets = host.split('.')
        return octets.size == 4 && octets[0] == "127" && octets.all(::isCanonicalOctet)
    }

    private fun isCanonicalOctet(octet: String): Boolean =
        octet.length in 1..3 &&
            octet.all { it in '0'..'9' } &&
            (octet.length == 1 || octet[0] != '0') &&
            octet.toInt() <= 255
}
