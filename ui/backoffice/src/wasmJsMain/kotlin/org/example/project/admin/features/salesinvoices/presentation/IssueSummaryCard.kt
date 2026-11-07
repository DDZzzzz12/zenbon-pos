package org.example.project.admin.features.salesinvoices.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import org.example.project.ui.components.PosMoneyText
import org.example.project.ui.theme.PosSpacing

/**
 * The line a "Rechnung ausstellen" confirm shows under its warning: who the invoice is for and what it
 * adds up to, so the irreversible step is taken on a figure the operator has just read. The amount is
 * [gross] as the server sent it (or the editor's feedback total), through [PosMoneyText].
 */
@Composable
fun IssueSummaryCard(
    buyerName: String,
    gross: String?,
    currency: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(PosSpacing.lg),
        horizontalArrangement = Arrangement.spacedBy(PosSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = buyerName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (gross != null) {
            PosMoneyText(
                amount = gross,
                currencyCode = currency,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}
