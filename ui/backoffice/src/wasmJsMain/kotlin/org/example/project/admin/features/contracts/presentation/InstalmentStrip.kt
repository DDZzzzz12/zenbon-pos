package org.example.project.admin.features.contracts.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.dto.contract.ContractDto
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.LocalAppLanguage
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.formatYearMonth
import org.example.project.ui.theme.PosSpacing

private val CELL_WIDTH = 72.dp
private val CELL_HEIGHT = 56.dp

/**
 * A contract's instalments as a wrapping row of period cells, newest last.
 *
 * A gap is what this exists to show: instalments are a regular series, so a differently-coloured
 * cell in an otherwise even row is visible before any number is read. A table of the same data
 * would need every row read to notice the same thing.
 *
 * Colour carries the status, and the cell also carries its period label and its status word —
 * colour alone would be unreadable to an operator who cannot distinguish red from green.
 *
 * **No `PosCard` in the row.** It forces `fillMaxWidth` internally, so every cell would measure to
 * the full width. A plain bounded layout with a background and a clip instead.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InstalmentStrip(
    instalments: List<ContractDto.InstalmentDto>,
    interval: String,
    selectedId: Long?,
    onSelect: (ContractDto.InstalmentDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    val language = LocalAppLanguage.current
    // Wraps instead of scrolling: on a phone a horizontal row clipped its last cell with no hint.
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(PosSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(PosSpacing.sm),
    ) {
        instalments.forEach { instalment ->
            InstalmentCell(
                instalment = instalment,
                label = instalmentLabel(instalment.dueDate, interval, language),
                isSelected = instalment.id == selectedId,
                onSelect = onSelect,
            )
        }
    }
}

@Composable
private fun InstalmentCell(
    instalment: ContractDto.InstalmentDto,
    label: String,
    isSelected: Boolean,
    onSelect: (ContractDto.InstalmentDto) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val background =
        when (instalment.status) {
            "PAID" -> colors.primaryContainer
            "PARTIAL" -> colors.tertiaryContainer
            "OVERDUE" -> colors.errorContainer
            // WAIVED is written off, not outstanding: the same neutral as OPEN, faded, so it reads
            // as "nothing is expected here" rather than as a gap.
            "WAIVED" -> colors.surfaceVariant.copy(alpha = WAIVED_ALPHA)
            else -> colors.surfaceVariant
        }
    Column(
        modifier =
            Modifier
                .width(CELL_WIDTH)
                .height(CELL_HEIGHT)
                .clip(MaterialTheme.shapes.small)
                .background(background)
                // A border, not a size change: the strip's evenness is the whole signal, so the
                // selected cell must not become a different shape from its neighbours.
                .then(
                    if (isSelected) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
                    } else {
                        Modifier
                    },
                ).clickable { onSelect(instalment) }
                .padding(horizontal = 4.dp, vertical = PosSpacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = str(statusKey(instalment.status)),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

private const val WAIVED_ALPHA = 0.4f

/** The status arrives already derived from the server; the client only names it. */
internal fun statusKey(status: String): StringKey =
    when (status) {
        "PAID" -> AdminStringKeys.ContractStatusPaid
        "PARTIAL" -> AdminStringKeys.ContractStatusPartial
        "OVERDUE" -> AdminStringKeys.ContractStatusOverdue
        "WAIVED" -> AdminStringKeys.ContractStatusWaived
        else -> AdminStringKeys.ContractStatusOpen
    }

/**
 * The cell's period label, shaped by the contract [interval] so cells of one series differ:
 * a weekly series shows the day (`02.10.`), month-stepped ones the month (`10.2026`), a yearly one
 * the year, and a one-off or unknown interval the short German date (`02.10.26`, which fits the cell). [dueDate] is ISO
 * (`2026-10-02` or `2026-10-02T00:00`); anything else is returned unchanged. The month and the day
 * follow the UI [language] ([formatYearMonth]: `10.2026` / `2026年10月`; weekly `10月02日` in Chinese).
 */
internal fun instalmentLabel(
    dueDate: String,
    interval: String,
    language: Language = Language.GERMAN,
): String {
    val parts = dueDate.substringBefore('T').split('-')
    if (parts.size != 3 || parts[0].length != 4 || parts[1].length != 2 || parts[2].length != 2) return dueDate
    val (year, month, day) = parts
    return when (interval) {
        "WEEKLY" -> if (language == Language.CHINESE) "${month}月${day}日" else "$day.$month."
        "MONTHLY", "QUARTERLY", "SEMIANNUAL" -> dueDate.formatYearMonth(language)
        "ANNUAL" -> year
        else -> "$day.$month.${year.takeLast(2)}"
    }
}
