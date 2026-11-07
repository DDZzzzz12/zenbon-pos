package org.example.project.features.reports.presentation.components.dailyreport

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.payment.PaymentDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.StatisticsDashboardKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.getLocalizedName
import org.example.project.core.utils.sortedForDisplay
import org.example.project.core.utils.toFormattedCurrency

private val dotColors =
    listOf(
        Color(0xFF7C6FF7), // Purple
        Color(0xFF4ADE80), // Green
        Color(0xFF60A5FA), // Blue
        Color(0xFFFBBF24), // Yellow
        Color(0xFFF87171), // Red
    )

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PaymentDotsRow(
    payments: List<PaymentDto.PaymentSummary>,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (payments.isEmpty()) return
    // A dot's colour comes from its position, so the order must not follow the server's (unstable)
    // grouping order: sort by the name shown in the UI language (UI review follow-up 5).
    val language = LocalAppLanguage.current
    val ordered = remember(payments, language) { payments.sortedForDisplay(language) }

    FlowRow(
        modifier =
            modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.small)
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "${str(StatisticsDashboardKeys.payments)}:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ordered.forEachIndexed { index, payment ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(dotColors[index % dotColors.size]),
                )
                Text(
                    text = "${payment.getLocalizedName(language)} ${payment.amount.toFormattedCurrency()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
