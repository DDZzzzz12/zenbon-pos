package org.example.project.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.isAtLeastMedium
import org.example.project.core.utils.touchTargetSize
import org.example.project.ui.theme.PosSpacing

enum class PosToastType {
    SUCCESS,
    ERROR,
    WARNING,
}

@Composable
fun PosToast(
    title: String,
    message: String,
    type: PosToastType,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    autoDismissMs: Long? = null,
) {
    var visible by remember { mutableStateOf(true) }

    LaunchedEffect(autoDismissMs) {
        if (autoDismissMs != null) {
            delay(autoDismissMs)
            visible = false
            delay(300) // wait for exit animation
            onDismiss()
        }
    }

    val borderColor = type.accentColor()
    val icon = type.icon()

    AnimatedVisibility(
        visible = visible,
        enter =
            fadeIn(
                animationSpec = tween(200, easing = EaseInOut),
            ) +
                slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(300, easing = EaseInOut),
                ),
        exit =
            fadeOut(
                animationSpec = tween(150, easing = EaseInOut),
            ) +
                slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(200, easing = EaseInOut),
                ),
        modifier = modifier,
    ) {
        Surface(
            modifier =
                Modifier
                    .widthIn(max = if (currentWindowAdaptiveInfo().windowSizeClass.isAtLeastMedium()) 480.dp else 360.dp)
                    .border(
                        width = 1.5.dp,
                        color = borderColor,
                        shape = MaterialTheme.shapes.medium,
                    ),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = PosSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Accent icon with colored circle background
                Box(
                    modifier =
                        Modifier
                            .size(36.dp)
                            .background(
                                color = borderColor,
                                shape = CircleShape,
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = Color.White,
                    )
                }

                // Title + message column
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    PosAutoSizeText(
                        text = title,
                        style =
                            MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Optional action button (e.g., "Retry" for errors)
                if (actionLabel != null && onAction != null) {
                    // raw Material 3 by design: this IS a Pos* template
                    TextButton(
                        onClick = {
                            onAction()
                            onDismiss()
                        },
                    ) {
                        Text(
                            text = actionLabel,
                            style =
                                MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Medium,
                                ),
                            color = borderColor,
                        )
                    }
                }

                // Close button
                // raw Material 3 by design: this IS a Pos* template
                IconButton(
                    onClick = {
                        visible = false
                        onDismiss()
                    },
                    modifier = Modifier.size(touchTargetSize(32.dp)),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = str(CommonKeys.close),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun PosToastType.icon(): ImageVector =
    when (this) {
        PosToastType.SUCCESS -> Icons.Default.CheckCircle
        PosToastType.ERROR -> Icons.Default.Error
        PosToastType.WARNING -> Icons.Default.Warning
    }
