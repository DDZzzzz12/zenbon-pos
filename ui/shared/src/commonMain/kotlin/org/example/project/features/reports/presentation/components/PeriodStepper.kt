package org.example.project.features.reports.presentation.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import org.example.project.core.presentation.components.picker.CustomDateRangePickerDialog
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.ReportsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.reports.presentation.ReportsGranularity
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosSegmentedButtonRow
import org.example.project.ui.theme.PosSpacing

/**
 * A period selector: a granularity toggle (whichever segments [granularities] names — Berichte's four
 * anchored periods by default) and a back/label/forward stepper sitting side by side on one row
 * (wrapping to a second line only when the width can't hold both). Forward is disabled at the current
 * period — you cannot report the future. Stateless apart from the local open/closed flag for the
 * picker; the caller owns the granularity, the pre-formatted [label], and every callback.
 *
 * Clicking the label opens [PeriodPickerDialog], which adapts to [granularity] and lets the owner jump
 * straight to any past period; a picked period is reported through [onPickPeriod]. When [granularity]
 * is [ReportsGranularity.CUSTOM] the chevrons disable (there is no "next"/"previous" free range) and
 * the label instead opens [CustomDateRangePickerDialog], reporting the picked range through
 * [onPickRange].
 *
 * @param onPickRange Required whenever [granularities] contains [ReportsGranularity.CUSTOM] — that is
 * the only way a picked custom range reaches the caller. Meaningless (and fine to leave null)
 * otherwise. Enforced at the top of the function body.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PeriodStepper(
    granularity: ReportsGranularity,
    anchor: LocalDate,
    label: String,
    today: LocalDate,
    canStepForward: Boolean,
    onGranularity: (ReportsGranularity) -> Unit,
    onStepBack: () -> Unit,
    onStepForward: () -> Unit,
    onPickPeriod: (LocalDate) -> Unit,
    /** False greys the whole control — for views the period does not filter. */
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    granularities: List<ReportsGranularity> = ReportsGranularity.reporting,
    onPickRange: ((LocalDate, LocalDate) -> Unit)? = null,
) {
    require(ReportsGranularity.CUSTOM !in granularities || onPickRange != null) {
        "PeriodStepper: granularities contains CUSTOM, so onPickRange must be provided"
    }

    var showPicker by remember { mutableStateOf(false) }

    FlowRow(
        // Dimmed as a whole when disabled: the chevrons carry their own disabled tint, but the
        // granularity row and the clickable label would otherwise look live while silently
        // swallowing clicks. The point of greying rather than hiding is that the state is visible.
        modifier = modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.38f),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PosSegmentedButtonRow(
            options = granularities,
            selectedOption = granularity,
            onOptionSelected = { if (enabled) onGranularity(it) },
            modifier = Modifier.align(Alignment.CenterVertically),
            optionLabel = { str(it.labelKey) },
        )

        Row(
            modifier =
                Modifier
                    .align(Alignment.CenterVertically)
                    .clip(MaterialTheme.shapes.small)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PosIconButton(
                icon = Icons.Default.ChevronLeft,
                contentDescription = str(ReportsKeys.stepBack),
                onClick = onStepBack,
                enabled = enabled && granularity != ReportsGranularity.CUSTOM,
                size = 36.dp,
                iconSize = 20.dp,
            )
            val labelInteractionSource = remember { MutableInteractionSource() }
            val labelHovered by labelInteractionSource.collectIsHoveredAsState()
            Text(
                text = label,
                modifier =
                    Modifier
                        .widthIn(min = 104.dp)
                        .clip(MaterialTheme.shapes.small)
                        .padding(horizontal = 8.dp, vertical = PosSpacing.sm)
                        .clickable(
                            interactionSource = labelInteractionSource,
                            indication = null,
                            enabled = enabled,
                            role = Role.Button,
                        ) { showPicker = true },
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                color = if (labelHovered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (labelHovered) TextDecoration.Underline else null,
            )
            PosIconButton(
                icon = Icons.Default.ChevronRight,
                contentDescription = str(ReportsKeys.stepForward),
                onClick = onStepForward,
                enabled = enabled && canStepForward && granularity != ReportsGranularity.CUSTOM,
                size = 36.dp,
                iconSize = 20.dp,
            )
        }
    }

    if (showPicker) {
        if (granularity == ReportsGranularity.CUSTOM) {
            CustomDateRangePickerDialog(
                onDismiss = { showPicker = false },
                onDateRangeSelected = { start, end ->
                    showPicker = false
                    onPickRange?.invoke(start, end)
                },
                language = LocalAppLanguage.current,
            )
        } else {
            PeriodPickerDialog(
                granularity = granularity,
                anchor = anchor,
                today = today,
                onPick = { picked ->
                    showPicker = false
                    onPickPeriod(picked)
                },
                onDismiss = { showPicker = false },
            )
        }
    }
}
