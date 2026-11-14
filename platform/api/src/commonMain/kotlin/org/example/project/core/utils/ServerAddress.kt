package org.example.project.core.utils

/**
 * The server base URL a device is pointed at, as someone types it on the device.
 *
 * Typed by hand on a touch keyboard, it arrives with stray spaces, without a scheme, or without the
 * trailing slash the HTTP client's relative paths rely on. [normalize] is the one place that turns
 * that into a base URL — or says it cannot, so a caller rejects the input instead of storing an
 * address every later request fails on.
 */
object ServerAddress {
    private const val SCHEME_SEPARATOR = "://"
    private const val DEFAULT_SCHEME = "https://"
    private val SCHEMES = listOf("http://", "https://")
    private const val MAX_PORT = 65535
    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]", "0.0.0.0")
    private const val IPV4_LOOPBACK_PREFIX = "127."

    /**
     * `scheme://host[:port][/path]/`, or null when [input] is not a server address.
     *
     * A missing scheme means `https://`: the server is reachable over HTTPS only (Caddy), and nobody
     * types a scheme on a tablet. Any other scheme, credentials, a query or a fragment is rejected
     * rather than guessed at — each would break the base URL the client appends its paths to.
     */
    fun normalize(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val withScheme = if (SCHEME_SEPARATOR in trimmed) trimmed else DEFAULT_SCHEME + trimmed
        val scheme = SCHEMES.firstOrNull { withScheme.startsWith(it, ignoreCase = true) } ?: return null
        val rest = withScheme.substring(scheme.length)
        if ('?' in rest || '#' in rest) return null
        val authority = rest.substringBefore('/')
        if (authority.isEmpty() || '@' in authority) return null
        if (!hasHostAndValidPort(authority)) return null
        return scheme + rest.trimEnd('/') + "/"
    }

    private fun hasHostAndValidPort(authority: String): Boolean {
        val host: String
        val port: String?
        if (authority.startsWith("[")) {
            // An IPv6 literal carries colons of its own; the port follows the closing bracket.
            val end = authority.indexOf(']')
            if (end < 2) return false
            host = authority.substring(0, end + 1)
            val afterHost = authority.substring(end + 1)
            port =
                when {
                    afterHost.isEmpty() -> null
                    afterHost.startsWith(":") -> afterHost.substring(1)
                    else -> return false
                }
        } else {
            host = authority.substringBefore(':')
            port = if (':' in authority) authority.substringAfter(':') else null
        }
        if (host.isEmpty()) return false
        if (port == null) return true
        return port.length in 1..5 && port.all { it.isDigit() } && port.toInt() in 1..MAX_PORT
    }

    /**
     * Whether [input] is a valid server address whose host is unreachable local-only — `localhost`,
     * any `127.x.x.x` loopback literal, `[::1]`, or `0.0.0.0` (the wildcard bind address: not a
     * loopback literal itself, but Ktor logs it at startup, so an operator can copy it straight into
     * a pairing QR address field even though no other device can dial it). Such an address may work
     * in a browser on the server itself, but a device on the shop network that is handed it (e.g. in
     * a pairing QR) would talk to itself, or nowhere, instead.
     */
    fun isLoopback(input: String): Boolean {
        val normalized = normalize(input) ?: return false
        val authority = normalized.substringAfter(SCHEME_SEPARATOR).substringBefore('/')
        val host = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
        val lowerHost = host.lowercase()
        return lowerHost in LOOPBACK_HOSTS || lowerHost.startsWith(IPV4_LOOPBACK_PREFIX)
    }

    /**
     * The address to prefill a pairing QR with: the browser's own [origin] unless that is a loopback
     * host (the back office was opened on the server itself), in which case the first of the
     * server's own [suggestions] — a device on the shop network cannot dial `localhost`. Falls back
     * to [origin] when the server offered none, so the operator still sees the loopback warning.
     */
    fun reachableOrigin(
        origin: String,
        suggestions: List<String>,
    ): String = if (isLoopback(origin)) suggestions.firstOrNull() ?: origin else origin
}
