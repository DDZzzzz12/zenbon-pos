package org.example.project.admin.features.bootstrap.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.core.util.file.pickFile
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.SetupWizardKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.file.FilePickResult
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosTextField
import org.koin.compose.viewmodel.koinViewModel

/**
 * Pre-auth owner/shop bootstrap wizard (Terminal Phase RB3 Part A). Shown by the [BootstrapGate]
 * in `AdminApp` when `GET /api/setup/status` reports the server is not initialized. On submit
 * success the VM flips [BootstrapStatus.INITIALIZED] and the gate routes to the login screen.
 */
@Composable
fun BootstrapSetupScreen(viewModel: BootstrapSetupViewModel = koinViewModel()) {
    val state = viewModel.state

    PosErrorWatcher(
        error = state.error,
        title = str(AdminStringKeys.BootstrapTitle),
        onClear = viewModel::clearError,
        // This wizard IS the unauthenticated state — no account exists yet. The default
        // sign-out suppression would silently swallow every error here (a rejected VAT ID,
        // a failed restore, a duplicate username), leaving the operator with a dead button.
        suppressWhenSignedOut = false,
    )

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(str(AdminStringKeys.BootstrapTitle), style = MaterialTheme.typography.headlineSmall)
            Text(
                str(AdminStringKeys.BootstrapIntro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (state.step) {
                BootstrapStep.OWNER -> OwnerStep(viewModel, state)
                BootstrapStep.SHOP -> ShopStep(viewModel, state)
            }
        }
    }
}

