package org.example.project.admin.features.settings.presentation.sections

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.example.project.admin.core.auth.canConfigureSystem
import org.example.project.admin.core.presentation.components.DeleteConfirmDialog
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.auth.TokenStore
import org.example.project.core.domain.models.setup.VoucherSourceType
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.SettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.screens.settings.components.VoucherConfigManagementContent
import org.example.project.ui.screens.settings.components.VoucherSourceDialog
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Gutscheine — the voucher master switch and its source list, rendered by shared-ui's
 * [VoucherConfigManagementContent] and edited through shared-ui's [VoucherSourceDialog]: the same two
 * components composeApp's Business Settings tab uses, so the till and the web admin cannot drift on
 * which voucher kinds the shop accepts.
 *
 * Deleting the INTERNAL source while vouchers are enabled is blocked, because `VoucherConfig.isValid`
 * requires it. The server rejects that too — hiding the control here just means the operator does not
 * discover the rule through a 422.
 */
@Composable
fun VouchersSection(viewModel: VoucherConfigViewModel = koinViewModel()) {
    LaunchedEffect(Unit) { viewModel.refresh() }

    val language = LocalAppLanguage.current
    val tokenStore = koinInject<TokenStore>()
    // CONFIG_WRITE: what ConfigRules puts on PUT /api/v1/config/{shopId}/voucher-config. Was
    // CATALOG_MANAGE, a different capability, and the controls stayed visible either way (admin19 NOTE-j).
    val canEdit = tokenStore.canConfigureSystem
    var pendingDelete by remember { mutableStateOf<VoucherSourceType?>(null) }

    PosErrorWatcher(
        error = viewModel.state.error,
        title = str(SettingsKeys.voucherConfigSection),
        onClear = viewModel::clearError,
        retryAction = viewModel::refresh,
    )

    VoucherConfigManagementContent(
        voucherConfig = viewModel.state.voucherConfig,
        isLoading = viewModel.state.isLoading,
        language = language,
        onAddClick = { if (canEdit) viewModel.showAddDialog() },
        onEditClick = { if (canEdit) viewModel.showEditDialog(it) },
        onDeleteClick = { type ->
            if (canEdit && !viewModel.isSourceRequired(type)) pendingDelete = type
        },
        onToggleEnabled = { if (canEdit) viewModel.setEnabled(it) },
        modifier = Modifier.fillMaxSize(),
        editable = canEdit,
        // Shown disabled with the reason, rather than a trash icon that silently does nothing.
        isDeletable = { type -> !viewModel.isSourceRequired(type) },
    )

    if (viewModel.state.showAddDialog) {
        VoucherSourceDialog(
            editingSource = viewModel.state.editingSource,
            // Types already in use, so the dialog can stop the operator picking a duplicate. The one
            // being edited stays offered, otherwise its own type would be missing from its own form.
            existingTypes =
                viewModel.state.voucherConfig
                    ?.sources
                    ?.map { it.type }
                    ?.filterNot { it == viewModel.state.editingSource?.type }
                    .orEmpty(),
            language = language,
            onDismiss = viewModel::hideDialog,
            onConfirm = viewModel::upsertSource,
        )
    }

    pendingDelete?.let { type ->
        val sourceName =
            viewModel.state.voucherConfig
                ?.sources
                ?.firstOrNull { it.type == type }
                ?.name
                ?.takeIf { it.isNotBlank() } ?: str(voucherSourceTypeKey(type))
        DeleteConfirmDialog(
            entityType = str(AdminStringKeys.VoucherSourceEntity),
            entityName = sourceName,
            consequence = str(AdminStringKeys.VoucherSourceDeleteConsequence),
            onConfirm = {
                viewModel.deleteSource(type)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

private fun voucherSourceTypeKey(type: VoucherSourceType) =
    when (type) {
        VoucherSourceType.INTERNAL -> AdminStringKeys.VoucherSourceTypeInternal
        VoucherSourceType.LEGACY -> AdminStringKeys.VoucherSourceTypeLegacy
        VoucherSourceType.EXTERNAL -> AdminStringKeys.VoucherSourceTypeExternal
        VoucherSourceType.PROMOTIONAL -> AdminStringKeys.VoucherSourceTypePromotional
        VoucherSourceType.GIFT_CARD -> AdminStringKeys.VoucherSourceTypeGiftCard
    }
