package org.example.project.core.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.project.core.data.models.PaymentType
import org.example.project.core.domain.enums.setup.Language
import org.example.project.ui.components.PosAutoSizeText
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant

/**
 * Payment Type Button component.
 */
@Composable
fun PaymentTypeButton(
    paymentType: PaymentType,
    isSelected: Boolean,
    onClick: () -> Unit,
    language: Language,
) {
    PosCard(
        onClick = onClick,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp),
        variant = PosCardVariant.Plain,
        selected = isSelected,
    ) {
        body {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                PosAutoSizeText(
                    text = paymentType.getName(language),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    minFontSize = 10.sp,
                )
            }
        }
    }
}
