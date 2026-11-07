package org.example.project.admin.features.dashboard.presentation.checklist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.theme.LocalPosLayout
import org.koin.compose.viewmodel.koinViewModel

/**
 * Tells the operator what still stops this install from taking an order.
 *
 * **Renders nothing once no row is outstanding** — no empty card, no congratulation. It is an onboarding
 * aid, not a health monitor: it does not come back if the TSE later goes offline, because the TSE screen
 * owns live device health and does it far better. While anything is outstanding, the card shows only the
 * outstanding rows and offers an "Alle anzeigen" toggle to reveal the passed ones.
 *
 * Replaces `FirstTerminalBanner`, which nagged about exactly one of the six things that can block
 * trading. Keeping both would have nagged about terminals twice, in two visual languages, on one screen.
 *
 * The caller gates this on MANAGER — a cashier cannot act on any row, so showing them the card is noise.
 *
 * @param canAdministerTse hoisted from the caller (which already holds the `TokenStore`, as it does for
 *   the sidebar and the `canManage` gate above this card) rather than injected here. A manager sees the
 *   TSE row but is not offered its click, because commissioning is ADMIN-only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupChecklistCard(
    canAdministerTse: Boolean,
    onNavigate: (SetupChecklistItem) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SetupChecklistViewModel = koinViewModel(),
) {
    LaunchedEffect(canAdministerTse) { viewModel.refresh(canAdministerTse) }

    val items = viewModel.state.items
    val outstanding = items.filter { it.state != ChecklistState.PASSED }
    val doneCount = items.size - outstanding.size

    // The builder now returns every row, so the render-nothing decision lives here: an install with
    // nothing outstanding needs no card, and a permanent "well done" banner is what this card was
    // written to avoid.
    if (items.isEmpty() || outstanding.isEmpty()) return

    var showAll by remember { mutableStateOf(false) }
    val visible = if (showAll) items else outstanding
    val compact = LocalPosLayout.current.isCompact
    var sheetOpen by remember { mutableStateOf(false) }
    val checklistBody =
        @Composable {
            ChecklistBody(
                visible = visible,
                doneCount = doneCount,
                total = items.size,
                showAll = showAll,
                onToggleShowAll = { showAll = !showAll },
                onNavigate = { item ->
                    sheetOpen = false
                    onNavigate(item)
                },
            )
        }

    if (compact) {
        // the full list opens in a bottom sheet.
        ChecklistSummaryRow(doneCount, items.size, outstanding.size, onClick = { sheetOpen = true }, modifier = modifier)
        if (sheetOpen) {
            ModalBottomSheet(
                onDismissRequest = { sheetOpen = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                shape = RectangleShape,
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) { checklistBody() }
            }
        }
    } else {
        PosCard(modifier = modifier.fillMaxWidth(), variant = PosCardVariant.Plain) { body { checklistBody() } }
    }
}

/** Title with progress, the rows, and the "show all / only open" toggle — shared by the card and the phone sheet. */
@Composable
private fun ChecklistBody(
    visible: List<SetupChecklistItem>,
    doneCount: Int,
    total: Int,
    showAll: Boolean,
    onToggleShowAll: () -> Unit,
    onNavigate: (SetupChecklistItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(str(AdminStringKeys.ChecklistCardTitle), style = MaterialTheme.typography.titleMedium)
            Text(
                str(AdminStringKeys.ChecklistProgress, doneCount.toString(), total.toString()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ChecklistProgressBar(doneCount, total)
        ChecklistRows(visible, onNavigate)
        if (doneCount > 0) {
            PosTextButton(
                text =
                    if (showAll) {
                        str(AdminStringKeys.ChecklistShowOpenOnly)
                    } else {
                        str(AdminStringKeys.ChecklistShowAll, doneCount.toString())
                    },
                onClick = onToggleShowAll,
            )
        }
    }
}

/** Phone form: icon, title, "4 von 6 erledigt · 2 offen", the progress bar and a chevron, as one tap target that opens the sheet. */
@Composable
private fun ChecklistSummaryRow(
    doneCount: Int,
    total: Int,
    openCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PosCard(modifier = modifier.fillMaxWidth(), variant = PosCardVariant.Plain, onClick = onClick) {
        body {
            Row(
                Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Checklist,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(str(AdminStringKeys.ChecklistCardTitle), style = MaterialTheme.typography.titleMedium)
                    Text(
                        str(AdminStringKeys.ChecklistProgressCompact, doneCount.toString(), total.toString(), openCount.toString()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ChecklistProgressBar(doneCount, total)
                }
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 4 dp bar, `primary` on `surface-container-highest`; square, as the system has no rounded corners. */
@Composable
private fun ChecklistProgressBar(
    done: Int,
    total: Int,
) {
    val fraction = if (total == 0) 0f else done.toFloat() / total
    Box(Modifier.fillMaxWidth().height(4.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
    }
}

/** One column, or two once the card is wide enough (a Medium window's full-width card). */
@Composable
private fun ChecklistRows(
    items: List<SetupChecklistItem>,
    onNavigate: (SetupChecklistItem) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= TwoColumnMinWidth) 2 else 1
        Column(Modifier.fillMaxWidth()) {
            items.chunked(columns).forEachIndexed { index, rowItems ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    rowItems.forEach { item ->
                        ChecklistRow(item = item, onClick = { onNavigate(item) }, modifier = Modifier.weight(1f))
                    }
                    repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private val TwoColumnMinWidth = 520.dp

@Composable
private fun ChecklistRow(
    item: SetupChecklistItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val passed = item.state == ChecklistState.PASSED
    val blocked = item.state == ChecklistState.BLOCKED

    // A blocked row is dimmed: its prerequisite is not met, so it is not the operator's next action.
    val contentColor =
        when {
            passed -> MaterialTheme.colorScheme.onSurfaceVariant
            blocked -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            else -> MaterialTheme.colorScheme.onSurface
        }
    // A passed row has nothing to act on; a blocked one routes to its prerequisite, not itself.
    // A row with no destination has nowhere this operator's role may go, so it renders without
    // a click affordance rather than bouncing off the route gate.
    val clickable = !passed && item.destination != null

    Row(
        modifier =
            modifier
                .defaultMinSize(minHeight = 48.dp)
                .then(if (clickable) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector =
                when {
                    passed -> Icons.Default.CheckCircle
                    blocked -> Icons.Default.Lock
                    else -> Icons.Default.RadioButtonUnchecked
                },
            contentDescription = null,
            tint = if (passed) MaterialTheme.colorScheme.primary else contentColor,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = str(item.labelKey),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = contentColor,
            )
            // The recommendation is the point of the row — it names the concrete next action.
            // A passed row does not need telling.
            if (!passed) {
                Text(
                    text = str(item.recommendationKey),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (clickable) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
