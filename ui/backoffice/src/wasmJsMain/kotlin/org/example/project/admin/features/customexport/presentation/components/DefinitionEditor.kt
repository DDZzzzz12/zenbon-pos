package org.example.project.admin.features.customexport.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.customexport.presentation.DefinitionDraft
import org.example.project.admin.features.customexport.presentation.RunBlockReason
import org.example.project.admin.features.customexport.presentation.runBlockReason
import org.example.project.core.domain.dto.customexport.CustomExportDto
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosDateField
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosPickerField
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosTextField

/**
 * The right-hand pane: one export definition, from its name to the button that downloads it.
 *
 * Everything here sizes to content — the pane's single scroll container lives in the screen. Nothing
 * in this file may introduce a second one.
 *
 * ## The subject is fixed once the definition is saved
 *
 * Every chosen column and every filter belongs to the subject they were picked from, so switching it
 * empties both. That is the right behaviour while composing a new export and an unacceptable one on a
 * saved definition, where a stray click would silently discard the work. A saved definition therefore
 * shows the subject as plain label/value text, with a sentence saying why: an operator who wants a
 * different subject makes a new export.
 *
 * ## A reader edits nothing
 *
 * [canManage] is `CUSTOM_EXPORT_MANAGE`, which the server enforces on create and delete. Without it
 * every input here is disabled and the save and delete buttons are not rendered — running stays
 * available, because that is the read tier's whole point. Leaving the inputs live for a reader would
 * invite them to change a definition they cannot save, and a run uses the *stored* definition
 * regardless of what is on screen.
 */
