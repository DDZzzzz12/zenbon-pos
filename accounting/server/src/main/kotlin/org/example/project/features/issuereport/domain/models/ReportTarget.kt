package org.example.project.features.issuereport.domain.models

/**
 * Where a report goes, resolved from the stored overrides and the update channel. [isPublic] is the
 * operator's declaration (or the channel default), never verified with GitHub.
 */
data class ReportTarget(
    val owner: String,
    val repo: String,
    val baseUrl: String,
    val isPublic: Boolean,
)
