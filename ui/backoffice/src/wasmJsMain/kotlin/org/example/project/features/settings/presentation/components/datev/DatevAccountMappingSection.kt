/**
 * DATEV Account-Mapping Settings Section Component.
 *
 * Lets a shop configure the DATEV account mapping (spec §3.3) used by the
 * Kassenbuch/Buchungsstapel export: kasse account, per-kind/per-rate income
 * and expense accounts, and per-payment-type clearing accounts. Ships with
 * SKR03/SKR04 seed defaults the user can apply and then adjust.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.settings.presentation.components.datev

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.example.project.core.domain.enums.datev.KassenbuchSpecialBooking
import org.example.project.core.domain.enums.datev.Kontenrahmen
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.DatevSettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.settings.presentation.state.DatevAccountMappingCellState
import org.example.project.features.settings.presentation.state.DatevAccountMappingState
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosSegmentedButtonRow
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.components.PosTextField
import org.example.project.ui.components.kassenbuch.kindLabel
import org.example.project.ui.layout.PaneBreakpoints

/** False until the mapping has loaded: the default cells would otherwise all be flagged "Konto fehlt". */
private val LocalFlagMissingKonto = compositionLocalOf { true }

/** Width of one Konto/BU block when blocks sit side by side (content >= PHONE_MAX). */
private val CELL_BLOCK_WIDTH: Dp = 260.dp