@Composable
fun DefinitionEditor(
    draft: DefinitionDraft,
    subject: CustomExportDto.SubjectDto?,
    subjects: List<CustomExportDto.SubjectDto>,
    isBroken: Boolean,
    isDirty: Boolean,
    canManage: Boolean,
    isSaving: Boolean,
    isRunning: Boolean,
    onNameChange: (String) -> Unit,
    onSubjectChange: (String) -> Unit,
    onToggleField: (String) -> Unit,
    onHeadingChange: (String, String) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
    onDateRangeChange: (String?, String?) -> Unit,
    onToggleFilter: (String) -> Unit,
    onSave: () -> Unit,
    onRun: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val saved = draft.id != null
    val busy = isSaving || isRunning

    // One flag for every input in the form: a reader may look and run, not type.
    val editable = canManage && !busy
    val hasFields = draft.fields.isNotEmpty()
    var subjectMenuOpen by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PosTextField(
            value = draft.name,
            onValueChange = onNameChange,
            label = str(AdminStringKeys.CustomExportName),
            enabled = editable,
            modifier = Modifier.fillMaxWidth(),
        )

        if (saved) {
            // Fixed for a saved definition: label and value, not a dimmed dropdown that looks broken.
            // Say why it cannot change, and what to do instead, right under it.
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = str(AdminStringKeys.CustomExportSubject),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(text = subject?.label ?: draft.subjectKey, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = str(AdminStringKeys.CustomExportSubjectFixed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Box {
                PosPickerField(
                    value = subject?.label ?: draft.subjectKey,
                    onClick = { subjectMenuOpen = true },
                    label = str(AdminStringKeys.CustomExportSubject),
                    enabled = editable,
                )
                PosDropdown(expanded = subjectMenuOpen, onDismissRequest = { subjectMenuOpen = false }) {
                    subjects.forEach { candidate ->
                        PosDropdownItem(
                            text = candidate.label,
                            selected = candidate.key == draft.subjectKey,
                            onClick = {
                                onSubjectChange(candidate.key)
                                subjectMenuOpen = false
                            },
                        )
                    }
                }
            }
        }

        if (isBroken) {
            Text(
                text = str(AdminStringKeys.CustomExportBrokenExplainer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        FieldPicker(
            available = subject?.fields.orEmpty(),
            chosen = draft.fields,
            unresolvedKeys = draft.unresolvedKeys,
            enabled = editable,
            // The server's cap, not a constant of ours. A subject that has not loaded yet offers no
            // fields either, so the fallback gates nothing that could be ticked.
            maxAggregateColumns = subject?.maxAggregateColumns ?: 0,
            onToggleField = onToggleField,
            onHeadingChange = onHeadingChange,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
        )

        if (subject?.hasDateFilter == true) {
            DateRangeRow(
                dateFrom = draft.dateFrom,
                dateTo = draft.dateTo,
                enabled = editable,
                onDateRangeChange = onDateRangeChange,
            )
        }

        val toggles = subject?.toggles.orEmpty()
        if (toggles.isNotEmpty()) {
            Text(str(AdminStringKeys.CustomExportFilters), style = MaterialTheme.typography.titleSmall)
            toggles.forEach { toggle ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = toggle.key in draft.enabledToggles,
                        onCheckedChange = { onToggleFilter(toggle.key) },
                        enabled = editable,
                    )
                    Text(toggle.label, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (!hasFields && canManage) {
            // Said next to the disabled save button rather than after a rejected round trip: the
            // view model already refuses to send a column-less definition.
            Text(
                text = str(AdminStringKeys.CustomExportNoFieldsSelected),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val runBlock = runBlockReason(saved = saved, dirty = isDirty, broken = isBroken)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            // Save is hidden rather than disabled for a reader: they never acquire the manage tier
            // by waiting, so a permanently dead button would only be furniture.
            if (canManage) {
                PosButton(
                    text = str(CommonKeys.save),
                    onClick = onSave,
                    enabled = hasFields && draft.subjectKey.isNotBlank() && !busy,
                    isLoading = isSaving,
                )
            }
            PosSecondaryButton(
                text = str(AdminStringKeys.CustomExportRun),
                onClick = onRun,
                // Deliberately NOT gated on canManage — running a definition somebody else built is
                // what CUSTOM_EXPORT_READ buys, and the server re-checks every field against the
                // runner's own capabilities. A run uses the STORED definition, so an unsaved or
                // edited form cannot run, and the server refuses a broken one anyway: the reason
                // is said next to the button instead of leaving it dead.
                enabled = runBlock == null && !busy,
                isLoading = isRunning,
            )
            if (runBlock != null) {
                Text(
                    text = str(runBlock.messageKey()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PosSecondaryButton(
                text = str(CommonKeys.cancel),
                onClick = onClose,
                enabled = !busy,
            )
        }

        if (saved && canManage) {
            // Separated from Save: the destructive action is outlined, last, and never the loudest
            // control on the page.
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PosSecondaryButton(
                    text = str(CommonKeys.delete),
                    onClick = onDelete,
                    enabled = !busy,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                )
            }
        }
    }
}

private fun RunBlockReason.messageKey() =
    when (this) {
        RunBlockReason.SAVE_FIRST -> AdminStringKeys.CustomExportRunSaveFirst
        RunBlockReason.FIX_FIELD -> AdminStringKeys.CustomExportRunFixField
    }

/**
 * The two bounds, as dates.
 *
 * Both fields hand the view model a bare `yyyy-MM-dd`; it owns the time halves that decide whether
 * the final day is in the export. Nothing here appends one — a screen that minted its own would be
 * the second place that rule could be got wrong.
 */
@Composable
private fun DateRangeRow(
    dateFrom: String?,
    dateTo: String?,
    enabled: Boolean,
    onDateRangeChange: (String?, String?) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PosDateField(
            value = dateFrom.datePart(),
            onValueChange = { date -> onDateRangeChange(date, dateTo.datePart()) },
            label = str(AdminStringKeys.CustomExportDateFrom),
            // Blank is "no bound" and is what the run applies; a sample date here read as a
            // date that would be applied (QA admin16 NOTE-4).
            placeholder = str(AdminStringKeys.CustomExportDateUnbounded),
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        PosDateField(
            value = dateTo.datePart(),
            onValueChange = { date -> onDateRangeChange(dateFrom.datePart(), date) },
            label = str(AdminStringKeys.CustomExportDateTo),
            // Blank is "no bound" and is what the run applies; a sample date here read as a
            // date that would be applied (QA admin16 NOTE-4).
            placeholder = str(AdminStringKeys.CustomExportDateUnbounded),
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The date half of a stored `yyyy-MM-ddTHH:mm:ss` bound, or blank when no bound is set.
 *
 * Used on the way *out* as well as in: the untouched bound is passed back as a date so the view model
 * re-mints its own time half rather than being handed one it did not choose.
 */
private fun String?.datePart(): String = this?.substringBefore('T').orEmpty()
