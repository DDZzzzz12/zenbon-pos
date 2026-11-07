package org.example.project.ui.screens.dashboard.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.example.project.core.data.models.RTable
import org.example.project.core.domain.reason.ActionReasons
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.TableDeleteKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDangerButton
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.ReasonChips
import org.example.project.ui.components.ReasonSelectionState
import org.example.project.ui.theme.PosTheme

@Composable
fun PosDeleteTableDialog(
    table: RTable,
    onConfirm: (reason: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val reasonState = remember { ReasonSelectionState(ActionReasons.cancelTable) }
    PosDialog(
        onDismiss = onDismiss,
        title = str(TableDeleteKeys.deleteTableTitle, table.tableNumber),
        showCloseButton = false,
        footer = {
            Spacer(modifier = Modifier.weight(1f))
            PosSecondaryButton(
                text = str(CommonKeys.cancel),
                onClick = onDismiss,
            )
            PosDangerButton(
                text = str(TableDeleteKeys.confirm),
                icon = Icons.Default.Delete,
                onClick = {
                    reasonState.submitOrNull()?.let { reason ->
                        onConfirm(reason)
                        onDismiss()
                    }
                },
            )
        },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Warning icon circle
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer),
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = PosTheme.colors.posError,
                    modifier = Modifier.size(24.dp),
                )
            }

            // Warning text
            Text(
                text = str(TableDeleteKeys.deleteWarningBody),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            ReasonChips(state = reasonState)
        }
    }
}
