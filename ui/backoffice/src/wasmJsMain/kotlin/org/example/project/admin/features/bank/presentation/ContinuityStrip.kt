package org.example.project.admin.features.bank.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.bank.BankAccountDto
import org.example.project.ui.theme.PosSpacing

private val CELL_HEIGHT = 22.dp
private val PRESENT_CELL_WIDTH = 26.dp
private val GAP_CELL_WIDTH = 14.dp

/**
 * One cell per statement held, oldest to newest, with a hollow cell wherever one is missing.
 *
 * This is the reason `GET /api/bank-accounts/{id}/statements` exists. The account list reports the
 * same finding as `continuityBreaks` — a list of bare statement **ids** — which the old Lücken card
 * rendered by joining into a comma-separated string, so an operator was shown database keys and told
 * they were missing periods. A hole in a pattern is read at a glance; a row of ids is not read at
 * all.
 *
 * A statement flagged [BankAccountDto.StatementDto.continuityBreak] does not itself continue the
 * previous one, so the hollow cell is drawn **before** it — that is where the missing statement
 * belongs. The first statement continues nothing and is therefore never a break.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContinuityStrip(
    statements: List<BankAccountDto.StatementDto>,
    presentColor: Color,
    gapColor: Color,
    modifier: Modifier = Modifier,
) {
    if (statements.isEmpty()) return
    // A FlowRow, not a Row: 12+ statements ran past the pane edge on a phone and the last cells
    // were clipped. Every cell stays visible; a long history wraps to a second line.
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PosSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(PosSpacing.xs),
    ) {
        statements.forEach { statement ->
            if (statement.continuityBreak) GapCell(gapColor)
            PresentCell(presentColor)
        }
    }
}

@Composable
private fun PresentCell(color: Color) {
    Box(
        modifier =
            Modifier
                .width(PRESENT_CELL_WIDTH)
                .height(CELL_HEIGHT)
                .clip(MaterialTheme.shapes.small)
                .background(color),
    )
}

/** Hollow, not merely a different fill: "nothing is here" should not look like a kind of content. */
@Composable
private fun GapCell(color: Color) {
    Box(
        modifier =
            Modifier
                .width(GAP_CELL_WIDTH)
                .height(CELL_HEIGHT)
                .border(1.dp, color, MaterialTheme.shapes.small),
    )
}
