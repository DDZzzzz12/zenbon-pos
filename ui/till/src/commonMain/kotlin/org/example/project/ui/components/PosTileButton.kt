package org.example.project.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Stacked-content tile button: optional icon above a label, on a tappable surface.
 *
 * Use for action grids and navigation tile grids where the consumer expects a
 * square-ish target with the icon centred above text. For text-only buttons,
 * prefer [PosButton] / [PosSecondaryButton] / [PosTextButton].
 */
@Composable
fun PosTileButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    iconSize: Dp = 24.dp,
    iconLabelSpacing: Dp = 4.dp,
    contentPadding: PaddingValues = PaddingValues(8.dp),
    textStyle: TextStyle = MaterialTheme.typography.labelSmall,
    textMaxLines: Int = 1,
    minHeight: Dp = 70.dp,
    height: Dp? = null,
    shape: Shape = MaterialTheme.shapes.small,
    border: BorderStroke? = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    iconTint: Color = contentColor,
    selectedContainerColor: Color = MaterialTheme.colorScheme.primary,
    selectedContentColor: Color = MaterialTheme.colorScheme.onPrimary,
) {
    val resolvedContainer = if (selected) selectedContainerColor else containerColor
    val resolvedContent = if (selected) selectedContentColor else contentColor
    val resolvedIconTint = if (selected) selectedContentColor else iconTint
    val sizingModifier =
        if (height != null) {
            Modifier.height(height)
        } else {
            Modifier.heightIn(min = minHeight)
        }
    Surface(
        modifier =
            modifier
                .then(sizingModifier)
                .clickable(enabled = enabled, onClick = onClick),
        color = resolvedContainer,
        contentColor = resolvedContent,
        shape = shape,
        border = border,
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    modifier = Modifier.size(iconSize),
                    tint = resolvedIconTint,
                )
                if (iconLabelSpacing > 0.dp) {
                    Spacer(Modifier.height(iconLabelSpacing))
                }
            }
            PosAutoSizeText(
                text = label,
                style = textStyle,
                color = resolvedContent,
                textAlign = TextAlign.Center,
                maxLines = textMaxLines,
                minFontSize = 8.sp,
            )
        }
    }
}
