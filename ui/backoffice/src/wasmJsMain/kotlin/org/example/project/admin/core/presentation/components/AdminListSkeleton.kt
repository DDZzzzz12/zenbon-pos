package org.example.project.admin.core.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/** Enough placeholder rows to fill a viewport, not enough to pretend the page is already full. */
private const val SKELETON_ROWS = 8

private val ROW_HEIGHT = 28.dp

/**
 * Placeholder rows shown while a list loads.
 *
 * Replaces `PosLoadingOverlay` on the list screens: the overlay blanks the whole page, so clicking a
 * filter chip made the toolbar the operator had just used disappear along with the rows. Here the
 * chrome stays put and only the rows read as pending.
 *
 * No shimmer animation. An infinite animation on the Compose/wasm canvas costs a frame budget the
 * admin panel has no reason to spend, and `prefers-reduced-motion` is not observable from here.
 */
@Composable
fun AdminListSkeleton(
    modifier: Modifier = Modifier,
    rows: Int = SKELETON_ROWS,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(rows) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(ROW_HEIGHT)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
    }
}
