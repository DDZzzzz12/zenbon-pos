package org.example.project.admin.features.zBon.closing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.presentation.components.notification.DisplayMode
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.screens.reports.components.CreateZBonConfirmDialog
import org.koin.compose.viewmodel.koinViewModel

/**
 * The adminApp's "Z-Bon erstellen" flow. [requestOpen] flips true when the operator presses the button;
 * the host opens the dialog once and reports it consumed through [onOpened]. [onCreated] receives the new
 * Z-Bon and the shop's currency from the server's readiness — the caller decides whether to navigate to it
 * (`ZBON_MANAGE`) or show the summary. The currency is [FALLBACK_CURRENCY] only while readiness is unknown.
 *
 * Rendered next to Compose content, never over a DOM overlay, so no `BlockDomOverlayWhileVisible()`;
 * call it first if this is ever hosted on the document scanner (its camera `<video>`).
 */
@Composable
fun ZBonClosingHost(
    requestOpen: Boolean,
    onOpened: () -> Unit,
    paysOutTips: Boolean,
    onOpenSigningSettings: (() -> Unit)? = null,
    onCreated: (ZBonDto.ZBonResponseDto, String) -> Unit,
    viewModel: ZBonClosingViewModel = koinViewModel(),
) {
    LaunchedEffect(requestOpen) {
        if (requestOpen) {
            viewModel.open(includeTipPayouts = paysOutTips)
            onOpened()
        }
    }
    val state = viewModel.state

    // DIALOG, not the default toast: every closing failure, including a business 409, must show the
    // server's detail, error code and trace id.
    PosErrorWatcher(
        error = state.error,
        title = str(AdminStringKeys.ZBonCloseTitle),
        onClear = viewModel::clearError,
        displayMode = DisplayMode.DIALOG,
    )

    state.created?.let { created ->
        LaunchedEffect(created.id) {
            onCreated(created, state.createdCurrency ?: FALLBACK_CURRENCY)
            viewModel.consumeCreated()
        }
    }

    val draft = state.draft ?: return
    CreateZBonConfirmDialog(
        language = LocalAppLanguage.current,
        draft = draft,
        currencyCode = draft.readiness?.currency?.takeIf { it.isNotBlank() } ?: FALLBACK_CURRENCY,
        onCountChange = viewModel::updateCount,
        onTipCheckedChange = viewModel::setTipChecked,
        onTipAmountChange = viewModel::updateTipAmount,
        onTipMethodChange = viewModel::setTipMethod,
        onTopUpSourceSelect = viewModel::selectTopUpSource,
        onTopUpAmountChange = viewModel::updateTopUpAmount,
        onOpenSigningSettings =
            onOpenSigningSettings?.let { open ->
                {
                    viewModel.dismiss()
                    open()
                }
            },
        onConfirm = viewModel::confirm,
        onDismiss = viewModel::dismiss,
        dirty = draft.isDirty,
    )
}

/** Shown only while the server's readiness (which carries the shop's currency) has not arrived. */
private const val FALLBACK_CURRENCY = "EUR"
