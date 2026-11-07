package org.example.project.admin.features.customexport.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.customexport.presentation.DraftField
import org.example.project.core.domain.dto.customexport.CustomExportDto
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardStatus
import org.example.project.ui.components.PosStatusChip
import org.example.project.ui.components.PosTextField
import org.example.project.ui.layout.PaneBreakpoints
import org.example.project.ui.layout.PaneSection
import org.example.project.ui.layout.PosPaneHost
import org.example.project.ui.theme.PosSpacing

/** A chosen-column row narrower than this puts the heading field on its own line above the buttons. */
private val CHOSEN_ROW_STACK_BELOW: Dp = 340.dp

/**
 * Ticking the columns, naming them, and putting them in order.
 *
 * ## Two plain columns, no scroll of its own
 *
 * Both halves are ordinary [Column]s that size to their content. The editor around them supplies the
 * single scroll container; a `LazyColumn` or a nested `verticalScroll` here would be measured with an
 * unbounded height and crash the wasm canvas at runtime — a failure the compiler never sees. The
 * sections below are emitted into that same flat [Column] for the same reason — one heading and its
 * rows, then the next — not as a list of nested scrollables.
 *
 * On a phone the two columns are `PosPaneHost` sections (one visible at a time); they are still plain
 * Columns with no scroll.
 *
 * ## Sections come from the server
 *
 * Which area a column belongs to is [CustomExportDto.FieldDto.group], decided server-side from the
 * foreign-key graph and already German. [groupFields] only arranges them, so a heading needs no
 * string key and a table the graph reaches next appears without a client change.
 *
 * ## Buttons, not drag and drop
 *
 * Order is [chosen]'s list order, and it is changed with up/down buttons. Drag reordering on a
 * Compose canvas is a large piece of work for something that has to be keyboard-reachable anyway, and
 * the ends are *disabled* rather than silently no-op: a button that moves nothing when pressed reads
 * as a broken screen, not as a boundary.
 *
 * ## Summary columns are capped, and the cap is shown
 *
 * A summary column costs a subquery per exported row, so the server caps how many one definition may
 * carry and refuses both the save and the run past it. [lockedAggregateKeys] decides which checkboxes
 * that makes untickable; a hint above each aggregate section says how many are used of how many, so
 * a greyed-out checkbox reads as a limit rather than as a broken screen.
 *
 * ## Filtering is local, and never hides a ticked box
 *
 * The query lives in a [remember] here and is deliberately not in the ViewModel or the draft: it
 * is how the operator is looking at the list right now, not part of the definition they save.
 * [filterFields] holds the rules — most importantly that a field in [chosen] stays on screen
 * however the query reads, because the checkbox is the control an operator unticks with.
 *
 * ## A column the catalogue lost
 *
 * A key in [unresolvedKeys] has no entry in [available] — it cannot be ticked, renamed usefully, or
 * run. It is still rendered, marked, and removable, because removing it is the only repair and an
 * operator cannot remove a row they were never shown.
 */
