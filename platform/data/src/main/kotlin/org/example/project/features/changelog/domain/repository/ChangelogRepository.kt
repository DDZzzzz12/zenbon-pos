package org.example.project.features.changelog.domain.repository

import org.example.project.features.changelog.domain.models.ChangelogRelease

/** Read-only data access for the `changelog_release` table. */
interface ChangelogRepository {
    fun list(
        page: Int,
        size: Int,
    ): List<ChangelogRelease>

    fun count(): Long
}
