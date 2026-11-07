package org.example.project.ui.components.beleg

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.ui.theme.LocalPosColors
import org.example.project.ui.theme.PosSpacing

/** The dot colour of a [BelegTone], taken from the Zenbon status palette (never colour alone: see [PosStatusDot]). */
@Composable
fun belegToneColor(tone: BelegTone): Color {
    val colors = LocalPosColors.current
    return when (tone) {
        BelegTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
        BelegTone.Info -> colors.posReserved
        BelegTone.Warning -> colors.posOccupied
        BelegTone.Danger -> colors.posError
        BelegTone.Success -> colors.posAvailable
    }
}

/**
 * Status as an 8dp dot plus its word, so the state is never carried by colour alone.
 *
 * A status word is never truncated: with [wrap] the word may take several lines (a narrow table
 * column), otherwise it stays on one line and the caller must give it the room.
 */
@Composable
fun PosStatusDot(
    label: String,
    tone: BelegTone,
    modifier: Modifier = Modifier,
    wrap: Boolean = false,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PosSpacing.md - PosSpacing.xs),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(belegToneColor(tone)))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (wrap) Int.MAX_VALUE else 1,
            softWrap = wrap,
            overflow = TextOverflow.Clip,
            modifier = if (wrap) Modifier.weight(1f, fill = false) else Modifier,
        )
    }
}
