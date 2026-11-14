package org.example.project.features.terminal.data.service

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The addresses a device on the shop network could reach this server by, for the pairing dialog.
 *
 * The back office is often opened in a browser on the server itself (`http://localhost:8080`), and
 * its origin is then useless to a till or tablet: it would talk to itself. The server is the only
 * party that can list its own network addresses, so it offers them as suggestions the operator can
 * still edit.
 *
 * Only private IPv4 addresses of interfaces that are up are offered: loopback, link-local and
 * container/VM bridges' public-looking noise are skipped, and IPv6 is left out because the pairing
 * address field and the till's address policy are written for IPv4 LAN hosts.
 */
object LanServerUrls {
    /**
     * [addresses] as base URLs. Behind Caddy ([tlsRootPresent]) devices reach the server over
     * https on 443; otherwise by the port the request arrived on.
     */
    fun urlsFor(
        addresses: List<String>,
        port: Int,
        tlsRootPresent: Boolean,
    ): List<String> =
        addresses.distinct().map { host ->
            if (tlsRootPresent) "https://$host" else "http://$host:$port"
        }

    /** Private IPv4 addresses of this machine, interface order, no duplicates. Empty on any failure. */
    fun localAddresses(): List<String> =
        runCatching {
            NetworkInterface
                .getNetworkInterfaces()
                .toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .mapNotNull { it.hostAddress }
                .distinct()
        }.getOrDefault(emptyList())
}
