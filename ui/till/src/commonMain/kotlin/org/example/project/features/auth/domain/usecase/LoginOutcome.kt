package org.example.project.features.auth.domain.usecase

/**
 * Result of [LoginUseCase.invoke].
 *
 * @property language the employee's preferred language code, or null if none.
 * @property needsPairing true when background terminal registration returned
 *   TERMINAL_NEEDS_PAIRING (409) — tokens are already stored & valid; the device
 *   must pair before proceeding.
 */
data class LoginOutcome(
    val language: String?,
    val needsPairing: Boolean,
)
