/**
 * Draggable FAB for the floor plan view.
 *
 * Material 3 floating action button with:
 * - Draggable positioning
 * - Combine-mode toggle
 *
 * Layout authoring (adding/moving/deleting tables and room elements) lives in the
 * web admin panel, so this FAB carries no edit affordances.
 *
 * @since 2.0.0
 * @author Generated with Claude Code
 */
package org.example.project.ui.screens.dashboard

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.keys.FloorPlanKeys
import org.example.project.core.presentation.strings.str

/**
 * Draggable FAB carrying the floor plan's combine-mode toggle.
 *
 * @param isCombineMode Whether combine mode is currently active
 * @param onCombineModeToggle Callback to toggle combine mode
 * @param modifier Modifier for customization
 */
@Composable
fun PosFloorPlanFAB(
    isCombineMode: Boolean = false,
    onCombineModeToggle: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density

    // FAB position state (in DP units)
    var fabOffset by remember {
        mutableStateOf(
            Offset(
                x = 16f, // Default: bottom-right with padding
                y = 16f,
            ),
        )
    }

    Box(
        modifier =
            modifier
                .offset(
                    x = fabOffset.x.dp,
                    y = fabOffset.y.dp,
                ).pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        // Update offset in DP units
                        fabOffset +=
                            Offset(
                                x = dragAmount.x / density,
                                y = dragAmount.y / density,
                            )
                    }
                },
    ) {
        FloatingActionButton(
            onClick = onCombineModeToggle,
            modifier = Modifier.size(48.dp),
            containerColor =
                if (isCombineMode) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
            contentColor =
                if (isCombineMode) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
        ) {
            Icon(
                Icons.Default.Link,
                contentDescription = str(FloorPlanKeys.combineMode),
            )
        }
    }
}
