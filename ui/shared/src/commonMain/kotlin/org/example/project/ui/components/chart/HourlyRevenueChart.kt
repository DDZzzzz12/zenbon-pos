package org.example.project.ui.components.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.example.project.money.Decimal
import org.example.project.ui.components.formatMoneyForDisplay

private class HourBar(
    val hour: Int,
    val amount: String,
    val value: Double,
)

/**
 * Revenue per traded hour of day, as a bar chart with hour tick labels and a hover/tap tooltip.
 *
 * The x-axis is trimmed to [tradedHourRange] (ends only — interior gaps kept), so a shop that opens
 * at 11:00 no longer shows a wall of empty morning bars. Money → Double is the one sanctioned lossy
 * conversion (bar heights are pixels); the tooltip formats the original server string via
 * [formatMoneyForDisplay] so the exact amount is never rounded through a Double.
 */
@Composable
fun HourlyRevenueChart(
    hourlyRevenue: Map<Int, String>,
    currency: String,
    modifier: Modifier = Modifier,
) {
    val range = tradedHourRange(hourlyRevenue) ?: return
    val bars =
        range.map { hour ->
            val amount = hourlyRevenue[hour] ?: "0.00"
            HourBar(hour, amount, Decimal.parseOrNull(amount)?.toDoubleLossy() ?: 0.0)
        }
    val max = bars.maxOf { it.value }.coerceAtLeast(1e-6)
    val baseColor = MaterialTheme.colorScheme.primary
    val mutedColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
    val density = LocalDensity.current

    var hovered by remember { mutableStateOf<Int?>(null) }
    var widthPx by remember { mutableStateOf(0f) }

    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(120.dp)) {
            Canvas(
                modifier =
                    Modifier.fillMaxWidth().height(120.dp).pointerInput(bars.size) {
                        widthPx = size.width.toFloat()
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val pos = event.changes.first().position
                                hovered =
                                    when (event.type) {
                                        PointerEventType.Exit -> null
                                        else -> {
                                            val slot = size.width / bars.size
                                            (pos.x / slot).toInt().coerceIn(0, bars.size - 1)
                                        }
                                    }
                            }
                        }
                    },
            ) {
                val slot = size.width / bars.size
                val barWidth = slot * 0.7f
                bars.forEachIndexed { i, bar ->
                    val h = (bar.value / max * size.height).toFloat()
                    drawRect(
                        color = if (hovered == i) baseColor else mutedColor,
                        topLeft = Offset(slot * i + (slot - barWidth) / 2f, size.height - h),
                        size = Size(barWidth, h),
                    )
                }
            }

            hovered?.let { idx ->
                val bar = bars[idx]
                val slot = if (widthPx > 0f) widthPx / bars.size else 0f
                val centerPx = slot * idx + slot / 2f
                val xDp = with(density) { centerPx.toDp() } - 60.dp
                Surface(
                    tonalElevation = 3.dp,
                    shadowElevation = 2.dp,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier =
                        Modifier
                            .align(Alignment.TopStart)
                            .offset(x = xDp.coerceIn(0.dp, (with(density) { widthPx.toDp() } - 120.dp).coerceAtLeast(0.dp))),
                ) {
                    Text(
                        text = "${bar.hour} · ${formatMoneyForDisplay(bar.amount, currency)}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }

        val stride = if (bars.size > 12) 2 else 1
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            bars.forEachIndexed { i, bar ->
                Text(
                    text = if (i % stride == 0) bar.hour.toString() else "",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
