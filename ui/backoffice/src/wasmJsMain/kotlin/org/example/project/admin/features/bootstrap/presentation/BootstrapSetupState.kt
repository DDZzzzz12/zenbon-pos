package org.example.project.admin.features.bootstrap.presentation

import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.StringKey

/**
 * Immutable UI state for the pre-auth owner/shop bootstrap wizard (Terminal Phase RB3 Part A).
 * Field values mirror composeApp's `SetupWizardState`; validation is typed via [validationErrors]
 * (field → localized message key) per adminApp conventions (no raw String messages).
 *
 * @property evaluated Guards [BootstrapSetupViewModel.evaluateBootstrap] to probe once per session.
 * @property status Probe outcome driving the gate; starts PROBING.
 */
data class BootstrapSetupState(
    val evaluated: Boolean = false,
    val status: BootstrapStatus = BootstrapStatus.PROBING,
    val step: BootstrapStep = BootstrapStep.OWNER,
    val isSubmitting: Boolean = false,
    val isRestoring: Boolean = false,
    val isComplete: Boolean = false,
    val error: UiError? = null,
    val validationErrors: Map<BootstrapField, StringKey> = emptyMap(),
    // Step 1 — owner
    val adminUsername: String = "",
    val adminPassword: String = "",
    val adminPasswordConfirm: String = "",
    val adminEmail: String = "",
    val adminFirstName: String = "",
    val adminLastName: String = "",
    val adminPin: String = "",
    // Step 2 — shop
    val shopName: String = "",
    val companyName: String = "",
    val shopAddress: String = "",
    val shopCity: String = "",
    val zipCode: String = "",
    val shopPhone: String = "",
    val shopEmail: String = "",
    val shopTaxNumber: String = "",
    val ustIdNr: String = "",
    val shopBankName: String = "",
    val shopBankBic: String = "",
    val shopBankIBAN: String = "",
    val shopWebsite: String = "",
    val shopTimezone: String = "Europe/Berlin",
    val shopCountry: String = "Germany",
    val currencyCode: String = "EUR",
)
