package org.example.project.core.domain.dto.document

import kotlinx.serialization.Serializable

@Serializable
data class DocumentSummaryDto(
    val id: Long,
    val filename: String,
    val downloadUrl: String,
)
