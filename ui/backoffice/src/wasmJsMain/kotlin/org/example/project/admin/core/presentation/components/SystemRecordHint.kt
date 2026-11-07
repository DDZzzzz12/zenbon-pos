package org.example.project.admin.core.presentation.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.example.project.core.presentation.strings.keys.BackOfficeErrorKeys
import org.example.project.core.presentation.strings.str

/**
 * Stands in for the edit/delete controls of a record the system depends on (`SystemRecords`): the
 * server refuses to change or delete it, so the screen says so instead of offering the buttons.
 */
@Composable
fun SystemRecordHint(modifier: Modifier = Modifier) {
    Text(
        text = str(BackOfficeErrorKeys.systemRecordHint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
