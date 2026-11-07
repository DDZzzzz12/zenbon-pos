package org.example.project.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str

enum class PosNotificationSeverity {
    SUCCESS,
    WARNING,
    ERROR,
}

@Composable
fun PosNotificationPopup(
    title: String,
    message: String,
    severity: PosNotificationSeverity,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    dismissEnabled: Boolean = true,
) {
    Dialog(
        onDismissRequest = { if (dismissEnabled) onDismiss() },
        properties =
            DialogProperties(
                dismissOnBackPress = dismissEnabled,
                dismissOnClickOutside = dismissEnabled,
                usePlatformDefaultWidth = false,
            ),
    ) {
        Surface(
            modifier = modifier.widthIn(max = 380.dp).padding(24.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Icon circle
                val accentColor = severity.accentColor()
                Box(
                    modifier =
                        Modifier
                            .size(48.dp)
                            .background(
                                color = accentColor.copy(alpha = 0.1f),
                                shape = CircleShape,
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = severity.icon(),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = accentColor,
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Title
                Text(
                    text = title,
                    style =
                        MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Message
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Footer buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    if (dismissEnabled) {
                        PosSecondaryButton(
                            text = str(CommonKeys.close),
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    if (onRetry != null) {
                        // raw Material 3 by design: this IS a Pos* template
                        Button(
                            onClick = onRetry,
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = MaterialTheme.shapes.small,
                            colors =
                                ButtonDefaults.buttonColors(
                                    containerColor = accentColor,
                                    contentColor = Color.White,
                                ),
                        ) {
                            Text(text = str(CommonKeys.retry))
                        }
                    }
                }
            }
        }
    }
}

private fun PosNotificationSeverity.icon(): ImageVector =
    when (this) {
        PosNotificationSeverity.SUCCESS -> Icons.Default.CheckCircle
        PosNotificationSeverity.WARNING -> Icons.Default.Warning
        PosNotificationSeverity.ERROR -> Icons.Default.Error
    }
