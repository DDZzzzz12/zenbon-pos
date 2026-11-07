/**
 * Request Manager Session Button — compact, full, and phone layout variants.
 *
 * @since 2.0.0
 */
package org.example.project.ui.screens.auth.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.presentation.strings.keys.TemporarySessionKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.theme.PosSpacing

@Composable
fun RequestSessionButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    language: Language,
    compact: Boolean = false,
) {
    if (compact) {
        CompactRequestButton(onClick = onClick, modifier = modifier)
    } else {
        FullRequestButton(onClick = onClick, modifier = modifier)
    }
}

@Composable
fun PhoneRequestBanner(
    onClick: () -> Unit,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Surface(
            onClick = onClick,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = modifier.fillMaxWidth(),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PosSpacing.xl, vertical = PosSpacing.lg),
                horizontalArrangement = Arrangement.spacedBy(PosSpacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = str(TemporarySessionKeys.requestManagerAccess),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun CompactRequestButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.small,
        tonalElevation = 1.dp,
        modifier = modifier.padding(horizontal = PosSpacing.md, vertical = PosSpacing.sm),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PosSpacing.sm),
            modifier = Modifier.padding(PosSpacing.lg),
        ) {
            // A bare lock in the rail did not say what it opens (emu22 NOTE-ff): the label is
            // visible and the icon carries it as its content description too, so a screen reader
            // and an `adb uiautomator dump` name the button. The active state replaces this button
            // with TemporarySessionIndicator, whose action reads „Sitzung beenden“.
            val label = str(TemporarySessionKeys.managerSignIn)
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(30.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun FullRequestButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // raw Material 3 by design: exotic slot content (Icon + two-line Column) and custom secondaryContainer colors not expressible via PosSecondaryButton
    OutlinedButton(
        onClick = onClick,
        modifier =
            modifier
                .height(56.dp)
                .padding(horizontal = PosSpacing.xl),
        colors =
            ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(PosSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(PosSpacing.xs),
            ) {
                Text(
                    text = str(TemporarySessionKeys.requestManagerAccess),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = str(TemporarySessionKeys.temporarySessionDuration),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                )
            }
        }
    }
}
