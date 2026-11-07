package org.example.project.ui.components.beleg

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * One block of the [BelegFlowStrip]: a stage of the document flow with its live count.
 *
 * @property id stable key handed back to the strip's `onSelect`; the caller maps it to a bucket or a
 *   destination.
 * @property count null while unknown (not loaded yet), drawn as an em dash rather than zero.
 * @property sub the short line under the label on the Large layout ("8.420,30 € offen").
 * @property selected the stage the screen is showing, drawn in the primary container.
 */
data class BelegStage(
    val id: String,
    val label: String,
    val count: Long?,
    val icon: ImageVector,
    val sub: String? = null,
    val selected: Boolean = false,
)
