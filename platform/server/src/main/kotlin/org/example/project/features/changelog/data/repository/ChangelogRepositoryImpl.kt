package org.example.project.features.changelog.data.repository

import org.example.project.features.changelog.database.tables.ChangelogReleaseTable
import org.example.project.features.changelog.domain.models.ChangelogRelease
import org.example.project.features.changelog.domain.repository.ChangelogRepository
import org.jetbrains.exposed.v1.core.SortOrder

/**
 * Exposed implementation of ChangelogRepository. Plain data access — no dbQuery,
 * no suspend; the service owns the transaction (house pattern).
 *
 * `release_version` text-sort works for the current "1.0.x" shape because all rows
 * share the same prefix width. If hotfix versions like "1.0.10.1" eventually appear,
 * consider a generated sort column.
 */
class ChangelogRepositoryImpl : ChangelogRepository {
    override fun list(
        page: Int,
        size: Int,
    ): List<ChangelogRelease> =
        ChangelogRelease
            .all()
            .orderBy(ChangelogReleaseTable.id to SortOrder.DESC)
            .limit(size)
            .offset(page.toLong() * size)
            .toList()

    override fun count(): Long = ChangelogRelease.count()
}