@Composable
fun DatevAccountMappingSection(
    state: DatevAccountMappingState,
    onKontenrahmenSelected: (Kontenrahmen) -> Unit,
    onRequestApplyDefaults: () -> Unit,
    onConfirmApplyDefaults: () -> Unit,
    onCancelApplyDefaults: () -> Unit,
    onKasseKontoChange: (String) -> Unit,
    onCellChange: (isIncome: Boolean, kind: KassenbuchLineKind, rate: String, konto: String, bu: String) -> Unit,
    onPaymentCellChange: (paymentTypeId: Long, konto: String, bu: String) -> Unit,
    onSpecialCellChange: (slot: KassenbuchSpecialBooking, konto: String, bu: String) -> Unit,
    autoAssign: DatevAutoAssignCallbacks,
    onLoad: () -> Unit,
    onSave: () -> Unit,
) {
    LaunchedEffect(Unit) { onLoad() }

    // A mapping that has not loaded (still loading, or the load failed) holds the default state, whose
    // cells all read "automatic, no Konto": reporting those as missing would be a false alarm.
    val missingCells = if (state.loaded) missingKontoCells(state) else emptyList()
    val requesters = remember { mutableMapOf<String, BringIntoViewRequester>() }
    val requesterFor: (String) -> BringIntoViewRequester = { key -> requesters.getOrPut(key) { BringIntoViewRequester() } }
    val scope = rememberCoroutineScope()

    CompositionLocalProvider(LocalFlagMissingKonto provides state.loaded) {
        PosCard(
            modifier = Modifier.fillMaxWidth(),
            variant = PosCardVariant.Plain,
        ) {
            body {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (missingCells.isNotEmpty()) {
                        MissingKontoBanner(
                            count = missingCells.size,
                            onGoToFirst = { scope.launch { requesterFor(missingCells.first()).bringIntoView() } },
                        )
                    }

                    // Kontenrahmen selector (SKR03/SKR04)
                    PosSegmentedButtonRow(
                        options = Kontenrahmen.entries,
                        selectedOption = state.kontenrahmen,
                        onOptionSelected = onKontenrahmenSelected,
                    ) { kontenrahmen -> kontenrahmen.name }

                    PosSecondaryButton(
                        text = str(DatevSettingsKeys.applyDefaults),
                        onClick = onRequestApplyDefaults,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    PosTextField(
                        value = state.kasseKonto,
                        onValueChange = onKasseKontoChange,
                        label = str(DatevSettingsKeys.kasseKontoLabel),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Text(
                        text = str(DatevSettingsKeys.steuerberaterNote),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = str(DatevSettingsKeys.autoAssignHint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    HorizontalDivider()

                    Text(
                        text = str(DatevSettingsKeys.incomeSide),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    DATEV_RATES_PER_KIND.forEach { (kind, rates) ->
                        KindMappingRow(
                            label = kindLabel(kind),
                            isIncome = true,
                            kind = kind,
                            requesterFor = requesterFor,
                            rates = rates,
                            cellFor = { rate -> state.income[kind]?.get(rate) ?: DatevAccountMappingCellState() },
                            onCellChange = { rate, konto, bu -> onCellChange(true, kind, rate, konto, bu) },
                            onAutoAssignChange = { rate, on -> autoAssign.onRateCell(true, kind, rate, on) },
                        )
                    }

                    HorizontalDivider()

                    Text(
                        text = str(DatevSettingsKeys.expenseSide),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    DATEV_RATES_PER_KIND.forEach { (kind, rates) ->
                        KindMappingRow(
                            label = expenseKindLabel(kind),
                            isIncome = false,
                            kind = kind,
                            requesterFor = requesterFor,
                            rates = rates,
                            cellFor = { rate -> state.expense[kind]?.get(rate) ?: DatevAccountMappingCellState() },
                            onCellChange = { rate, konto, bu -> onCellChange(false, kind, rate, konto, bu) },
                            onAutoAssignChange = { rate, on -> autoAssign.onRateCell(false, kind, rate, on) },
                        )
                    }

                    HorizontalDivider()

                    Text(
                        text = str(DatevSettingsKeys.paymentTypesLabel),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    state.paymentTypes.forEach { row ->
                        AccountCellRow(
                            label = displayPaymentTypeName(row.name),
                            requester = requesterFor(datevPaymentCellKey(row.paymentTypeId)),
                            cell = row.cell,
                            onCellChange = { konto, bu -> onPaymentCellChange(row.paymentTypeId, konto, bu) },
                            onAutoAssignChange = { on -> autoAssign.onPaymentCell(row.paymentTypeId, on) },
                        )
                    }

                    HorizontalDivider()

                    Text(
                        text = str(DatevSettingsKeys.specialSide),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = str(DatevSettingsKeys.specialHint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PosTextButton(
                        text = str(DatevSettingsKeys.switchOffAllSpecial),
                        onClick = autoAssign.onSwitchOffAllSpecial,
                    )
                    KassenbuchSpecialBooking.entries.forEach { slot ->
                        val cell = state.special[slot] ?: DatevAccountMappingCellState()
                        AccountCellRow(
                            label = str(specialLabel(slot)),
                            requester = requesterFor(datevSpecialCellKey(slot)),
                            cell = cell,
                            onCellChange = { konto, bu -> onSpecialCellChange(slot, konto, bu) },
                            onAutoAssignChange = { on -> autoAssign.onSpecialCell(slot, on) },
                        )
                    }

                    state.validationErrorKey?.let { errorKey ->
                        Text(
                            text = str(errorKey),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    // Save is disabled until a full load succeeded: a full-replace PUT built
                    // from partially loaded state would wipe previously saved mappings.
                    PosButton(
                        text = str(CommonKeys.save),
                        onClick = onSave,
                        enabled = state.loaded,
                        isLoading = state.isSaving,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    if (state.showSeedConfirm) {
        PosDialog(
            onDismiss = onCancelApplyDefaults,
            title = str(DatevSettingsKeys.applyDefaults),
            kind = PosDialogKind.Confirm,
            primary = PosDialogAction(str(CommonKeys.confirm), onConfirmApplyDefaults),
        ) {
            Text(text = str(DatevSettingsKeys.applyDefaultsConfirm))
        }
    }
}

/**
 * One line-kind row: label, then one block per applicable VAT rate. Blocks are laid out by
 * [CellBlockFlow]: full width and stacked on a phone, side by side when there is room.
 */
@Composable
private fun KindMappingRow(
    label: String,
    isIncome: Boolean,
    kind: KassenbuchLineKind,
    requesterFor: (String) -> BringIntoViewRequester,
    rates: List<String>,
    cellFor: (rate: String) -> DatevAccountMappingCellState,
    onCellChange: (rate: String, konto: String, bu: String) -> Unit,
    onAutoAssignChange: (rate: String, autoAssign: Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        CellBlockFlow { blockModifier ->
            rates.forEach { rate ->
                CellBlock(
                    kontoLabel = "$rate % ${str(DatevSettingsKeys.kontoFieldLabel)}",
                    cell = cellFor(rate),
                    onCellChange = { konto, bu -> onCellChange(rate, konto, bu) },
                    onAutoAssignChange = { onAutoAssignChange(rate, it) },
                    requester = requesterFor(datevRateCellKey(isIncome, kind, rate)),
                    modifier = blockModifier,
                )
            }
        }
    }
}

/** The „Konto automatisch zuordnen" switch with its label; off disables the cell's Konto/BU fields. */
@Composable
private fun AutoAssignSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Switch(checked = checked, onCheckedChange = onCheckedChange)
        Text(text = str(DatevSettingsKeys.autoAssignLabel), style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * One labelled Konto/BU block (a payment type or a special booking). Highlights the "Konto fehlt"
 * (account missing) state when the Konto field is blank while automatic assignment is on — such a
 * cell must be mapped before the server accepts the mapping. Switched off, the fields are disabled
 * and may stay empty.
 */
@Composable
private fun AccountCellRow(
    label: String,
    requester: BringIntoViewRequester,
    cell: DatevAccountMappingCellState,
    onCellChange: (konto: String, bu: String) -> Unit,
    onAutoAssignChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        CellBlockFlow { blockModifier ->
            CellBlock(
                kontoLabel = str(DatevSettingsKeys.kontoFieldLabel),
                cell = cell,
                onCellChange = onCellChange,
                onAutoAssignChange = onAutoAssignChange,
                requester = requester,
                modifier = blockModifier,
            )
        }
    }
}

/**
 * Lays out [content]'s blocks: each block is full width (so its fields are full width) on a phone,
 * and a fixed [CELL_BLOCK_WIDTH] wrapping in a row otherwise. No scroll of any kind: this sits in
 * the Datenexport page's single vertical scroll and `PosHorizontalScroller` is gone.
 */
@Composable
private fun CellBlockFlow(content: @Composable (blockModifier: Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val blockModifier =
            if (maxWidth < PaneBreakpoints.PHONE_MAX) Modifier.fillMaxWidth() else Modifier.width(CELL_BLOCK_WIDTH)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content(blockModifier) }
    }
}

/** Switch, Konto field, BU field, top to bottom. Switching assignment off disables both fields. */
@Composable
private fun CellBlock(
    kontoLabel: String,
    cell: DatevAccountMappingCellState,
    onCellChange: (konto: String, bu: String) -> Unit,
    onAutoAssignChange: (Boolean) -> Unit,
    requester: BringIntoViewRequester,
    modifier: Modifier = Modifier,
) {
    val kontoMissing = cell.isKontoMissing() && LocalFlagMissingKonto.current
    Column(modifier.bringIntoViewRequester(requester), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AutoAssignSwitch(checked = cell.autoAssign, onCheckedChange = onAutoAssignChange)
        PosTextField(
            value = cell.konto,
            onValueChange = { onCellChange(it, cell.bu) },
            label = kontoLabel,
            enabled = cell.autoAssign,
            modifier = Modifier.fillMaxWidth(),
            isError = kontoMissing,
            supportingText =
                if (kontoMissing) {
                    { KontoMissingText() }
                } else {
                    null
                },
        )
        // BU only exists while the account is assigned; switched off the field is hidden, not disabled.
        if (cell.autoAssign) {
            PosTextField(
                value = cell.bu,
                onValueChange = { onCellChange(cell.konto, it) },
                label = str(DatevSettingsKeys.buFieldLabel),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The helper text of a cell lacking its account: an icon next to the words, so it is not colour alone. */
@Composable
private fun KontoMissingText() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Text(text = str(DatevSettingsKeys.kontoMissing))
    }
}

/** "N Konten fehlen" with a button that scrolls to the first such cell. Saving stays possible. */
@Composable
private fun MissingKontoBanner(
    count: Int,
    onGoToFirst: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = Icons.Default.Error,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text =
                        if (count == 1) {
                            str(DatevSettingsKeys.missingBannerOne)
                        } else {
                            str(DatevSettingsKeys.missingBannerMany, count)
                        },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            PosTextButton(text = str(DatevSettingsKeys.missingGoToFirst), onClick = onGoToFirst)
        }
    }
}
