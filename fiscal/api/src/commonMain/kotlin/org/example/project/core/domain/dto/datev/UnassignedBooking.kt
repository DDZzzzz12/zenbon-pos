package org.example.project.core.domain.dto.datev

import kotlinx.serialization.Serializable

/** One switched-off cell an export met, and how many lines it left without a contra account. */
@Serializable
data class UnassignedBooking(
    val cell: CellRef,
    val lineCount: Int,
)
