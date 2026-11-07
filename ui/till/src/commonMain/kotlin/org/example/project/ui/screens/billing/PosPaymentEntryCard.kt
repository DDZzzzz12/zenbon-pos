package org.example.project.ui.screens.billing

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.project.core.presentation.strings.keys.BillingKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.toFormattedCurrency
import org.example.project.money.Decimal
import org.example.project.ui.components.PosAutoSizeText
import org.example.project.ui.components.PosMarqueeText
import org.example.project.ui.theme.PosSpacing
import org.example.project.ui.theme.PosTheme

/**
 * One-line payment entry (type, amount, remove), about 40 dp tall so two of them fit in the
 * payment panel's list share on a 1280×800 tablet.
 */
@Composable
fun PosPaymentEntryCard(
    paymentTypeName: String,
    amount: Decimal,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Payment icon
            Icon(
                imageVector = Icons.Default.Payments,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )

            Spacer(modifier = Modifier.width(PosSpacing.md))

            PosMarqueeText(
                text = paymentTypeName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )

            // Amount
            PosAutoSizeText(
                text = amount.toFormattedCurrency(),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                minFontSize = 10.sp,
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Check icon
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = PosTheme.colors.posAvailable,
                modifier = Modifier.size(20.dp),
            )

            // Remove button
            // raw Material 3 by design: non-48dp size(32.dp) modifier not supported by PosIconButton
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = str(BillingKeys.removePaymentButton),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
