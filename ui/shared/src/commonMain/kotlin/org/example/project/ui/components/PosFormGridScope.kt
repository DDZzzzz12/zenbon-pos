package org.example.project.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/** What a [PosFormGrid] body can place: one-column [Cell]s and [FullRow]s that span the line. */
@Stable
class PosFormGridScope internal constructor(
    private val cellWidth: Dp,
    private val lineWidth: Dp,
) {
    /** A field. One per line on a phone, 2-3 per line when the grid is wide. */
    @Composable
    fun Cell(content: @Composable () -> Unit) {
        Box(cellWidth.widthOrFill()) { content() }
    }

    /** A hint, a multi-line field or anything else that should always take the whole line. */
    @Composable
    fun FullRow(content: @Composable () -> Unit) {
        Box(lineWidth.widthOrFill()) { content() }
    }
}

/** `Modifier.width` throws on an infinite or unspecified Dp, so an unbounded grid fills instead. */
private fun Dp.widthOrFill(): Modifier = if (value.isFinite())Modifier.width(this) else Modifier.fillMaxWidth()
