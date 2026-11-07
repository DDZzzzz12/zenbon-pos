package org.example.project.admin.features.settings.presentation.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.enums.setup.GermanVatRate
import org.example.project.ui.components.PosCard
import kotlin.math.round

/**
 * Picks a VAT rate from the closed statutory set, instead of accepting free text.
 *
 * German VAT rates are not open-ended, and the server already knows it: `VatRateGuard` rejects any
 * rate outside `DsfinvkExportDto.VatKeyMapping.MAPPED_RATES` with a 422. A text field that 422s on
 * most input is the wrong control for a domain with eleven legal values.
 *
 * But `MAPPED_RATES` mixes two populations. Five rates are currently valid; six are historical, kept
 * mappable only so data recorded under them still exports with its own Anlage-2 ID. Nothing
 * distinguished them at configuration time, so a shop could newly configure **16 %** — dead since
 * 2020 — and the server would accept it. [GermanVatRate.configurableRates] is the fix: this picker
 * offers only those.
 *
 * [current] is shown even when it is a historical rate, because an install may legitimately hold one
 * and hiding it would make the screen lie about the shop's own configuration. Such a rate renders
 * selected, disabled, and labelled via [historicalNote] — visible, not choosable.
 *
 * Labels arrive as parameters rather than being resolved here so this stays a pure presentational
 * component: the section host owns localization and passes `str(...)` values in.
 *
 * @param current the rate configured today, or null when nothing is set yet
 * @param historicalNote shown beside [current] when it is no longer configurable — should explain
 *   that the rate is retained for export and cannot be re-selected
 * @param enabled false renders the whole set read-only, e.g. below MANAGER
 */
@Composable
fun VatRatePicker(
    current: GermanVatRate?,
    onSelect: (GermanVatRate) -> Unit,
    historicalNote: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    // A historical rate is never in configurableRates, so it would otherwise vanish from the list.
    val rows =
        buildList {
            addAll(GermanVatRate.configurableRates)
            if (current != null && !current.configurable) add(current)
        }

    PosCard(modifier = modifier.fillMaxWidth()) {
        body {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                rows.forEach { rate ->
                    val selectable = enabled && rate.configurable
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // raw Material 3 by design: no Pos* radio component exists
                        RadioButton(
                            selected = rate == current,
                            onClick = { onSelect(rate) },
                            enabled = selectable,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = rate.formatPercent(),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (!rate.configurable) {
                                Text(
                                    text = historicalNote,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            text = "DSFinV-K ${rate.dsfinvkKey}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Formats the rate German-style — "19 %", "5,5 %", "10,7 %".
 *
 * Goes through per-mille integers rather than formatting the `Double` directly: 0.055 and 0.078 are
 * not exactly representable, so a naive `rate * 100` renders 5.500000000000001. `String.format` is
 * not an option either — it is JVM-only and this is wasm.
 */
private fun GermanVatRate.formatPercent(): String {
    val perMille = round(rate * 1000).toInt()
    val whole = perMille / 10
    val tenth = perMille % 10
    return if (tenth == 0) "$whole %" else "$whole,$tenth %"
}
