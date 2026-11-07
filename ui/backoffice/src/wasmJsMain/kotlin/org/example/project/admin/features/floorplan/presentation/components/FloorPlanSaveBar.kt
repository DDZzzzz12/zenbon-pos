package org.example.project.admin.features.floorplan.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.form.PosChangedMarker
import org.example.project.ui.theme.LocalPosLayout

/**
 * The editor's fixed footer: "● Geändert · 1 Element verschoben" while [changedCount] > 0, then
 * Abbrechen and Speichern (Speichern only while something changed). On a phone the two buttons share
 * the width under the marker line. [changedCount] counts tables and elements staged since the last save.
 */
@Composable
fun FloorPlanSaveBar(
    changedCount: Int,
    isSaving: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val layout = LocalPosLayout.current
    val summary =
        if (changedCount == 1) {
            str(AdminStringKeys.FloorPlanMovedCountOne)
        } else {
            str(AdminStringKeys.FloorPlanMovedCount, changedCount.toString())
        }
    val marker: @Composable () -> Unit = {
        if (changedCount > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PosChangedMarker()
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Column(modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (layout.isCompact) {
            Column(Modifier.fillMaxWidth().padding(horizontal = layout.screenPadding, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                marker()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PosSecondaryButton(text = str(CommonKeys.cancel), onClick = onCancel, modifier = Modifier.weight(1f))
                    PosButton(
                        text = str(CommonKeys.save),
                        onClick = onSave,
                        enabled = changedCount > 0 && !isSaving,
                        isLoading = isSaving,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = layout.screenPadding, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.weight(1f)) { marker() }
                PosSecondaryButton(text = str(CommonKeys.cancel), onClick = onCancel, height = 40.dp)
                PosButton(
                    text = str(CommonKeys.save),
                    onClick = onSave,
                    enabled = changedCount > 0 && !isSaving,
                    isLoading = isSaving,
                    height = 40.dp,
                )
            }
        }
    }
}