@Composable
fun FieldPicker(
    available: List<CustomExportDto.FieldDto>,
    chosen: List<DraftField>,
    unresolvedKeys: Set<String>,
    enabled: Boolean,
    maxAggregateColumns: Int,
    onToggleField: (String) -> Unit,
    onHeadingChange: (String, String) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chosenKeys = chosen.map { it.fieldKey }.toSet()
    // On the whole catalogue, not on what the filter left visible: the cap counts the definition's
    // summary columns, and a query must not appear to hand back room the server will still refuse.
    val lockedKeys = lockedAggregateKeys(available, chosenKeys, maxAggregateColumns)
    val usedAggregates = chosenAggregateCount(available, chosenKeys)
    var query by remember { mutableStateOf("") }
    val sections = filterFields(groupFields(available), query, chosenKeys)

    val availablePane: @Composable (Modifier) -> Unit = { paneModifier ->
        Column(paneModifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(str(AdminStringKeys.CustomExportFields), style = MaterialTheme.typography.titleSmall)
            PosTextField(
                value = query,
                onValueChange = { query = it },
                label = str(AdminStringKeys.CustomExportFieldFilter),
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            // A pane that simply goes blank reads as a screen that broke, not as a query that found
            // nothing — so say which of the two it is.
            if (sections.isEmpty() && available.isNotEmpty()) {
                Text(
                    text = str(AdminStringKeys.CustomExportFieldFilterEmpty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            sections.forEach { section ->
                if (section.heading != null) {
                    Text(
                        text = section.heading,
                        // labelMedium, not titleSmall: a group heading sits *under* the pane header
                        // above it, and two headings at the same weight read as two panes.
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                // Above the section's rows, not once at the top of the pane: the aggregates sit
                // under their own relation's heading, and a hint far from them explains nothing.
                if (section.fields.any { it.isAggregate }) {
                    Text(
                        text = str(AdminStringKeys.CustomExportAggregateLimit, usedAggregates, maxAggregateColumns),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                section.fields.forEach { field ->
                    AvailableFieldRow(
                        field = field,
                        checked = field.key in chosenKeys,
                        // Only an *unchosen* aggregate at the cap locks — see [lockedAggregateKeys].
                        // Ordinary columns and already-ticked aggregates stay live, or the operator
                        // would reach the limit with no control left that gets them back under it.
                        enabled = enabled && field.key !in lockedKeys,
                        onToggle = { onToggleField(field.key) },
                    )
                }
            }
        }
    }

    val chosenPane: @Composable (Modifier) -> Unit = { paneModifier ->
        Column(paneModifier, verticalArrangement = Arrangement.spacedBy(PosSpacing.sm)) {
            Text(str(AdminStringKeys.CustomExportSelectedFields), style = MaterialTheme.typography.titleSmall)
            // The broken-field explainer is said once, by the editor around this picker; a row only
            // carries its own marker.
            chosen.forEachIndexed { index, field ->
                ChosenColumnRow(
                    field = field,
                    index = index,
                    lastIndex = chosen.lastIndex,
                    // The catalogue's own label, so the row says which data it carries and not only
                    // what the operator renamed the heading to.
                    fieldLabel = available.firstOrNull { it.key == field.fieldKey }?.label,
                    dead = field.fieldKey in unresolvedKeys,
                    enabled = enabled,
                    onHeadingChange = { text -> onHeadingChange(field.fieldKey, text) },
                    onMoveUp = { onMoveUp(index) },
                    onMoveDown = { onMoveDown(index) },
                    onRemove = { onToggleField(field.fieldKey) },
                )
            }
        }
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth < PaneBreakpoints.PHONE_MAX) {
            // Phone: one half at a time. Both stay composed (PosPaneHost keeps hidden sections), so
            // the filter query and the chosen list survive a switch, and the host adds no scroll —
            // the editor still has exactly one scroll container.
            PosPaneHost(
                sections =
                    listOf(
                        PaneSection("available", str(AdminStringKeys.CustomExportFields)) { availablePane(Modifier) },
                        PaneSection("chosen", str(AdminStringKeys.CustomExportSelectedFields)) { chosenPane(Modifier) },
                    ),
                maxColumns = 1,
            )
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                availablePane(Modifier.weight(1f))
                chosenPane(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AvailableFieldRow(
    field: CustomExportDto.FieldDto,
    checked: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
        Text(
            text = field.label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun ChosenColumnRow(
    field: DraftField,
    index: Int,
    lastIndex: Int,
    fieldLabel: String?,
    dead: Boolean,
    enabled: Boolean,
    onHeadingChange: (String) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    PosCard(status = if (dead) PosCardStatus.Error else null) {
        body {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // An unresolvable column is never labelled with its raw path: the path is small
                // secondary text under a plain "unknown field" label.
                Column(Modifier.weight(1f)) {
                    Text(
                        text = fieldLabel ?: str(AdminStringKeys.CustomExportUnknownField),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (dead) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (fieldLabel == null) {
                        Text(
                            text = field.fieldKey,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (dead) {
                    // Icon + chip + card status: the fault is not carried by colour alone.
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(end = 8.dp).size(18.dp),
                    )
                    PosStatusChip(
                        text = str(AdminStringKeys.CustomExportBroken),
                        backgroundColor = MaterialTheme.colorScheme.errorContainer,
                        textColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val heading: @Composable (Modifier) -> Unit = { m ->
                    PosTextField(
                        value = field.heading,
                        onValueChange = onHeadingChange,
                        label = str(AdminStringKeys.CustomExportHeading),
                        // A dead column is going to be removed, not shipped; naming it changes nothing
                        // the server would accept.
                        enabled = enabled && !dead,
                        modifier = m,
                    )
                }
                if (maxWidth < CHOSEN_ROW_STACK_BELOW) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        heading(Modifier.fillMaxWidth())
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            ChosenRowControls(index, lastIndex, enabled, onMoveUp, onMoveDown, onRemove)
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        heading(Modifier.weight(1f))
                        ChosenRowControls(index, lastIndex, enabled, onMoveUp, onMoveDown, onRemove)
                    }
                }
            }
        }
    }
}

/** Up, down and remove for one chosen column. */
@Composable
private fun ChosenRowControls(
    index: Int,
    lastIndex: Int,
    enabled: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    IconButton(onClick = onMoveUp, enabled = enabled && index > 0) {
        Icon(
            imageVector = Icons.Default.KeyboardArrowUp,
            contentDescription = str(AdminStringKeys.CustomExportMoveUp),
            modifier = Modifier.size(20.dp),
        )
    }
    IconButton(onClick = onMoveDown, enabled = enabled && index < lastIndex) {
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = str(AdminStringKeys.CustomExportMoveDown),
            modifier = Modifier.size(20.dp),
        )
    }
    IconButton(onClick = onRemove, enabled = enabled) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = str(AdminStringKeys.CustomExportRemove),
            modifier = Modifier.size(20.dp),
        )
    }
}
