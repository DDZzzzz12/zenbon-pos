package org.example.project.ui.components.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import org.example.project.money.Decimal

/**
 * A minimal trend line over a series of money strings.
 *
 * Charts are the one place this codebase turns money into `Double`: pixel coordinates cannot be
 * computed in decimal, and `platform/money/README.md` sanctions `toDoubleLossy()` for exactly this. Going
 * through `Decimal.parse` rather than `String.toDoubleOrNull()` keeps the lossy step visible in
 * review and — the real fix over the original — drops an unparseable value instead of plotting it
 * as a zero trough that reads as a genuine dip.
 */
@Composable
fun Sparkline(
    values: List<String>,
    modifier: Modifier = Modifier,
) {
    if (values.size < 2) return
    val nums = values.mapNotNull { runCatching { Decimal.parse(it).toDoubleLossy() }.getOrNull() }
    if (nums.size < 2) return
    val mn = nums.min()
    val mx = nums.max()
    val range = (mx - mn).coerceAtLeast(1e-6)
    val color = MaterialTheme.colorScheme.primary

    Canvas(modifier = modifier.height(32.dp)) {
        val w = size.width
        val h = size.height
        val stepX = w / (nums.size - 1)
        val path = Path()
        nums.forEachIndexed { i, v ->
            val nx = stepX * i
            val ny = h - ((v - mn) / range * h).toFloat()
            if (i == 0) path.moveTo(nx, ny) else path.lineTo(nx, ny)
        }
        drawPath(path, color, style = Stroke(width = 2f))
    }
}
