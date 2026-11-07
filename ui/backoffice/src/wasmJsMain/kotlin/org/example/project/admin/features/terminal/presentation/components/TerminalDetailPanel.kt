package org.example.project.admin.features.terminal.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.project.admin.core.presentation.components.adminTouchSize
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.terminal.presentation.BROWSER_CLIENT_KIND
import org.example.project.admin.features.terminal.presentation.TerminalEditDraft
import org.example.project.admin.features.terminal.presentation.TerminalPanelAction
import org.example.project.admin.features.terminal.presentation.TerminalPanelPlan
import org.example.project.admin.features.terminal.presentation.TerminalTseState
import org.example.project.admin.features.terminal.presentation.isTseMissing
import org.example.project.admin.features.terminal.presentation.tseDeregisterBlockReason
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.PosPatternKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.EMPTY_VALUE
import org.example.project.core.utils.formatShortDateTime
import org.example.project.core.utils.orDash
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.theme.LocalPosColors

/**
 * The detail panel of one terminal: what it is, the action its state calls for, and the form for
 * changing it. The same content is the 360dp side panel (large), the 400dp drawer (medium) and the
 * compact detail page; [TerminalPanelPlan] decides the actions so all three show the same ones.
 *
 * Read and edit are the same panel rather than a dialog on the wide layouts — the operator compares
 * the row they picked with the others still visible in the table. The compact layout opens the edit
 * form on its own page instead (`TerminalEditFields` + a bottom bar), so it never reaches the edit
 * branch of this panel.
 *
 * `fiscal` is shown but never editable. Whether a terminal signs is what DSFinV-K and the TSE client
 * registration rest on, and the server refuses to change it; offering the field and then failing the
 * save would be worse than not offering it. Every control is hidden without [canManage] and the
 * server re-checks (guards at both levels).
 *
 * [embedded] is for a host that already scrolls (the compact page): no own scroll, no own inset.
 * [onClose] adds the × to the header (side panel and drawer).
 */
