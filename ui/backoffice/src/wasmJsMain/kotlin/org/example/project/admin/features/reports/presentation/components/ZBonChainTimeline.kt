package org.example.project.admin.features.reports.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDto
import kotlin.time.ExperimentalTime

/**
 * The Z-Bon chain drawn against the period: one bar per Z-Bon, and a full-height error bar for any
 * gap that has bills in it. A gap with no bills is not drawn — the till was shut, which is not a
 * finding.
 *
 * Timestamps that fail to parse collapse to the range start rather than throwing; the section
 * around this canvas already shows the same rows as text.
 */
@OptIn(ExperimentalTime::class)
@Composable
fun ZBonChainTimeline(
    rangeStart: LocalDateTime,
    rangeEnd: LocalDateTime,
    rows: List<MonthlyCloseDto.ZBonRollupRowDto>,
    gaps: List<MonthlyCloseDto.ZBonChainGapDto>,
    modifier: Modifier = Modifier,
) {
    val tz = TimeZone.currentSystemDefault()
    val startMs = rangeStart.toInstant(tz).toEpochMilliseconds()
    val totalMs = (rangeEnd.toInstant(tz).toEpochMilliseconds() - startMs).coerceAtLeast(1L)
    val zBonColor = MaterialTheme.colorScheme.primary
    val gapColor = MaterialTheme.colorScheme.error

    fun ratio(iso: String): Float {
        val ldt = runCatching { LocalDateTime.parse(iso) }.getOrElse { return 0f }
        return (ldt.toInstant(tz).toEpochMilliseconds() - startMs).toFloat() / totalMs
    }

    Canvas(modifier.height(36.dp).fillMaxWidth()) {
        rows.forEach { row ->
            val x0 = ratio(row.startTime) * size.width
            val x1 = ratio(row.endTime) * size.width
            drawRect(
                color = zBonColor,
                topLeft = Offset(x0, size.height * 0.25f),
                size = Size((x1 - x0).coerceAtLeast(2f), size.height * 0.5f),
            )
        }
        gaps.filter { it.hasBillsInGap }.forEach { gap ->
            val x0 = ratio(gap.gapStart) * size.width
            val x1 = ratio(gap.gapEnd) * size.width
            drawRect(
                color = gapColor,
                topLeft = Offset(x0, 0f),
                size = Size((x1 - x0).coerceAtLeast(2f), size.height),
            )
        }
    }
}
