package org.example.project.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun PosCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    inactive: Boolean = false,
    status: PosCardStatus? = null,
    variant: PosCardVariant = PosCardVariant.Tonal,
    accentColor: Color? = null,
    accentWidth: Dp = PosCardDefaults.AccentStripeWidth,
    containerColor: Color? = null,
    border: BorderStroke? = null,
    tonalElevation: Dp? = null,
    shape: Shape? = null,
    contentPadding: PaddingValues = PaddingValues(PosCardDefaults.CardPadding),
    contentSpacing: Dp = PosCardDefaults.CardContentSpacing,
    content: @Composable PosCardContent.() -> Unit,
) {
    val cardContent = PosCardContent().apply { content() }

    val resolvedContainer =
        resolvePosCardContainerColor(
            explicit = containerColor,
            inactive = inactive,
            selected = selected,
            status = status,
            variant = variant,
        )
    val resolvedBorder =
        resolvePosCardBorder(
            explicit = border,
            selected = selected,
            variant = variant,
        )
    val resolvedElevation =
        tonalElevation ?: when (variant) {
            PosCardVariant.Tonal -> if (selected) 2.dp else 0.dp
            PosCardVariant.Plain -> 0.dp
            PosCardVariant.Elevated -> 3.dp
        }
    val resolvedShape = shape ?: MaterialTheme.shapes.small
    val resolvedAccent = accentColor

    val clickableModifier =
        when {
            onClick != null && onLongClick != null ->
                Modifier.combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
            onClick != null -> Modifier.clickable(onClick = onClick)
            onLongClick != null ->
                Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = onLongClick,
                )
            else -> Modifier
        }

    val accentModifier =
        if (resolvedAccent != null) {
            // Draw the accent stripe ON TOP of the card surface, clipped to the card's rounded shape.
            // (drawBehind put it under the opaque container, so it only bled through at the rounded
            // corners.) clipPath keeps the stripe flush with the rounded edges while preserving the
            // card's elevation shadow, which a layout-level clip would otherwise cut off.
            Modifier.drawWithContent {
                drawContent()
                val outline = resolvedShape.createOutline(size, layoutDirection, this)
                clipPath(Path().apply { addOutline(outline) }) {
                    drawRect(
                        color = resolvedAccent,
                        topLeft = Offset.Zero,
                        size = Size(accentWidth.toPx(), size.height),
                    )
                }
            }
        } else {
            Modifier
        }
    val leadingInset = if (resolvedAccent != null) accentWidth else 0.dp

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .alpha(if (inactive) 0.5f else 1f)
                .then(clickableModifier)
                .then(accentModifier),
        colors = CardDefaults.cardColors(containerColor = resolvedContainer),
        border = resolvedBorder,
        elevation = CardDefaults.cardElevation(defaultElevation = resolvedElevation),
        shape = resolvedShape,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = leadingInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(contentSpacing),
            ) {
                cardContent.headerContent?.invoke()
                cardContent.bodyContent?.invoke()
            }

            cardContent.trailingContent?.invoke()
        }
    }
}

@Composable
private fun resolvePosCardContainerColor(
    explicit: Color?,
    inactive: Boolean,
    selected: Boolean,
    status: PosCardStatus?,
    variant: PosCardVariant,
): Color {
    if (explicit != null) return explicit
    if (inactive) return MaterialTheme.colorScheme.surfaceVariant.copy(alpha = PosCardDefaults.InactiveTintAlpha)
    if (selected) return MaterialTheme.colorScheme.primaryContainer.copy(alpha = PosCardDefaults.SelectedTintAlpha)
    if (status != null) return posCardStatusColor(status).copy(alpha = PosCardDefaults.StatusTintAlpha)
    return when (variant) {
        PosCardVariant.Tonal -> MaterialTheme.colorScheme.surfaceContainer
        PosCardVariant.Plain -> MaterialTheme.colorScheme.surface
        PosCardVariant.Elevated -> MaterialTheme.colorScheme.surface
    }
}

@Composable
private fun resolvePosCardBorder(
    explicit: BorderStroke?,
    selected: Boolean,
    variant: PosCardVariant,
): BorderStroke? {
    if (explicit != null) return explicit
    if (selected) return BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    return when (variant) {
        PosCardVariant.Plain -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        else -> null
    }
}

class PosCardContent {
    var headerContent: (@Composable () -> Unit)? = null
    var bodyContent: (@Composable () -> Unit)? = null
    var trailingContent: (@Composable () -> Unit)? = null

    fun header(content: @Composable () -> Unit) {
        headerContent = content
    }

    fun body(content: @Composable () -> Unit) {
        bodyContent = content
    }

    fun trailing(content: @Composable () -> Unit) {
        trailingContent = content
    }
}
