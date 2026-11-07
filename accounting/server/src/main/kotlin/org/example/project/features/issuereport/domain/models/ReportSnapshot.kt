package org.example.project.features.issuereport.domain.models

import java.time.Instant

/**
 * A built report exactly as one preview showed it. A send that names this preview posts [title] and
 * [body] verbatim — never a rebuild — and only to the same [target].
 *
 * [employeeId] binds the snapshot to whoever previewed it; [hasLogExcerpt] lets the send re-run the
 * public-target guard without re-reading anything; [createdAt] is what the time to live counts from.
 */
data class ReportSnapshot(
    val employeeId: String,
    val title: String,
    val body: String,
    val target: ReportTarget,
    val hasLogExcerpt: Boolean,
    val createdAt: Instant,
) {
    /** The body is operator text and possibly a log excerpt: keep it out of any accidental log line. */
    override fun toString(): String =
        "ReportSnapshot(employeeId=$employeeId, target=$target, hasLogExcerpt=$hasLogExcerpt, createdAt=$createdAt, " +
            "title=[${title.length} chars], body=[${body.length} chars])"
}
