package org.example.project.features.issuereport.domain.models

import org.example.project.core.domain.dto.issuereport.IssueReportDto
import java.time.Instant

/** Everything the server adds to a report itself. Nothing here comes from the operator's text. */
data class ReportContext(
    val appVersion: String,
    val platform: String,
    val source: IssueReportDto.Source,
    val screen: String?,
    val timestamp: Instant,
    val shopName: String?,
    val reporterRole: String,
)
