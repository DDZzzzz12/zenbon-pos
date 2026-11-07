/**
 * Temporary Session Active Indicator — three responsive layout variants.
 *
 * @since 2.0.0
 */
package org.example.project.ui.screens.auth.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.keys.TemporarySessionKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.auth.presentation.state.TemporarySessionUiState
import org.example.project.ui.components.PosAutoSizeText
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.theme.PosSpacing
import org.example.project.ui.theme.PosTheme

@Composable
fun TemporarySessionIndicator(
    modifier: Modifier = Modifier,
    state: TemporarySessionUiState,
    onExtendClick: () -> Unit,
    onEndClick: () -> Unit,
    language: Language,
    compact: Boolean = false,
) {
    AnimatedVisibility(
        visible = state.hasActiveSession,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        if (compact) {
            CompactSessionIndicator(
                formattedTime = state.formattedTimeRemaining,
                isExpiringSoon = state.isExpiringSoon,
                onEndClick = onEndClick,
                modifier = modifier,
            )
        } else {
            FullSessionIndicator(
                managerName = state.managerName ?: str(TemporarySessionKeys.unknownManager),
                formattedTime = state.formattedTimeRemaining,
                isExpiringSoon = state.isExpiringSoon,
                onExtendClick = onExtendClick,
                onEndClick = onEndClick,
                modifier = modifier,
            )
        }
    }
}

@Composable
fun PhoneSessionBanner(
    state: TemporarySessionUiState,
    onExtendClick: () -> Unit,
    onEndClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = state.hasActiveSession,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        val colors = PosTheme.colors
        val statusColor = if (state.isExpiringSoon) colors.posError else colors.posAvailable
        val containerColor = statusColor.copy(alpha = 0.12f)

        Surface(
            color = containerColor,
            modifier = modifier.fillMaxWidth(),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PosSpacing.xl, vertical = PosSpacing.md),
                horizontalArrangement = Arrangement.spacedBy(PosSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor),
                )
                PosAutoSizeText(
                    text = state.managerName ?: str(TemporarySessionKeys.unknownManager),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = state.formattedTimeRemaining,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                )
                PosIconButton(
                    icon = Icons.Default.Refresh,
                    contentDescription = str(TemporarySessionKeys.extendButton),
                    onClick = onExtendClick,
                    tint = MaterialTheme.colorScheme.primary,
                    size = 28.dp,
                    iconSize = 18.dp,
                )
                PosIconButton(
                    icon = Icons.Default.Close,
                    contentDescription = str(TemporarySessionKeys.endSessionContentDescription),
                    onClick = onEndClick,
                    tint = colors.posError,
                    size = 28.dp,
                    iconSize = 18.dp,
                )
            }
        }
    }
}

// ── Private layout variants ─────────────────────────────────────────────────

/**
 * Compact pill indicator for NavigationRail (MEDIUM layout).
 */
@Composable
private fun CompactSessionIndicator(
    formattedTime: String,
    isExpiringSoon: Boolean,
    onEndClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PosTheme.colors
    val statusColor = if (isExpiringSoon) colors.posError else colors.posAvailable
    val containerColor = statusColor.copy(alpha = 0.12f)

    Surface(
        color = containerColor,
        shape = MaterialTheme.shapes.small,
        modifier = modifier.padding(horizontal = PosSpacing.md, vertical = PosSpacing.sm),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(PosSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = PosSpacing.lg, vertical = PosSpacing.sm),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor),
            )
            Text(
                text = formattedTime,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = statusColor,
            )
            PosIconButton(
                icon = Icons.Default.Close,
                contentDescription = str(TemporarySessionKeys.endSessionContentDescription),
                onClick = onEndClick,
                tint = statusColor,
                size = 20.dp,
                iconSize = 14.dp,
            )
        }
    }
}

/**
 * Full session card for NavigationSidebar (EXPANDED layout).
 */
@Composable
private fun FullSessionIndicator(
    managerName: String,
    formattedTime: String,
    isExpiringSoon: Boolean,
    onExtendClick: () -> Unit,
    onEndClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = PosTheme.colors
    val statusColor = if (isExpiringSoon) colors.posError else colors.posAvailable
    val timerContainerColor = statusColor.copy(alpha = 0.12f)

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        modifier = modifier.padding(horizontal = PosSpacing.xl, vertical = PosSpacing.md),
    ) {
        Column(
            modifier = Modifier.padding(PosSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(PosSpacing.lg),
        ) {
            // Title row with status dot
            Row(
                horizontalArrangement = Arrangement.spacedBy(PosSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor),
                )
                Text(
                    text = str(TemporarySessionKeys.managerSessionActiveTitle),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            // Manager info row
            Row(
                horizontalArrangement = Arrangement.spacedBy(PosSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                PosAutoSizeText(
                    text = "${str(TemporarySessionKeys.managerLabel)} $managerName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Timer surface
            Surface(
                color = timerContainerColor,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(PosSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = PosSpacing.lg, vertical = PosSpacing.md),
                ) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = str(TemporarySessionKeys.expiresInLabel),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = formattedTime,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = statusColor,
                    )
                }
            }

            // Action buttons

            PosButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onExtendClick,
                text = str(TemporarySessionKeys.extendButton),
            )

            PosSecondaryButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onEndClick,
                text = str(TemporarySessionKeys.endSessionButton),
            )
        }
    }
}
