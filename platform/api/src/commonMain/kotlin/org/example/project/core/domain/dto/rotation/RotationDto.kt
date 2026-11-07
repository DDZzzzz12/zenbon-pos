package org.example.project.core.domain.dto.rotation

import kotlinx.serialization.Serializable

/**
 * Container for the round-robin "next employee" DTOs.
 */
object RotationDto {
    /** Reference to an employee in the rotation. */
    @Serializable
    data class EmployeeRefDto(
        val employeeId: String,
        val employeeNumber: String,
        val displayName: String? = null,
    )

    /** Body of the `GET` next-employee endpoint: the rotation version and who is next (null if nobody). */
    @Serializable
    data class NextSnapshotDto(
        val version: Long,
        val next: EmployeeRefDto?,
    )
}
