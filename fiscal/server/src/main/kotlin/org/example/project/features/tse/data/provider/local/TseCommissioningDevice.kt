package org.example.project.features.tse.data.provider.local

import org.example.project.core.domain.dto.tse.TseDto

/**
 * The device side of a TSE setup, behind which [LocalTseAdminProvider] makes its decisions.
 *
 * The production implementation drives the Swissbit SDK through JNI, which no test JVM loads; this
 * seam is what lets the setup's mode handling — the part that failed on every fresh install — be
 * tested against a recording double.
 */
interface TseCommissioningDevice {
    /** Opens the TSE when nothing has opened it yet. False when no TSE answers. */
    fun open(): Boolean

    /** Whether the TSE is already commissioned. */
    suspend fun isCommissioned(): Boolean

    /** Runs the certified setup sequence; throws a `PosException` when the TSE refuses it. */
    fun commission(request: TseCommissioningRequest): TseDto.AdminResultDto
}