@Composable
fun TerminalDetailPanel(
    terminal: TerminalDto.TerminalSummaryDto?,
    edit: TerminalEditDraft?,
    dirty: Boolean,
    inactiveBrowserStation: Boolean,
    canManage: Boolean,
    busy: Boolean,
    generatingCode: Boolean,
    onGenerateCode: () -> Unit,
    onRegisterTse: () -> Unit,
    onDeregisterTse: () -> Unit,
    onRevoke: () -> Unit,
    onDeactivate: () -> Unit,
    onReactivate: () -> Unit,
    onBeginEdit: () -> Unit,
    onEditField: ((TerminalEditDraft) -> TerminalEditDraft) -> Unit,
    onSave: () -> Unit,
    onCancelEdit: () -> Unit,
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
    onClose: (() -> Unit)? = null,
) {
    if (terminal == null) {
        NothingSelected(modifier)
        return
    }
    Column(
        (if (embedded) modifier.fillMaxWidth() else modifier.fillMaxSize().verticalScroll(rememberScrollState()))
            .padding(if (embedded) 0.dp else 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (edit == null) {
            val actions =
                PanelActions(
                    busy = busy,
                    generatingCode = generatingCode,
                    onGenerateCode = onGenerateCode,
                    onRegisterTse = onRegisterTse,
                    onDeregisterTse = onDeregisterTse,
                    onRevoke = onRevoke,
                    onDeactivate = onDeactivate,
                    onReactivate = onReactivate,
                    onBeginEdit = onBeginEdit,
                )
            TerminalReadBody(terminal, inactiveBrowserStation, canManage, actions, onClose)
        } else {
            TerminalEditBody(terminal, edit, dirty, onEditField, onSave, onCancelEdit)
        }
    }
}

/** The callbacks and in-flight flags the read panel's action buttons use. */
private class PanelActions(
    val busy: Boolean,
    val generatingCode: Boolean,
    val onGenerateCode: () -> Unit,
    val onRegisterTse: () -> Unit,
    val onDeregisterTse: () -> Unit,
    val onRevoke: () -> Unit,
    val onDeactivate: () -> Unit,
    val onReactivate: () -> Unit,
    val onBeginEdit: () -> Unit,
)

@Composable
private fun NothingSelected(modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.TouchApp, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            text = str(AdminStringKeys.TerminalSelectHint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun TerminalReadBody(
    terminal: TerminalDto.TerminalSummaryDto,
    inactiveBrowserStation: Boolean,
    canManage: Boolean,
    actions: PanelActions,
    onClose: (() -> Unit)?,
) {
    PanelHeader(terminal, onClose)
    TerminalActiveStatus(terminal)
    if (terminal.isTseMissing()) TseMissingWarning()
    TerminalFacts(terminal)
    if (inactiveBrowserStation) {
        Text(
            text = str(AdminStringKeys.TerminalBrowserStationsOff),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    terminal.pairingCodeExpiresAt?.let { expiry ->
        Text(
            text = str(AdminStringKeys.TerminalPairingOpenUntil, expiry.formatShortDateTime(LocalAppLanguage.current)),
            style = MaterialTheme.typography.bodySmall,
            color = LocalPosColors.current.posReserved,
        )
    }
    if (!canManage) return
    val plan = TerminalPanelPlan.of(terminal)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        plan.primary?.let { PanelButton(it, primary = true, outlinedPrimary = false, actions) }
        plan.secondary.forEachIndexed { index, action ->
            PanelButton(action, primary = false, outlinedPrimary = plan.primary == null && index == 0, actions)
        }
    }
    DecommissionSection(terminal, plan.decommission, actions)
}

/** Header: role tile, `TER-003` and the name under it, the × where the host has one. */
@Composable
private fun PanelHeader(
    terminal: TerminalDto.TerminalSummaryDto,
    onClose: (() -> Unit)?,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TerminalRoleTile(terminal.role, terminal.isActive, size = 48.dp, iconSize = 26.dp)
        Column(Modifier.weight(1f)) {
            Text(terminal.terminalId, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                text = terminalDisplayName(terminal) ?: str(AdminStringKeys.TerminalUnnamed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        onClose?.let { PosIconButton(Icons.Filled.Close, str(CommonKeys.close), it) }
    }
}

/** Amber warning: a fiscal terminal that is not registered at the TSE cannot sign. */
@Composable
private fun TseMissingWarning() {
    val bar = LocalPosColors.current.posOccupied
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(bar))
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
            Text(
                text =
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(str(AdminStringKeys.TerminalTseWarningTitle)) }
                        append(" ")
                        append(str(AdminStringKeys.TerminalTseWarningBody))
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

/** Gerät, Rolle (+ Browser), Standort, TSE, Fiskal, Zuletzt gesehen. */
@Composable
private fun TerminalFacts(terminal: TerminalDto.TerminalSummaryDto) {
    val role = str(roleKey(terminal.role))
    Column {
        FactRow(str(AdminStringKeys.TerminalDeviceLabel), deviceLabel(terminal))
        FactRow(
            str(AdminStringKeys.TerminalColumnRole),
            if (terminal.clientKind == BROWSER_CLIENT_KIND) "$role · ${str(AdminStringKeys.TerminalClientKindBrowser)}" else role,
        )
        FactRow(str(AdminStringKeys.TerminalLocationLabel), terminal.location ?: EMPTY_VALUE)
        FactRow(str(AdminStringKeys.TerminalColumnTse), str(terminalTseValueKey(TerminalTseState.of(terminal))))
        FactRow(
            str(AdminStringKeys.TerminalFiscalLabel),
            str(if (terminal.fiscal) AdminStringKeys.TerminalFiscalYes else AdminStringKeys.TerminalFiscalNo),
        )
        FactRow(str(AdminStringKeys.TerminalColumnLastSeen), terminal.lastSeenAt.formatShortDateTime(LocalAppLanguage.current))
    }
}

@Composable
private fun FactRow(
    label: String,
    value: String,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = adminTouchSize(32.dp)),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** One full-width action: the filled [primary], the primary-outlined lead edit, or a plain outlined button. */
@Composable
private fun PanelButton(
    action: TerminalPanelAction,
    primary: Boolean,
    outlinedPrimary: Boolean,
    actions: PanelActions,
) {
    val height = adminTouchSize(40.dp)
    val text = str(panelActionLabel(action))
    val icon = panelActionIcon(action)
    val onClick = panelActionClick(action, actions)
    val enabled = panelActionEnabled(action, actions)
    val fill = Modifier.fillMaxWidth()
    when {
        primary -> PosButton(text = text, onClick = onClick, enabled = enabled, icon = icon, height = height, modifier = fill)
        outlinedPrimary ->
            PosSecondaryButton(
                text = text,
                onClick = onClick,
                enabled = enabled,
                icon = icon,
                height = height,
                modifier = fill,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                borderColor = MaterialTheme.colorScheme.primary,
            )
        else -> PosSecondaryButton(text = text, onClick = onClick, enabled = enabled, icon = icon, height = height, modifier = fill)
    }
}

/** "Außerbetriebnahme": the red actions, the deregister one disabled with its reason until the terminal is retired. */
@Composable
private fun DecommissionSection(
    terminal: TerminalDto.TerminalSummaryDto,
    decommission: List<TerminalPanelAction>,
    actions: PanelActions,
) {
    // Only a registered client can be deregistered; until the terminal is retired (deactivated,
    // unbound) the action is shown disabled with what is still missing.
    val blockReason = tseDeregisterBlockReason(terminal)
    Column(Modifier.padding(top = 4.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            text = str(AdminStringKeys.TerminalDecommissionHeading).uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.5.sp),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        decommission.forEach { action ->
            val enabled = panelActionEnabled(action, actions) && (action != TerminalPanelAction.DEREGISTER_TSE || blockReason == null)
            DecommissionRow(str(panelActionLabel(action)), panelActionIcon(action), enabled, panelActionClick(action, actions))
            if (action == TerminalPanelAction.DEREGISTER_TSE && blockReason != null) {
                Text(
                    text = str(blockReason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

/** A left-aligned red text action (destructive, never filled: the confirm dialog it opens is the loud step). */
@Composable
private fun DecommissionRow(
    text: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val color: Color = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = adminTouchSize(40.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
    }
}

/** Edit mode of the wide panel: header with the "Geändert" marker, the fields, Abbrechen / Speichern. */
@Composable
private fun TerminalEditBody(
    terminal: TerminalDto.TerminalSummaryDto,
    edit: TerminalEditDraft,
    dirty: Boolean,
    onEditField: ((TerminalEditDraft) -> TerminalEditDraft) -> Unit,
    onSave: () -> Unit,
    onCancelEdit: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TerminalRoleTile(terminal.role, terminal.isActive, size = 48.dp, iconSize = 26.dp)
        Column(Modifier.weight(1f)) {
            Text(
                text = str(AdminStringKeys.TerminalEditTitle, terminal.terminalId),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            if (dirty) TerminalChangedMarker()
        }
    }
    TerminalEditFields(edit, terminal.fiscal, onEditField)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PosSecondaryButton(
            text = str(AdminStringKeys.CommonCancel),
            onClick = onCancelEdit,
            enabled = !edit.saving,
            height = adminTouchSize(40.dp),
            modifier = Modifier.weight(1f),
        )
        PosButton(
            text = str(AdminStringKeys.CommonSave),
            onClick = onSave,
            enabled = !edit.saving,
            isLoading = edit.saving,
            height = adminTouchSize(40.dp),
            modifier = Modifier.weight(1f),
        )
    }
}

/** "Geändert": the form differs from the stored terminal (pure dirty state, never "was touched"). */
@Composable
internal fun TerminalChangedMarker(modifier: Modifier = Modifier) {
    Text(
        text = str(PosPatternKeys.formChanged),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.tertiary,
        modifier = modifier,
    )
}

private fun panelActionLabel(action: TerminalPanelAction) =
    when (action) {
        TerminalPanelAction.REGISTER_TSE -> AdminStringKeys.TerminalTseRegister
        TerminalPanelAction.REACTIVATE -> AdminStringKeys.TerminalReactivate
        TerminalPanelAction.GENERATE_CODE -> AdminStringKeys.TerminalGenerateCode
        TerminalPanelAction.EDIT -> AdminStringKeys.CommonEdit
        TerminalPanelAction.DEACTIVATE -> AdminStringKeys.TerminalDeactivate
        TerminalPanelAction.REVOKE_BINDING -> AdminStringKeys.TerminalRevokeBinding
        TerminalPanelAction.DEREGISTER_TSE -> AdminStringKeys.TerminalTseDeregister
    }

private fun panelActionIcon(action: TerminalPanelAction): ImageVector =
    when (action) {
        TerminalPanelAction.REGISTER_TSE -> Icons.Filled.VerifiedUser
        TerminalPanelAction.REACTIVATE -> Icons.Filled.PlayCircle
        TerminalPanelAction.GENERATE_CODE -> Icons.Filled.QrCode2
        TerminalPanelAction.EDIT -> Icons.Filled.Edit
        TerminalPanelAction.DEACTIVATE -> Icons.Filled.Block
        TerminalPanelAction.REVOKE_BINDING -> Icons.Filled.LinkOff
        TerminalPanelAction.DEREGISTER_TSE -> Icons.Filled.Cancel
    }

private fun panelActionClick(
    action: TerminalPanelAction,
    actions: PanelActions,
): () -> Unit =
    when (action) {
        TerminalPanelAction.REGISTER_TSE -> actions.onRegisterTse
        TerminalPanelAction.REACTIVATE -> actions.onReactivate
        TerminalPanelAction.GENERATE_CODE -> actions.onGenerateCode
        TerminalPanelAction.EDIT -> actions.onBeginEdit
        TerminalPanelAction.DEACTIVATE -> actions.onDeactivate
        TerminalPanelAction.REVOKE_BINDING -> actions.onRevoke
        TerminalPanelAction.DEREGISTER_TSE -> actions.onDeregisterTse
    }

/** Editing is never blocked by an in-flight op; the pairing code also waits for its own request. */
private fun panelActionEnabled(
    action: TerminalPanelAction,
    actions: PanelActions,
): Boolean =
    when (action) {
        TerminalPanelAction.EDIT -> true
        TerminalPanelAction.GENERATE_CODE -> !actions.busy && !actions.generatingCode
        else -> !actions.busy
    }

/**
 * The terminal's name, or null when it does not have one worth showing.
 *
 * Provisioning defaults `name` to the terminal number, so most terminals carry "TER-002" as their
 * name — which printed the number twice in the panel header and filled the table's Name column with
 * a copy of the column beside it. A name equal to the number is a placeholder, not a name, and
 * saying so is more use to an operator than repeating it.
 */
internal fun terminalDisplayName(terminal: TerminalDto.TerminalSummaryDto): String? = terminal.name?.takeIf { it.isNotBlank() && it != terminal.terminalId }

/** Brand and model as one line, falling back to whichever half the device actually reported. */
internal fun deviceLabel(terminal: TerminalDto.TerminalSummaryDto): String =
    listOfNotNull(terminal.terminalBrand?.takeIf { it.isNotBlank() }, terminal.terminalModel?.takeIf { it.isNotBlank() })
        .joinToString(" ")
        .orDash()

internal fun roleKey(role: String) =
    when (role) {
        TerminalRole.CASHIER.name -> AdminStringKeys.TerminalRoleCashier
        TerminalRole.ORDER_STATION.name -> AdminStringKeys.TerminalRoleOrderStation
        TerminalRole.KITCHEN.name -> AdminStringKeys.TerminalRoleKitchen
        TerminalRole.CUSTOMER_DISPLAY.name -> AdminStringKeys.TerminalRoleCustomerDisplay
        else -> AdminStringKeys.TerminalRoleKiosk
    }
