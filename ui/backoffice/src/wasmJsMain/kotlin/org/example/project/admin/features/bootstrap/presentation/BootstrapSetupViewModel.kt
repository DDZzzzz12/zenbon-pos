package org.example.project.admin.features.bootstrap.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.launch
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.setup.InitialSetupDto
import org.example.project.core.network.error.onError
import org.example.project.core.network.error.runCatchingApi
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.error.toUiError
import org.example.project.core.presentation.strings.StringKey
import org.example.project.features.setup.data.remote.SetupApiClient

/**
 * Drives the pre-auth new-server bootstrap (Terminal Phase RB3 Part A): probe `GET /api/setup/status`,
 * and if the server is not initialized, step the operator through an OWNER → SHOP wizard that submits
 * `POST /api/setup/initialize`. On success the status flips to INITIALIZED and the gate shows the login
 * screen. Alternatively, the operator can upload a backup ZIP instead of completing the wizard
 * ([restoreFromBackup]); it takes the same INITIALIZED exit because the restored backup already carries
 * its own owner account. All calls reuse the shared unauthenticated [SetupApiClient]; no server route
 * changes.
 */
class BootstrapSetupViewModel(
    private val setupApiClient: SetupApiClient,
) : ViewModel() {
    private val log = Logger.withTag("bootstrap")

    var state by mutableStateOf(BootstrapSetupState())
        private set

    /**
     * Runs once per session. `isInitialized=false` (equivalently `requiresSetup`) arms the wizard;
     * any probe failure falls through to INITIALIZED so login is shown rather than trapping the user.
     */
    fun evaluateBootstrap() {
        if (state.evaluated) return
        viewModelScope.launch {
            runCatchingApi { setupApiClient.checkSetupStatus() }
                .onSuccess { status ->
                    state =
                        state.copy(
                            evaluated = true,
                            status = if (status.isInitialized) BootstrapStatus.INITIALIZED else BootstrapStatus.NEEDS_BOOTSTRAP,
                        )
                }.onError { uiError ->
                    log.e { "setup-status probe failed — defaulting to login" }
                    state = state.copy(evaluated = true, status = BootstrapStatus.INITIALIZED, error = uiError)
                }
        }
    }

    fun clearError() {
        state = state.copy(error = null)
    }

    private fun setError(e: Throwable) {
        state = state.copy(error = e.toUiError())
    }

    fun updateField(
        field: BootstrapField,
        value: String,
    ) {
        val cleared = state.validationErrors - field
        state =
            when (field) {
                BootstrapField.ADMIN_USERNAME -> state.copy(adminUsername = value)
                BootstrapField.ADMIN_PASSWORD -> state.copy(adminPassword = value)
                BootstrapField.ADMIN_PASSWORD_CONFIRM -> state.copy(adminPasswordConfirm = value)
                BootstrapField.ADMIN_EMAIL -> state.copy(adminEmail = value)
                BootstrapField.ADMIN_FIRST_NAME -> state.copy(adminFirstName = value)
                BootstrapField.ADMIN_LAST_NAME -> state.copy(adminLastName = value)
                BootstrapField.ADMIN_PIN -> state.copy(adminPin = value)
                BootstrapField.SHOP_NAME -> state.copy(shopName = value)
                BootstrapField.COMPANY_NAME -> state.copy(companyName = value)
                BootstrapField.SHOP_ADDRESS -> state.copy(shopAddress = value)
                BootstrapField.SHOP_CITY -> state.copy(shopCity = value)
                BootstrapField.ZIP_CODE -> state.copy(zipCode = value)
                BootstrapField.SHOP_PHONE -> state.copy(shopPhone = value)
                BootstrapField.SHOP_EMAIL -> state.copy(shopEmail = value)
                BootstrapField.SHOP_TAX_NUMBER -> state.copy(shopTaxNumber = value)
                BootstrapField.UST_ID_NR -> state.copy(ustIdNr = value)
                BootstrapField.SHOP_BANK_NAME -> state.copy(shopBankName = value)
                BootstrapField.SHOP_BANK_BIC -> state.copy(shopBankBic = value)
                BootstrapField.SHOP_BANK_IBAN -> state.copy(shopBankIBAN = value)
                BootstrapField.SHOP_WEBSITE -> state.copy(shopWebsite = value)
                BootstrapField.SHOP_TIMEZONE -> state.copy(shopTimezone = value)
                BootstrapField.SHOP_COUNTRY -> state.copy(shopCountry = value)
                BootstrapField.CURRENCY_CODE -> state.copy(currencyCode = value)
            }.copy(validationErrors = cleared)
    }

    fun nextStep() {
        if (validateOwner()) state = state.copy(step = BootstrapStep.SHOP, error = null)
    }

    fun previousStep() {
        state = state.copy(step = BootstrapStep.OWNER, error = null)
    }

    /**
     * Step 2 submit — build [InitialSetupDto.CompleteSetupRequestDto] from owner+shop and POST it to
     * `/api/setup/initialize`. The server auto-configures payment types, VAT rates and defaults. On
     * success the status flips to INITIALIZED so the gate shows login; the operator then signs in with
     * the owner credentials just created.
     */
    fun submitBootstrap() {
        if (!validateShop()) return
        val s = state
        val request =
            InitialSetupDto.CompleteSetupRequestDto(
                owner =
                    InitialSetupDto.OwnerSetupRequestDto(
                        adminUsername = s.adminUsername.trim(),
                        adminPassword = s.adminPassword,
                        adminEmail = s.adminEmail.trim(),
                        adminFirstName = s.adminFirstName.trim(),
                        adminLastName = s.adminLastName.trim(),
                        adminPin = s.adminPin.trim(),
                    ),
                shop =
                    InitialSetupDto.ShopSetupRequestDto(
                        shopName = s.shopName.trim(),
                        companyName = s.companyName.trim().ifBlank { null },
                        shopAddress = s.shopAddress.trim(),
                        shopCity = s.shopCity.trim(),
                        zipCode = s.zipCode.trim().toIntOrNull() ?: 0,
                        shopCountry = s.shopCountry.trim(),
                        shopPhone = s.shopPhone.trim(),
                        shopTaxNumber = s.shopTaxNumber.trim().ifBlank { null },
                        ustIdNr =
                            s.ustIdNr
                                .trim()
                                .uppercase()
                                .ifBlank { null },
                        shopBankName = s.shopBankName.trim().ifBlank { null },
                        shopBankBic = s.shopBankBic.trim().ifBlank { null },
                        shopBankIBAN = s.shopBankIBAN.trim().ifBlank { null },
                        shopEmail = s.shopEmail.trim().ifBlank { null },
                        shopTimezone = s.shopTimezone.trim().ifBlank { "Europe/Berlin" },
                        shopWebsite = s.shopWebsite.trim().ifBlank { null },
                        currencyCode = s.currencyCode.trim(),
                    ),
            )
        state = state.copy(isSubmitting = true, error = null)
        viewModelScope.launch {
            runCatchingApi { setupApiClient.executeCompleteSetup(request) }
                .onSuccess {
                    log.i { "owner/shop bootstrap complete — routing to login" }
                    state = state.copy(isSubmitting = false, isComplete = true, status = BootstrapStatus.INITIALIZED)
                }.onError { uiError ->
                    log.e { "bootstrap submit failed" }
                    state = state.copy(isSubmitting = false, error = uiError)
                }
        }
    }

    /**
     * Restores a whole installation from a backup ZIP instead of running the wizard. Bytes come in
     * from the screen's file picker — the VM never touches the DOM, so this stays unit-testable.
     * On success the status flips to INITIALIZED and the gate shows login, exactly as a completed
     * wizard does; the restored backup already contains its own owner account.
     */
    fun restoreFromBackup(backupData: ByteArray) {
        log.i { "restoring from backup, ${backupData.size} bytes" }
        state = state.copy(isRestoring = true, error = null)
        viewModelScope.launch {
            runCatchingApi { setupApiClient.restoreBackupForSetup(backupData) }
                .onSuccess {
                    log.i { "restore complete — routing to login" }
                    state =
                        state.copy(
                            isRestoring = false,
                            status = BootstrapStatus.INITIALIZED,
                        )
                }.onError { uiError ->
                    log.e { "restore failed" }
                    state = state.copy(isRestoring = false, error = uiError)
                }
        }
    }

    /**
     * The browser could not read the chosen file. Surfaced as a typed error rather than swallowed —
     * a silent no-op leaves the operator unable to tell a failed read from a click that never landed.
     */
    fun onBackupFileUnreadable() {
        log.w { "backup file could not be read" }
        state =
            state.copy(
                error = UiError.Unknown(messageKey = AdminStringKeys.BootstrapBackupUnreadable),
            )
    }

    private fun validateOwner(): Boolean {
        val e = mutableMapOf<BootstrapField, StringKey>()
        if (state.adminUsername.isBlank()) e[BootstrapField.ADMIN_USERNAME] = AdminStringKeys.BootstrapErrorRequired
        if (state.adminPassword.length < 6) e[BootstrapField.ADMIN_PASSWORD] = AdminStringKeys.BootstrapErrorPasswordShort
        if (state.adminPassword !=
            state.adminPasswordConfirm
        ) {
            e[BootstrapField.ADMIN_PASSWORD_CONFIRM] = AdminStringKeys.BootstrapErrorPasswordMismatch
        }
        if (state.adminEmail.isBlank() ||
            !state.adminEmail.contains("@")
        ) {
            e[BootstrapField.ADMIN_EMAIL] = AdminStringKeys.BootstrapErrorEmail
        }
        // Required because the server writes them to `employee_info`, where DSFinV-K's
        // BEDIENER_NAME comes from. Caught here so a blank name is a field error on step 1 rather
        // than a 400 at the end of step 2.
        if (state.adminFirstName.isBlank()) e[BootstrapField.ADMIN_FIRST_NAME] = AdminStringKeys.BootstrapErrorRequired
        if (state.adminLastName.isBlank()) e[BootstrapField.ADMIN_LAST_NAME] = AdminStringKeys.BootstrapErrorRequired
        if (state.adminPin.isBlank()) e[BootstrapField.ADMIN_PIN] = AdminStringKeys.BootstrapErrorRequired
        state = state.copy(validationErrors = e)
        return e.isEmpty()
    }

    private fun validateShop(): Boolean {
        val e = mutableMapOf<BootstrapField, StringKey>()
        if (state.shopName.isBlank()) e[BootstrapField.SHOP_NAME] = AdminStringKeys.BootstrapErrorRequired
        if (state.shopAddress.isBlank()) e[BootstrapField.SHOP_ADDRESS] = AdminStringKeys.BootstrapErrorRequired
        if (state.shopCity.isBlank()) e[BootstrapField.SHOP_CITY] = AdminStringKeys.BootstrapErrorRequired
        val zip = state.zipCode.trim().toIntOrNull()
        if (zip == null || state.zipCode.trim().length !in 3..10) e[BootstrapField.ZIP_CODE] = AdminStringKeys.BootstrapErrorZip
        if (state.shopPhone.isBlank()) e[BootstrapField.SHOP_PHONE] = AdminStringKeys.BootstrapErrorRequired
        if (state.shopEmail.isNotBlank() &&
            !state.shopEmail.contains("@")
        ) {
            e[BootstrapField.SHOP_EMAIL] = AdminStringKeys.BootstrapErrorEmail
        }
        state = state.copy(validationErrors = e)
        return e.isEmpty()
    }
}
