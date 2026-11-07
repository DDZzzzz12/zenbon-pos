package org.example.project.admin.features.update.presentation

/**
 * The one thing the operator needs to understand when they open the update screen.
 *
 * Every value gets its own sentence. A generic "something went wrong" is useless to whoever is
 * standing at the till at 03:00, and two of these exist purely because collapsing them would be a
 * lie: [AUTH_FAILED] must never render as [UP_TO_DATE] (a private repository answers 404 to an
 * unauthorised caller, so an expired token looks exactly like "no releases"), and
 * [TOKEN_UNREADABLE] must never render as "no token configured" (something *is* configured, and it
 * is broken).
 */
enum class UpdateSituation {
    /** No check has ever run on this installation. */
    NEVER_CHECKED,

    /** The newest release is the one already running. */
    UP_TO_DATE,

    /** A newer release exists and has not been downloaded. */
    AVAILABLE,

    /** A newer release is downloaded, checksum-verified, and ready to install. */
    STAGED,

    /** GitHub rejected the stored token. */
    AUTH_FAILED,

    /** Ciphertext is stored but this host's key cannot open it. */
    TOKEN_UNREADABLE,

    /** No `UPDATE_TOKEN_KEY` on this host, so no token can be stored at all. */
    TOKEN_KEY_MISSING,

    /** GitHub could not be reached, or the release published no server archive. */
    UNREACHABLE,

    /** A release was found but could not be downloaded and verified. */
    STAGING_FAILED,

    /** The last install swapped the jar but the new build did not start; the previous jar was restored. */
    ROLLED_BACK,

    /** An install the operator just asked for was refused by the preflight. */
    BLOCKED,

    /**
     * The last *nightly* install was refused by the preflight, and nobody was there to see it.
     *
     * Distinct from [BLOCKED] because the operator did not just press anything: this is a report
     * about last night, and it has to say so. It is also distinct from [STAGED], which is what this
     * used to render as — a shop whose till is never idle at 03:00 was shown "ready to install"
     * indefinitely while the server was turned away every single night.
     */
    AUTO_APPLY_BLOCKED,
}
