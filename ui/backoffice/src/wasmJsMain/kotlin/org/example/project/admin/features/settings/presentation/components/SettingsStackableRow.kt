package org.example.project.admin.features.settings.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.ui.layout.PaneBreakpoints

/** Whether a title-plus-actions row has too little CONTENT width (not window width) to stay one row. */
internal fun shouldStackRow(contentWidth: Dp): Boolean = contentWidth < PaneBreakpoints.PHONE_MAX

/**
 * A weighted [leading] block (title, field) beside its [actions] (buttons).
 *
 * Wide: one Row, [leading] takes the remaining width. Narrow (see [shouldStackRow]): [leading]
 * gets the full width and the actions wrap underneath, so neither squeezes the other and every
 * button stays reachable. [leading] receives the modifier it must apply to its root (`weight(1f)`
 * wide, `fillMaxWidth()` stacked). [hasActions] false (a read-only principal) renders [leading]
 * alone and never invokes [actions].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsStackableRow(
    modifier: Modifier = Modifier,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    hasActions: Boolean = true,
    leading: @Composable (Modifier) -> Unit,
    actions: @Composable () -> Unit,
) {
    if (!hasActions) {
        leading(modifier.fillMaxWidth())
        return
    }
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        if (shouldStackRow(maxWidth)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                leading(Modifier.fillMaxWidth())
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) { actions() }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = verticalAlignment,
            ) {
                leading(Modifier.weight(1f))
                actions()
            }
        }
    }
}
