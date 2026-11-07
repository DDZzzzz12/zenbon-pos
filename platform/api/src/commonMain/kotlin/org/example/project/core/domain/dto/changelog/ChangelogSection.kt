package org.example.project.core.domain.dto.changelog

/**
 * Section headings inside a release marker's `highlights_*` / `known_issues_*` arrays.
 *
 * The arrays are flat lists of strings, and the changelog screen renders each one as a bullet. A
 * string that begins with [HEADING_PREFIX] is the exception: it is a heading over the bullets that
 * follow it, rendered as a label instead of a bullet. A release marker uses this to set a group
 * apart — the TSE-signing and DSFinV-K items lead v1.1.0 under their own heading, with fixes and
 * new functions in separate groups — without changing the wire format or the table.
 *
 * The heading text is ordinary release-note content and lives in the migration per language, like
 * every other bullet; only the marker is shared between the migration and the screen.
 */
object ChangelogSection {
    const val HEADING_PREFIX = "## "

    fun isHeading(bullet: String): Boolean = bullet.startsWith(HEADING_PREFIX)

    fun headingText(bullet: String): String = bullet.removePrefix(HEADING_PREFIX).trim()
}
