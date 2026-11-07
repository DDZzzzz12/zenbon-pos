package org.example.project.features.changelog.database.tables

import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IdTable
import org.jetbrains.exposed.v1.datetime.CurrentDateTime
import org.jetbrains.exposed.v1.datetime.datetime
import org.jetbrains.exposed.v1.json.jsonb

/**
 * Exposed table for `changelog_release`. Migration-only writes; application code
 * is read-only (see spec 2026-08-02-changelog-release-notes-table-design.md).
 *
 * The primary key is `release_version` (TEXT), not a serial id — every release is
 * exactly one row. Uses [IdTable] with a `String` id rather than `LongIdTable`.
 */
object ChangelogReleaseTable : IdTable<String>("changelog_release") {
    override val id: Column<EntityID<String>> = varchar("release_version", 60).entityId()
    override val primaryKey = PrimaryKey(id)

    // `defaultExpression`, not `clientDefault`, on all three timestamps — matching the `DEFAULT
    // NOW()` the production DDL (`V79__release_tse_audit_terminal.sql`) declares on each.
    //
    // `clientDefault` fills the value Exposed sends and emits NO column default, which is invisible
    // until a writer that is NOT Exposed inserts a row — and a release-marker migration is exactly
    // that. Its INSERT names the notes columns and leaves these three to the default, so against an
    // Exposed-built schema it failed with "NULL not allowed for APPLIED_AT". A fresh install builds
    // this table with Exposed, so the NEXT release's marker migration would have aborted the upgrade
    // of every freshly installed shop, and `SchemaManager.seedReleaseNotes` — which replays the
    // existing markers on the fresh path — could not run at all.
    //
    // The two cannot be combined: Exposed's `clientDefault` clears `dbDefaultValue` and
    // `defaultExpression` clears the client function, whichever is applied last. The database side
    // is the one that has to win here, because the writers that depend on it are SQL scripts.
    val releaseDate = datetime("release_date").defaultExpression(CurrentDateTime)

    val titleEn = text("title_en")
    val titleDe = text("title_de")
    val titleZh = text("title_zh")

    val summaryEn = text("summary_en")
    val summaryDe = text("summary_de")
    val summaryZh = text("summary_zh")

    val highlightsEn = jsonb<List<String>>("highlights_en", Json.Default)
    val highlightsDe = jsonb<List<String>>("highlights_de", Json.Default)
    val highlightsZh = jsonb<List<String>>("highlights_zh", Json.Default)

    val knownIssuesEn = jsonb<List<String>>("known_issues_en", Json.Default)
    val knownIssuesDe = jsonb<List<String>>("known_issues_de", Json.Default)
    val knownIssuesZh = jsonb<List<String>>("known_issues_zh", Json.Default)

    val appliedAt = datetime("applied_at").defaultExpression(CurrentDateTime)
    val appliedBy = text("applied_by").default("current_user")
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
}
