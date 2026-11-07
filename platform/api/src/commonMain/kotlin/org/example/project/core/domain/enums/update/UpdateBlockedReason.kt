package org.example.project.core.domain.enums.update

/**
 * Why the pre-install preflight refused an update, in a form a client can localize.
 *
 * The server carries the same six values as `PreflightVerdict.Reason`, a pos-server type the
 * clients cannot see. Rather than ship the operator an English sentence assembled on the server,
 * `POST /api/update/apply` answers 409 with a `FieldError("preflight", <name>)`, and this enum is
 * what the name is read back into. `UpdateBlockedReasonParityTest` in pos-server fails the build if
 * the two lists ever diverge, which is the only thing keeping the wire contract honest.
 *
 * A name that does not parse must stay null rather than collapse into a nearby value: "we could not
 * identify this" is not one typo away from "there are open bills".
 */
enum class UpdateBlockedReason {
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

    ;

    companion object {
        /** The value [name] denotes, or null when this build does not know it. */
        fun parse(name: String?): UpdateBlockedReason? = entries.firstOrNull { it.name == name }
    }
}
