package org.example.project.ui.screens.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.keys.TseStatusKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.notifications.presentation.state.TseConnectionState
import org.example.project.ui.theme.PosSpacing
import org.example.project.ui.theme.PosTheme

/**
 * Persistent banner shown while the TSE is configured NORMAL but offline.
 * Bookings keep flowing and are queued for Nachsignieren — this is informational,
 * not a blocking dialog. Mirrors the raw-Material3 PhoneSessionBanner convention.
 */
@Composable
fun TseOfflineBanner(
    tseConnectionState: TseConnectionState,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = tseConnectionState is TseConnectionState.NormalOffline,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        val accent = PosTheme.colors.posOccupied
        // raw Material 3 by design: mirrors the existing PhoneSessionBanner banner convention
        Surface(
            color = accent.copy(alpha = 0.12f),
            modifier = modifier.fillMaxWidth(),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = PosSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
                // One line: the banner sits between the screen and the status bar, and every dp it
                // takes is one the screen above loses - on a 1280x800 till that was 75 dp for a
                // single sentence. The message already opens with the title, so the separate title
                // line was pure repetition. Wraps to a second line only where the width forces it.
                Text(
                    text = str(TseStatusKeys.offlineBannerMessage),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier.size(8.dp).clip(CircleShape).background(accent),
                )
            }
        }
    }
}
