package org.example.project.admin.features.settings.presentation.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.example.project.admin.core.auth.holds
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.core.util.file.pickImage
import org.example.project.admin.core.util.resolveImageUrl
import org.example.project.core.auth.TokenStore
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.ShopInfoKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosTextField
import org.example.project.ui.guard.rememberDirtySource
import org.example.project.ui.screens.settings.components.ShopLogoSection
import org.example.project.ui.theme.LocalPosLayout
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Geschäftsdaten — the shop's master data and logo.
 *
 * The logo control is shared-ui's [ShopLogoSection], extracted from composeApp's `PosShopInfoContent`
 * precisely so both clients could use it: composeApp resolves the image through `SettingManager`'s base
 * URL and picks a file with CMPFilePicker, while adminApp resolves against the page origin and uses its
 * own browser `pickImage()` — CMPFilePicker does not target wasm.
 *
 * Everything else is a plain form. It is adminApp's own rather than shared-ui's `ShopInfoFormState`
 * because that state class has no `ustIdNr` field — see [ShopInfoViewModel] for why that matters.
 *
 * Editing is gated on MANAGER; the server enforces the same rule.
 */
@Composable
fun ShopDataSection(viewModel: ShopInfoViewModel = koinViewModel()) {
    LaunchedEffect(Unit) { viewModel.refresh() }

    val scope = rememberCoroutineScope()
    val tokenStore = koinInject<TokenStore>()
    val canEdit = tokenStore.holds(Capability.SHOP_INFO_MANAGE)
    val form = viewModel.state.form
    rememberDirtySource(dirty = viewModel.state.isDirty, onDiscard = viewModel::refresh)

    PosErrorWatcher(
        error = viewModel.state.error,
        title = str(ShopInfoKeys.screenTitle),
        onClear = viewModel::clearError,
        retryAction = viewModel::refresh,
    )

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(LocalPosLayout.current.screenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ShopLogoSection(
            logoUrl = form.logoUrl,
            isUploading = viewModel.state.isUploadingLogo,
            isDisabled = !canEdit,
            resolveImageUrl = { resolveImageUrl(it) },
            onPickImage = {
                scope.launch {
                    pickImage()?.let { picked -> viewModel.uploadLogo(picked.bytes, picked.fileName) }
                }
            },
            onRemoveLogo = viewModel::removeLogo,
        )

        FormCard(str(ShopInfoKeys.basicInfoSection)) {
            Field(str(ShopInfoKeys.shopName), form.shopName, canEdit) { v ->
                viewModel.updateForm { it.copy(shopName = v) }
            }
            Field(str(ShopInfoKeys.companyName), form.companyName, canEdit) { v ->
                viewModel.updateForm { it.copy(companyName = v) }
            }
            Field(str(ShopInfoKeys.address), form.shopAddress, canEdit) { v ->
                viewModel.updateForm { it.copy(shopAddress = v) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PosTextField(
                    value = form.zipCode,
                    onValueChange = { v -> viewModel.updateForm { it.copy(zipCode = v) } },
                    label = str(ShopInfoKeys.zipCode),
                    enabled = canEdit,
                    modifier = Modifier.weight(1f),
                )
                PosTextField(
                    value = form.shopCity,
                    onValueChange = { v -> viewModel.updateForm { it.copy(shopCity = v) } },
                    label = str(ShopInfoKeys.city),
                    enabled = canEdit,
                    modifier = Modifier.weight(2f),
                )
            }
            Field(str(ShopInfoKeys.country), form.shopCountry, canEdit) { v ->
                viewModel.updateForm { it.copy(shopCountry = v) }
            }
        }

        FormCard(str(ShopInfoKeys.contactInfoSection)) {
            Field(str(ShopInfoKeys.phone), form.shopPhone, canEdit) { v ->
                viewModel.updateForm { it.copy(shopPhone = v) }
            }
            Field(str(ShopInfoKeys.email), form.shopEmail, canEdit) { v ->
                viewModel.updateForm { it.copy(shopEmail = v) }
            }
            Field(str(ShopInfoKeys.website), form.shopWebsite, canEdit) { v ->
                viewModel.updateForm { it.copy(shopWebsite = v) }
            }
        }

        // Tax identifiers are fiscally load-bearing: shopTaxNumber becomes STNR and ustIdNr becomes
        // USTID in every DSFinV-K export and Z-Bon Stammdaten snapshot. They are two different
        // identifiers and must never be conflated.
        FormCard(str(ShopInfoKeys.financialInfoSection)) {
            Field(str(ShopInfoKeys.taxNumber), form.shopTaxNumber, canEdit) { v ->
                viewModel.updateForm { it.copy(shopTaxNumber = v) }
            }
            Field(str(AdminStringKeys.BootstrapUstIdNr), form.ustIdNr, canEdit) { v ->
                viewModel.updateForm { it.copy(ustIdNr = v) }
            }
            Field(str(ShopInfoKeys.bankName), form.shopBankName, canEdit) { v ->
                viewModel.updateForm { it.copy(shopBankName = v) }
            }
            Field(str(ShopInfoKeys.bankIban), form.shopBankIBAN, canEdit) { v ->
                viewModel.updateForm { it.copy(shopBankIBAN = v) }
            }
            Field(str(ShopInfoKeys.bankBic), form.shopBankBic, canEdit) { v ->
                viewModel.updateForm { it.copy(shopBankBic = v) }
            }
        }

        PosCard(modifier = Modifier.fillMaxWidth()) {
            body {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(str(ShopInfoKeys.restaurantMode), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = str(ShopInfoKeys.restaurantModeDescription),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // raw Material 3 by design: no Pos* switch component exists
                    Switch(
                        checked = form.restaurantMode,
                        onCheckedChange = { v -> viewModel.updateForm { it.copy(restaurantMode = v) } },
                        enabled = canEdit,
                    )
                }
            }
        }

        if (canEdit) {
            PosButton(
                text = str(CommonKeys.save),
                onClick = viewModel::save,
                enabled = !viewModel.state.isSaving && form.shopName.isNotBlank(),
            )
        }
    }
}

@Composable
private fun FormCard(
    title: String,
    content: @Composable () -> Unit,
) {
    PosCard(modifier = Modifier.fillMaxWidth()) {
        body {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                content()
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    PosTextField(
        value = value,
        onValueChange = onChange,
        label = label,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}
