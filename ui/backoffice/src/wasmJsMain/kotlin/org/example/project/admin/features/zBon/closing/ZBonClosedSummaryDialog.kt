package org.example.project.admin.features.zBon.closing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.formatMoneyForDisplay

/** What a closer who may not open the Z-Bon list sees after closing: the number, revenue and cash balance. */
@Composable
fun ZBonClosedSummaryDialog(
    zBon: ZBonDto.ZBonResponseDto,
    currencyCode: String,
    onDismiss: () -> Unit,
) {
    PosDialog(
        onDismiss = onDismiss,
        title = str(AdminStringKeys.ZBonClosedTitle, zBon.zbonNummer.toString()),
        kind = PosDialogKind.Confirm,
        primary = PosDialogAction(text = str(CommonKeys.ok), onClick = onDismiss),
        showSecondary = false,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = str(AdminStringKeys.ZBonClosedRevenue, formatMoneyForDisplay(zBon.totalRevenue, currencyCode)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = str(AdminStringKeys.ZBonClosedClosingBalance, formatMoneyForDisplay(zBon.closingBalance, currencyCode)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
