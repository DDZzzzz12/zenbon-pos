package org.example.project.features.dashboard.presentation.components.billing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.keys.BillReceiptKeys
import org.example.project.core.presentation.strings.str

/**
 * Storno dialog switch: re-open the bill's table session so its items can be billed again (on), or
 * a plain Storno that leaves the table alone (off). The server ignores it for a counter voucher sale.
 */
@Composable
fun StornoRestoreSessionToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = str(BillReceiptKeys.restoreSessionLabel),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = str(BillReceiptKeys.restoreSessionHint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // raw Material 3 by design: no Pos* switch component exists yet (same as PosTransferTableDialog)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
