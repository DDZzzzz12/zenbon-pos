package org.example.project.features.setup.domain.service

import org.example.project.core.domain.dto.setup.InitialSetupDto
import org.example.project.core.domain.dto.setup.SetupReadinessDto

/**
 * Service interface for initial system setup operations.
 *
 * Throws [org.example.project.infrastructure.errors.PosException] on errors;
 * the global StatusPages handler converts to application/problem+json.
 * Migrated from Result<T, Error> in Stage B Batch 4.
 */
interface InitialSetupService {
    /**
     * Checks if the system has already been initialized.
     *
     * Determines whether initial setup has been completed by checking
     * for the presence of essential data and initialization flags.
     */
    suspend fun checkInitializationStatus(): InitialSetupDto.InitialSetupStatusDto

    /**
     * Resets the system to its initial state.
     *
     * WARNING: This operation removes all data and reinitializes the system.
     * Should only be used in development or with explicit administrator consent.
     */
    suspend fun resetSystemData(resetRequest: InitialSetupDto.ResetSystemRequestDto): InitialSetupDto.InitialSetupResponseDto

    /**
     * Executes a setup step from a generic InitialSetupRequestDto.
     *
     * Routes the setup request to the appropriate step handler based on
     * the setupStep field, then executes the step with the provided data.
     */
    suspend fun executeSetupStep(setupRequest: InitialSetupDto.InitialSetupRequestDto<*>)

    /**
     * Executes a complete atomic system setup from the UI wizard.
     *
     * Creates admin account, shop info, initializes all ConfigKey defaults,
     * creates sample payment types, and sets standard German VAT rates
     * in a single transaction. If any step fails, everything rolls back.
     */
    suspend fun executeCompleteSetup(request: InitialSetupDto.CompleteSetupRequestDto): InitialSetupDto.InitialSetupResponseDto

    /**
     * Whether the installation can actually take an order — everything the bootstrap wizard does not
     * cover, such as a paired fiscal terminal and a commissioned TSE.
     *
     * Separate from [checkInitializationStatus] because that one is served unauthenticated for the
     * wizard gate, and this one reports fiscal signing state that must not be public.
     */
    suspend fun checkReadiness(): SetupReadinessDto
}
