package org.example.project.infrastructure.plugins

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.MutableOriginConnectionPoint
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaderValues
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import org.example.project.core.utils.LoopbackHosts

/**
 * Takes the client address and scheme from Caddy's `X-Forwarded-*` headers, but only from a
 * loopback peer.
 *
 * The trust boundary is the server host: Caddy (or any other process) on this machine connects
 * from loopback, so its headers are honoured. A LAN peer is not, and its `X-Forwarded-*` headers are
 * ignored entirely. That matters because binding 127.0.0.1 is not guaranteed: the in-app updater
 * replaces only pos-server.jar, so a shop that has not re-run the installer (and dev mode) still
 * listens on 0.0.0.0 with no Caddy in front. There a client could otherwise send a fresh
 * `X-Forwarded-For` per request and get a fresh `PairAttemptLimiter` bucket each time; instead,
 * per-client limits stay keyed on the real TCP peer's address.
 *
 * Only two headers are used, and only their last entry (the value Caddy set — without
 * `trusted_proxies`, Caddy replaces any client-supplied `X-Forwarded-*` header with its own single
 * value rather than appending to it, but the last-entry parsing is correct either way):
 * - `X-Forwarded-For` sets `remoteHost` and `remoteAddress`.
 * - `X-Forwarded-Proto` sets `scheme`, and only when it is `http` or `https`.
 *
 * `X-Forwarded-Host`, `-Server`, `-Protocol`, `-SSL`, `-Port` and `Front-End-Https` are never read.
 *
 * Install it before anything reads `call.request.origin`: `PairAttemptLimiter` keys on it, and
 * behind the proxy every request would otherwise share one bucket (127.0.0.1).
 */
fun Application.configureForwardedHeaders() {
    install(XForwardedHeaders) {
        // Ktor collects these lists before calling the handler; keep only the two we apply.
        hostHeaders.clear()
        httpsFlagHeaders.clear()
        portHeaders.clear()
        protoHeaders.clear()
        protoHeaders.add(HttpHeaders.XForwardedProto)
        forHeaders.clear()
        forHeaders.add(HttpHeaders.XForwardedFor)
        // Replaces Ktor's default handler: nothing is applied except what this function sets.
        extractEdgeProxy(::applyForwardedHeaders)
    }
}

/**
 * Applies [headers] to [point] when the direct TCP peer is loopback; otherwise leaves it untouched.
 *
 * [point] still reads through to the request's real connection until a field is assigned, so
 * `point.remoteAddress` here is the TCP peer. It is checked as an IP literal; an IPv4-mapped
 * loopback such as `::ffff:127.0.0.1` is not trusted, which fails closed. Likewise, a peer address
 * carrying an IPv6 zone id (e.g. `0:0:0:0:0:0:0:1%0`, as a native Netty transport can report) is not
 * recognized as loopback either; the installers' upstream connects over IPv4 `127.0.0.1`, so this
 * also fails closed rather than open.
 */
internal fun applyForwardedHeaders(
    point: MutableOriginConnectionPoint,
    headers: XForwardedHeaderValues,
) {
    if (!LoopbackHosts.isLoopback(point.remoteAddress)) return

    headers.forHeader?.lastEntry()?.let { client ->
        point.remoteHost = client
        point.remoteAddress = client
    }
    headers.protoHeader
        ?.lastEntry()
        ?.lowercase()
        ?.takeIf { it == "http" || it == "https" }
        ?.let { point.scheme = it }
}

private fun String.lastEntry(): String? = substringAfterLast(',').trim().takeIf { it.isNotEmpty() }
