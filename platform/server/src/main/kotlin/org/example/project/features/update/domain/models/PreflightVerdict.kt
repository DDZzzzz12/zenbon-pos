package org.example.project.features.update.domain.models

/**
 * Whether it is safe to install an update right now, and -- when it is not -- **which** condition
 * stands in the way.
 *
 * The named [Reason] is the point of the type. "Not possible right now" is useless to an operator
 * reading yesterday's log at 03:00; "there are still open bills" tells them what to do about it.
 */
sealed interface PreflightVerdict {
    /** Nothing stands in the way; the installer may proceed. */
    data object Clear : PreflightVerdict

    /** Installation must not proceed. [reason] is the first condition found, not the only one. */
    data class Blocked(
        val reason: Reason,
    ) : PreflightVerdict

    /**
     * Declaration order is evaluation order: fiscal state first, because that is what an operator
     * most needs to see and most often has to act on.
     */
    enum class Reason {
        /** Bills or Kassenbuch entries are open since the last Kassenabschluss. */
        OPEN_BUSINESS,

        /** At least one table session is still open. */
        OPEN_TABLE_SESSION,

        /** A TSE transaction is still open. */
        OPEN_TSE_WORK,

        /** Another scheduled job is running. */
        JOB_RUNNING,

        /** Not enough free disk to hold what the installation needs. */
        INSUFFICIENT_DISK,
    }
}