@Composable
private fun OwnerStep(
    viewModel: BootstrapSetupViewModel,
    state: BootstrapSetupState,
) {
    val scope = rememberCoroutineScope()
    val busy = state.isSubmitting || state.isRestoring
    var picking by remember { mutableStateOf(false) }

    PosCard(Modifier.fillMaxWidth()) {
        body {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(str(AdminStringKeys.BootstrapOwnerHeading), style = MaterialTheme.typography.titleMedium)
                Field(state, BootstrapField.ADMIN_USERNAME, AdminStringKeys.BootstrapAdminUsername, state.adminUsername, viewModel)
                Field(state, BootstrapField.ADMIN_PASSWORD, AdminStringKeys.BootstrapAdminPassword, state.adminPassword, viewModel)
                Field(
                    state,
                    BootstrapField.ADMIN_PASSWORD_CONFIRM,
                    AdminStringKeys.BootstrapAdminPasswordConfirm,
                    state.adminPasswordConfirm,
                    viewModel,
                )
                Field(state, BootstrapField.ADMIN_EMAIL, AdminStringKeys.BootstrapAdminEmail, state.adminEmail, viewModel)
                Field(state, BootstrapField.ADMIN_FIRST_NAME, AdminStringKeys.BootstrapAdminFirstName, state.adminFirstName, viewModel)
                Field(state, BootstrapField.ADMIN_LAST_NAME, AdminStringKeys.BootstrapAdminLastName, state.adminLastName, viewModel)
                Field(state, BootstrapField.ADMIN_PIN, AdminStringKeys.BootstrapAdminPin, state.adminPin, viewModel)
                PosButton(text = str(AdminStringKeys.BootstrapNext), onClick = viewModel::nextStep, enabled = !busy)

                // "OR restore from a backup" — the alternative to filling in the wizard at all.
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    HorizontalDivider(Modifier.weight(1f))
                    Text(
                        str(SetupWizardKeys.or),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(Modifier.weight(1f))
                }
                Text(
                    text = str(SetupWizardKeys.restoreDescription),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                PosSecondaryButton(
                    text =
                        if (state.isRestoring) {
                            str(SetupWizardKeys.restoring)
                        } else {
                            str(SetupWizardKeys.selectBackupFile)
                        },
                    onClick = {
                        picking = true
                        scope.launch {
                            try {
                                when (val result = pickFile("application/zip,.zip")) {
                                    is FilePickResult.Picked ->
                                        viewModel.restoreFromBackup(result.file.bytes)
                                    FilePickResult.ReadFailed ->
                                        viewModel.onBackupFileUnreadable()
                                    FilePickResult.Cancelled -> Unit // deliberate: silence
                                }
                            } finally {
                                picking = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && !picking,
                    isLoading = state.isRestoring,
                    icon = Icons.Default.Restore,
                )
            }
        }
    }
}

@Composable
private fun ShopStep(
    viewModel: BootstrapSetupViewModel,
    state: BootstrapSetupState,
) {
    PosCard(Modifier.fillMaxWidth()) {
        body {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(str(AdminStringKeys.BootstrapShopHeading), style = MaterialTheme.typography.titleMedium)
                Field(state, BootstrapField.SHOP_NAME, AdminStringKeys.BootstrapShopName, state.shopName, viewModel)
                Field(state, BootstrapField.COMPANY_NAME, AdminStringKeys.BootstrapCompanyName, state.companyName, viewModel)
                Field(state, BootstrapField.SHOP_ADDRESS, AdminStringKeys.BootstrapShopAddress, state.shopAddress, viewModel)
                Field(state, BootstrapField.SHOP_CITY, AdminStringKeys.BootstrapShopCity, state.shopCity, viewModel)
                Field(state, BootstrapField.ZIP_CODE, AdminStringKeys.BootstrapZipCode, state.zipCode, viewModel)
                Field(state, BootstrapField.SHOP_PHONE, AdminStringKeys.BootstrapShopPhone, state.shopPhone, viewModel)
                Field(state, BootstrapField.SHOP_EMAIL, AdminStringKeys.BootstrapShopEmail, state.shopEmail, viewModel)
                Field(state, BootstrapField.SHOP_TAX_NUMBER, AdminStringKeys.BootstrapShopTaxNumber, state.shopTaxNumber, viewModel)
                Field(state, BootstrapField.UST_ID_NR, AdminStringKeys.BootstrapUstIdNr, state.ustIdNr, viewModel)
                Field(state, BootstrapField.SHOP_BANK_NAME, AdminStringKeys.BootstrapShopBankName, state.shopBankName, viewModel)
                Field(state, BootstrapField.SHOP_BANK_BIC, AdminStringKeys.BootstrapShopBankBic, state.shopBankBic, viewModel)
                Field(state, BootstrapField.SHOP_BANK_IBAN, AdminStringKeys.BootstrapShopBankIban, state.shopBankIBAN, viewModel)
                Field(state, BootstrapField.SHOP_WEBSITE, AdminStringKeys.BootstrapShopWebsite, state.shopWebsite, viewModel)
                Field(state, BootstrapField.SHOP_TIMEZONE, AdminStringKeys.BootstrapShopTimezone, state.shopTimezone, viewModel)
                Field(state, BootstrapField.SHOP_COUNTRY, AdminStringKeys.BootstrapShopCountry, state.shopCountry, viewModel)
                Field(state, BootstrapField.CURRENCY_CODE, AdminStringKeys.BootstrapCurrencyCode, state.currencyCode, viewModel)
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PosButton(
                        text = str(AdminStringKeys.BootstrapSubmit),
                        onClick = viewModel::submitBootstrap,
                        enabled = !state.isSubmitting,
                        isLoading = state.isSubmitting,
                    )
                    PosSecondaryButton(text = str(AdminStringKeys.BootstrapBack), onClick = viewModel::previousStep)
                }
            }
        }
    }
}

@Composable
private fun Field(
    state: BootstrapSetupState,
    field: BootstrapField,
    label: StringKey,
    value: String,
    viewModel: BootstrapSetupViewModel,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        PosTextField(
            value = value,
            onValueChange = { viewModel.updateField(field, it) },
            label = str(label),
            enabled = !state.isSubmitting,
            visualTransformation = if (field in SECRET_FIELDS) PasswordVisualTransformation() else VisualTransformation.None,
        )
        state.validationErrors[field]?.let { key ->
            Text(str(key), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Rendered masked: the wizard is often filled in on a shared or projected screen (E2E round 4, R4-10). */
private val SECRET_FIELDS =
    setOf(BootstrapField.ADMIN_PASSWORD, BootstrapField.ADMIN_PASSWORD_CONFIRM, BootstrapField.ADMIN_PIN)
