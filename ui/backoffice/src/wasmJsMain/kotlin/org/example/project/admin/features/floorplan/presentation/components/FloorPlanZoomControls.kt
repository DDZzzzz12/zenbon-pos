package org.example.project.admin.features.floorplan.presentation.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosIconButton
import kotlin.math.roundToInt

/**
 * Zoom in / zoom out / fit with the current percentage between the first two, over the canvas: a
 * vertical stack ("+ 100 % − fit") beside the palette column, a horizontal cluster ("− 100 % + fit")
 * when [horizontal]. Square, bordered, 48dp targets.
 */
@Composable
fun FloorPlanZoomControls(
    zoom: Float,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFit: () -> Unit,
    canZoomIn: Boolean,
    canZoomOut: Boolean,
    canFit: Boolean,
    modifier: Modifier = Modifier,
    horizontal: Boolean = false,
) {
    val percent = str(AdminStringKeys.FloorPlanZoomPercent, (zoom * 100).roundToInt().toString())
    val plus: @Composable () -> Unit = {
        PosIconButton(Icons.Default.Add, str(AdminStringKeys.FloorPlanZoomIn), onZoomIn, enabled = canZoomIn, size = 48.dp)
    }
    val minus: @Composable () -> Unit = {
        PosIconButton(Icons.Default.Remove, str(AdminStringKeys.FloorPlanZoomOut), onZoomOut, enabled = canZoomOut, size = 48.dp)
    }
    val label: @Composable () -> Unit = {
        Text(
            text = percent,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 24.dp).padding(horizontal = 4.dp),
        )
    }
    val fit: @Composable () -> Unit = {
        PosIconButton(Icons.Default.FitScreen, str(AdminStringKeys.FloorPlanZoomFit), onFit, enabled = canFit, size = 48.dp)
    }
    Surface(
        modifier = modifier.border(1.dp, MaterialTheme.colorScheme.outline),
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        if (horizontal) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                minus()
                label()
                plus()
                fit()
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                plus()
                label()
                minus()
                fit()
            }
        }
    }
}
