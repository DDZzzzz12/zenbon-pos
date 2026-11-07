package org.example.project.core.network

import kotlin.concurrent.Volatile

/**
 * The one root certificate this app has been told to trust, process-wide.
 *
 * A till pairs with a server behind Caddy's local CA by verifying that CA's root against a check code
 * it was handed out of band (see `RootFingerprint`), and stores the root here. Every platform trust
 * manager reads [pem] on each TLS handshake, so a freshly pinned root applies to the HTTP clients that
 * already exist — no client is rebuilt. Holds the PEM only; parsing stays in the platform code.
 */
object PinnedRootTrust {
    @Volatile
    var pem: String? = null
        private set

    /** Pins [pem] (a single PEM certificate), replacing any earlier pin; null clears it. */
    fun set(pem: String?) {
        this.pem = pem?.takeIf { it.isNotBlank() }
    }
}
