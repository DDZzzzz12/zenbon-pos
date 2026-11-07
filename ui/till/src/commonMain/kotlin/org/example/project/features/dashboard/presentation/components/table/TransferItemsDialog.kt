package org.example.project.features.dashboard.presentation.components.table

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.data.models.RTable
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.TableDetailKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalLanguageManager
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.screens.dashboard.components.TableSearchSelector

@Composable
fun TransferItemsDialog(
    itemCount: Int,
    currentTableId: Long,
    availableTables: List<RTable>,
    onConfirm: (targetTableId: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val languageManager = LocalLanguageManager.current
    val language by languageManager.currentLanguage

    var selectedTableId by remember { mutableStateOf<Long?>(null) }

    PosDialog(
        onDismiss = onDismiss,
        title = str(TableDetailKeys.transferItems),
        maxWidth = 500.dp,
        footer = {
            PosTextButton(text = str(CommonKeys.cancel), onClick = onDismiss)
            PosButton(
                text = str(CommonKeys.confirm),
                onClick = { selectedTableId?.let(onConfirm) },
                enabled = selectedTableId != null,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary,
                    ),
            )
        },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 600.dp),
        ) {
            // Item count info
            Text(
                text = "${str(TableDetailKeys.transferTo)} $itemCount ${str(TableDetailKeys.items).lowercase()}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Table search and selection
            TableSearchSelector(
                tables = availableTables,
                excludeTableId = currentTableId,
                selectedTableId = selectedTableId,
                onTableClick = { table -> selectedTableId = table.id },
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}
