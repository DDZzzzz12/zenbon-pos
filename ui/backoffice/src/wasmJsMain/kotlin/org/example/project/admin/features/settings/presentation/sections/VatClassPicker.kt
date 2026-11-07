package org.example.project.admin.features.settings.presentation.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.setup.VatDto
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.ui.components.PosDropdown
import org.example.project.ui.components.PosDropdownItem
import org.example.project.ui.components.PosPickerField
import org.example.project.ui.components.PosSegmentedButtonRow
import org.example.project.ui.components.formatSuffixedPercentForDisplay

/**
 * The three VAT primary keys the server accepts in a mapping. Not derived from the configured VAT
 * rows: `VatRules.isValid()` and `ConfigurationRoutes`' PUT both hard-restrict the set to A, B, C, so
 * offering anything else would build a payload the server answers 400 to.
 */
private val VAT_CLASS_IDS = listOf("A", "B", "C")

/**
 * Below this content width the strip (label column + up to four segments) cannot fit, so the rule
 * is picked from a dropdown. Measured: the phone edit dialog leaves ~236dp, the strip needs ~430dp.
 */
private val SEGMENTED_MIN_WIDTH = 480.dp

/** Whether [contentWidth] is too narrow for the segmented strip. */
internal fun usesPickerField(contentWidth: Dp): Boolean = contentWidth < SEGMENTED_MIN_WIDTH

/**
 * One rule row of the VAT-mapping editor: a label and a single-choice strip over A/B/C.
 *
 * [optional] adds a leading "not set" segment, which is how `VatRules.toGo` and `.reduced` express
 * "fall back to the default class". The default rule is not optional — [org.example.project.core.domain.models.setup.VatRules.default]
 * is non-null and every booking resolves through it.
 *
 * Segments are labelled with the live percentage from [vatRates], matched on `vatId` — the mapping
 * stores primary keys, so matching on `vatClass` (as the deleted composeApp row did) never matches.
 */
@Composable
fun VatClassPicker(
    label: String,
    selected: String?,
    vatRates: List<VatDto.VatResponseDto>,
    notSetLabel: String,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    optional: Boolean = false,
    enabled: Boolean = true,
) {
    val options: List<String?> = if (optional) listOf(null) + VAT_CLASS_IDS else VAT_CLASS_IDS
    val language = LocalAppLanguage.current
    val optionLabel: @Composable (String?) -> String = { option ->
        if (option == null) {
            notSetLabel
        } else {
            vatRates.firstOrNull { it.vatId == option }?.let { "$option · ${formatSuffixedPercentForDisplay(it.vatPercentage, language)}" } ?: option
        }
    }
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        if (usesPickerField(maxWidth)) {
            // Every option stays selectable: the same A/B/C (+ "not set") set, one tap away.
            var open by remember { mutableStateOf(false) }
            val fieldWidth = maxWidth
            Box(modifier = Modifier.fillMaxWidth()) {
                PosPickerField(
                    value = optionLabel(selected),
                    onClick = { open = true },
                    label = label,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                )
                PosDropdown(
                    expanded = open,
                    onDismissRequest = { open = false },
                    minWidth = fieldWidth,
                    maxWidth = fieldWidth,
                ) {
                    options.forEach { option ->
                        PosDropdownItem(
                            text = optionLabel(option),
                            selected = option == selected,
                            minHeight = 48.dp,
                            onClick = {
                                onSelect(option)
                                open = false
                            },
                        )
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.width(110.dp),
                )
                PosSegmentedButtonRow(
                    options = options,
                    selectedOption = selected,
                    onOptionSelected = onSelect,
                    enabled = enabled,
                    optionLabel = { option -> optionLabel(option) },
                )
            }
        }
    }
}
