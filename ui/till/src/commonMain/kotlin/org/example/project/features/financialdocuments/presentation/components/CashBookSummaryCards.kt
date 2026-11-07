package org.example.project.features.financialdocuments.presentation.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.keys.FinancialDocumentsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.financialdocuments.presentation.state.KassenbuchState
import org.example.project.ui.components.PosAutoSizeText
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant

/** Fixed card width used when the cards scroll horizontally on compact (phone) screens. */
private val CompactCardWidth = 170.dp

@Composable
fun CashBookSummaryCards(
    kassenbuchState: KassenbuchState,
    language: Language,
    modifier: Modifier = Modifier,
    isCompact: Boolean,
) {
    // PosCard forces fillMaxWidth internally, so the cards need a bounded width to measure
    // correctly. Compact: fixed-width cards inside a horizontalScroll. Non-compact: equal
    // weight in a plain Row (no scroll). Putting fillMaxWidth/weight cards inside a
    // horizontalScroll yields an unbounded width and collapses the layout.
    if (isCompact) {
        Row(
            modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SummaryCardItems(kassenbuchState = kassenbuchState, isCompact = true)
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SummaryCardItems(kassenbuchState = kassenbuchState, isCompact = false)
        }
    }
}

@Composable
private fun RowScope.SummaryCardItems(
    kassenbuchState: KassenbuchState,
    isCompact: Boolean,
) {
    val cardModifier = if (isCompact) Modifier.width(CompactCardWidth) else Modifier.weight(1f)

    // Cash on Hand
    PosCard(
        modifier = cardModifier,
        variant = PosCardVariant.Elevated,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
    ) {
        body {
            Icon(
                imageVector = Icons.Default.AccountBalanceWallet,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = str(FinancialDocumentsKeys.cashOnHand),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
            PosAutoSizeText(
                text = "${kassenbuchState.currentBalance} €",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }

    // Total Income
    PosCard(
        modifier = cardModifier,
        variant = PosCardVariant.Elevated,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        body {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = str(FinancialDocumentsKeys.totalIncome),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PosAutoSizeText(
                text = "${kassenbuchState.totalIncome} €",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    // Total Expenses
    PosCard(
        modifier = cardModifier,
        variant = PosCardVariant.Elevated,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        body {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.TrendingDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = str(FinancialDocumentsKeys.totalExpenses),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PosAutoSizeText(
                text = "${kassenbuchState.totalExpenses} €",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
