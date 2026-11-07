package org.example.project.core.utils

import io.ktor.http.DEFAULT_PORT
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.parseUrl

/**
 * The HTTPS-only rule for a pos-server URL.
 *
 * The server sits behind Caddy and is reachable only over HTTPS (see
 * docs/superpowers/plans/2026-09-12-https-caddy-reverse-proxy.md). A client that accepted
 * `http://192.168.1.10:8080` would send passwords and tokens in clear text the moment an operator
 * mistyped an address, so every place that stores a server URL asks this first.
 */
object ServerUrlPolicy {
    fun evaluate(raw: String): ServerUrlVerdict {
        val trimmed = raw.trim()
        // Ktor ends the authority at `\`, browsers and OkHttp read it as `/`: the parsers would
        // disagree about the host. Whitespace and control characters have no place in a stored URL.
        if (trimmed.any { it == '\\' || it.isWhitespace() || it.isISOControl() }) return ServerUrlVerdict.MALFORMED

        val scheme = trimmed.substringBefore("://", missingDelimiterValue = "").lowercase()
        if (scheme != "http" && scheme != "https") return ServerUrlVerdict.MALFORMED

        // Not `Url(...)`: that fills an empty host from the origin (localhost, or window.location on
        // wasm), which would turn `http://#x` into loopback. parseUrl returns null for an empty host
        // and for anything Ktor cannot build (e.g. a port outside 0..65535).
        val url = parseUrl(trimmed) ?: return ServerUrlVerdict.MALFORMED
        // Credentials would be stored in settings and echoed in error texts.
        if (url.user != null || url.password != null) return ServerUrlVerdict.MALFORMED

        return when {
            scheme == "https" -> ServerUrlVerdict.ACCEPTED
            LoopbackHosts.isLoopback(url.host) -> ServerUrlVerdict.ACCEPTED
            else -> ServerUrlVerdict.INSECURE_REMOTE
        }
    }

    /**
     * The https:// address a plain-HTTP LAN URL most likely moved to once the installer put Caddy in
     * front of pos-server: the scheme becomes https and the old `:8080` (or an explicit `:80`) is
     * dropped, because Caddy answers on 443. Host — IPv6 brackets included — and any other port and
     * path are kept; query and fragment are dropped, because this suggestion is used as a base URL
     * that every request path gets appended to, and a stray `?a=b#f` surviving onto `/api/...` would
     * corrupt it.
     *
     * Parsed rather than string-replaced: a textual `replace(":8080", "")` also turned `:80800` into
     * `0` and was case-sensitive about the scheme. Anything that is not
     * [ServerUrlVerdict.INSECURE_REMOTE] — already accepted, or malformed (an unparseable port
     * included) — comes back unchanged.
     */
    fun suggestHttps(raw: String): String {
        if (evaluate(raw) != ServerUrlVerdict.INSECURE_REMOTE) return raw
        val url = parseUrl(raw.trim()) ?: return raw
        return URLBuilder(url)
            .apply {
                protocol = URLProtocol.HTTPS
                // Not url.port: that is the scheme's default (80) when none was written, and would
                // print as `:80` after the switch to https.
                port =
                    when (url.specifiedPort) {
                        HTTP_PORT, LEGACY_SERVER_PORT -> DEFAULT_PORT
                        else -> url.specifiedPort
                    }
                parameters.clear()
                fragment = ""
            }.buildString()
    }

    private const val HTTP_PORT = 80

    /** Where pos-server listened in plain HTTP before Caddy. */
    private const val LEGACY_SERVER_PORT = 8080
}
