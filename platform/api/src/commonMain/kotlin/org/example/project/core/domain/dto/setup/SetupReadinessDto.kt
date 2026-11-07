package org.example.project.core.domain.dto.setup

import kotlinx.serialization.Serializable

/**
 * Whether this installation can actually take an order yet.
 *
 * Distinct from `InitialSetupDto.InitialSetupStatusDto`, which answers "does the bootstrap wizard need
 * to run" and is served **unauthenticated** because the wizard gate must call it before any account
 * exists. This one is authenticated (MANAGER+): [hasFiscalTerminal] and [tseCommissioned] describe the
 * fiscal signing setup, and exposing that publicly would be reconnaissance for precisely the tampering
 * the TSE exists to prevent.
 *
 * The server owns the two non-trivial determinations rather than letting the client reassemble them
 * from `/api/v1/terminals` and `/api/tse/config`, so "ready to trade" has one definition.
 *
 * @property hasFiscalTerminal at least one active terminal with fiscal capability. A TSE cannot be
 *   commissioned without one, which is why the client renders the TSE row as *blocked* rather than
 *   failed while this is false.
 * @property tseCommissioned the TSE has been taken into service. Derived from persisted state only —
 *   see the service implementation for why this deliberately does not probe the device.
 * @property productCount and [tableCount] are counts rather than booleans so the client can show
 *   progress ("3 products") instead of just pass/fail, without a second call.
 * @property restaurantMode when false the shop is counter-service and has no tables, so the client
 *   omits the table row entirely rather than reporting a missing thing that does not apply.
 */
@Serializable
data class SetupReadinessDto(
    val hasFiscalTerminal: Boolean,
    val tseCommissioned: Boolean,
    val hasVatRates: Boolean,
    val hasPaymentTypes: Boolean,
    val productCount: Long,
    val tableCount: Long,
    val restaurantMode: Boolean,
)
