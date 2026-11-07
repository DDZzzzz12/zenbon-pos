package org.example.project.infrastructure.server

import org.example.project.core.utils.LoopbackHosts

/**
 * The startup error a production server logs when it listens on a non-loopback address.
 *
 * A warning, never a refusal. The in-app updater replaces only pos-server.jar, so a shop that has
 * not re-run the installer yet (no Caddy, no `HOST=127.0.0.1`) must still start and trade after an
 * unattended update. Closing the port is the installer's job; this makes sure a skipped installer
 * run is visible in every log instead of silently leaving clear-text HTTP on the LAN.
 *
 * @return the message to log, or null when there is nothing to report.
 */
fun plainHttpExposureWarning(
    host: String,
    developmentMode: Boolean,
): String? {
    if (developmentMode || LoopbackHosts.isLoopback(host)) return null
    return "SECURITY: pos-server listens on '$host' over plain HTTP. Re-run install-service.sh " +
        "so Caddy terminates HTTPS and pos-server binds loopback (HOST=127.0.0.1)."
}
