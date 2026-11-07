package org.example.project.ui.screens.dashboard

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.data.models.RTable
import org.example.project.core.domain.reason.ActionReasons
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.TableDetailKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalLanguageManager
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.ReasonChips
import org.example.project.ui.components.ReasonSelectionState
import org.example.project.ui.screens.dashboard.components.TableSearchSelector

@Composable
fun PosTransferItemsDialog(
    loadTables: (String) -> Unit,
    itemCount: Int,
    currentTableId: Long,
    availableTables: List<RTable>,
    onConfirm: (targetTableId: Long, reason: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val languageManager = LocalLanguageManager.current
    val language by languageManager.currentLanguage

    var selectedTableId by remember { mutableStateOf<Long?>(null) }
    val reasonState = remember { ReasonSelectionState(ActionReasons.transferItems) }

    PosDialog(
        onDismiss = onDismiss,
        title = str(TableDetailKeys.transferItems),
        titleIcon = Icons.Default.SwapHoriz,
        maxWidth = 500.dp,
        footer = {
            PosSecondaryButton(
                text = str(CommonKeys.cancel),
                onClick = onDismiss,
            )
            PosButton(
                text = str(CommonKeys.confirm),
                onClick = {
                    selectedTableId?.let { tableId ->
                        reasonState.submitOrNull()?.let { reason -> onConfirm(tableId, reason) }
                    }
                },
                enabled = selectedTableId != null,
            )
        },
    ) {
        Text(
            text = str(TableDetailKeys.selectedItems, itemCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        TableSearchSelector(
            loadTables = loadTables,
            tables = availableTables,
            excludeTableId = currentTableId,
            selectedTableId = selectedTableId,
            onTableClick = { table -> selectedTableId = table.id },
            modifier = Modifier.heightIn(max = 400.dp),
        )

        ReasonChips(state = reasonState)
    }
}
