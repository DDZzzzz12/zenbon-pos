/**
 * Step indicator for phone billing flow.
 *
 * Shows current step label with navigation button to go back to previous step.
 *
 * @since 1.0.0
 * @author Generated with Claude Code
 */
package org.example.project.features.dashboard.presentation.components.billing

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
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.keys.BillingKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.dashboard.presentation.state.BillingStep
import org.example.project.ui.components.PosTextButton

/**
 * Shows billing step progress with navigation.
 *
 * @param currentStep Current billing step
 * @param language Current UI language
 * @param onBackClick Callback when back button is clicked
 * @param modifier Optional modifier
 */
@Composable
fun StepIndicator(
    currentStep: BillingStep,
    language: Language,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isFirstStep = currentStep == BillingStep.SELECT_ITEMS

    val stepLabel =
        when (currentStep) {
            BillingStep.SELECT_ITEMS -> str(BillingKeys.step1Label)
            BillingStep.REVIEW_SELECTION -> str(BillingKeys.step2Label)
            BillingStep.ENTER_PAYMENTS -> str(BillingKeys.step3Label)
            BillingStep.RECEIPT -> str(BillingKeys.step4Label)
        }

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Previous step button (disabled on first step)
        PosTextButton(
            text = getPreviousStepLabel(language),
            onClick = onBackClick,
            enabled = !isFirstStep,
        )

        // Current step label
        Text(
            text = stepLabel,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Get "Previous" label for the back button.
 */
private fun getPreviousStepLabel(language: Language): String =
    when (language) {
        Language.GERMAN -> "Zurück"
        Language.ENGLISH -> "Previous"
        Language.CHINESE -> "上一步"
    }
