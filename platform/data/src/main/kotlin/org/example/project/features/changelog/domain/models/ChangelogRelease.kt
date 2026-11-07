package org.example.project.features.changelog.domain.models

import org.example.project.features.changelog.database.tables.ChangelogReleaseTable
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.dao.Entity
import org.jetbrains.exposed.v1.dao.EntityClass

/**
 * Exposed DAO for one row of `changelog_release`. Read-only from application code;
 * only Flyway migration files write this table.
 */
class ChangelogRelease(
    id: EntityID<String>,
) : Entity<String>(id) {
    companion object : EntityClass<String, ChangelogRelease>(ChangelogReleaseTable)

    var releaseDate by ChangelogReleaseTable.releaseDate
    var titleEn by ChangelogReleaseTable.titleEn
    var titleDe by ChangelogReleaseTable.titleDe
    var titleZh by ChangelogReleaseTable.titleZh
    var summaryEn by ChangelogReleaseTable.summaryEn
    var summaryDe by ChangelogReleaseTable.summaryDe
    var summaryZh by ChangelogReleaseTable.summaryZh
    var highlightsEn by ChangelogReleaseTable.highlightsEn
    var highlightsDe by ChangelogReleaseTable.highlightsDe
    var highlightsZh by ChangelogReleaseTable.highlightsZh
    var knownIssuesEn by ChangelogReleaseTable.knownIssuesEn
    var knownIssuesDe by ChangelogReleaseTable.knownIssuesDe
    var knownIssuesZh by ChangelogReleaseTable.knownIssuesZh
    var appliedAt by ChangelogReleaseTable.appliedAt
    var appliedBy by ChangelogReleaseTable.appliedBy
    var createdAt by ChangelogReleaseTable.createdAt
}
