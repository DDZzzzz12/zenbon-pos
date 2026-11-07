package org.example.project.features.reports.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import org.example.project.core.presentation.components.picker.CustomDateRangePickerDialog
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.keys.ReportsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.reports.presentation.ReportsGranularity
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.theme.PosSpacing

/**
 * Phone form of [PeriodStepper]: `‹ label ›` on one row with 48dp targets. The granularity is chosen
 * elsewhere (the filter panel). The label opens the very same [PeriodPickerDialog]; a
 * [ReportsGranularity.CUSTOM] range has no "next"/"previous" and no single-period picker, so the
 * chevrons are inert there, and so is the label unless [onPickRange] is given: then, as in
 * [PeriodStepper], the label opens [CustomDateRangePickerDialog] and reports the picked range.
 */
@Composable
fun PeriodStepperCompact(
    label: String,
    canStepForward: Boolean,
    onStepBack: () -> Unit,
    onStepForward: () -> Unit,
    onPickPeriod: (LocalDate) -> Unit,
    anchor: LocalDate,
    granularity: ReportsGranularity,
    today: LocalDate,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onPickRange: ((LocalDate, LocalDate) -> Unit)? = null,
) {
    var showPicker by remember { mutableStateOf(false) }
    val custom = granularity == ReportsGranularity.CUSTOM
    val stepping = enabled && !custom
    val labelOpensPicker = stepping || (enabled && custom && onPickRange != null)

    Row(
        modifier = modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.38f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PosIconButton(
            icon = Icons.Default.ChevronLeft,
            contentDescription = str(ReportsKeys.stepBack),
            onClick = onStepBack,
            enabled = stepping,
            size = 48.dp,
        )
        Text(
            text = label,
            modifier =
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clickable(enabled = labelOpensPicker, role = Role.Button) { showPicker = true }
                    .padding(horizontal = PosSpacing.md, vertical = PosSpacing.lg),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
        )
        PosIconButton(
            icon = Icons.Default.ChevronRight,
            contentDescription = str(ReportsKeys.stepForward),
            onClick = onStepForward,
            enabled = stepping && canStepForward,
            size = 48.dp,
        )
    }

    if (showPicker && custom && onPickRange != null) {
        CustomDateRangePickerDialog(
            onDismiss = { showPicker = false },
            onDateRangeSelected = { start, end ->
                showPicker = false
                onPickRange(start, end)
            },
            language = LocalAppLanguage.current,
        )
    } else if (showPicker) {
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
