package org.example.project.features.tse.domain.service

import org.example.project.core.domain.dto.tse.TseConfigDto

/**
 * PROTECTED MODULE — Do not modify without explicit authorization.
 * TSE operations are fiscally regulated (KassenSichV) and changes require careful review.
 *
 * Service interface for managing TSE (Technical Security Element) configuration.
 *
 * All methods throw `org.example.project.infrastructure.errors.PosException` on failure
 * (B1 amendment); see the cross-walk table in
 * `docs/superpowers/plans/2026-05-04-poserror-tse-migration.md`.
 *
 * @since 1.0.0
 */
interface TseConfigurationService {
    /** Get current TSE configuration. */
    suspend fun getTseConfig(): TseConfigDto.TseConfig

    /** Update TSE configuration with partial updates. Returns the apply result surfaced to the operator. */
    suspend fun updateTseConfig(
        request: TseConfigDto.UpdateTseConfigRequest,
        deviceId: String,
    ): TseConfigDto.TseConfigUpdateResultDto
}
