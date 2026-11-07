package org.example.project.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.strings.keys.ApiSettingsKeys
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.ServerUrlKeys
import org.example.project.core.presentation.strings.keys.SettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.ServerUrlPolicy
import org.example.project.features.settings.domain.enums.ThemeMode
import org.example.project.ui.screens.settings.components.ThemeSegmentedButtons

/**
 * Redesigned API Settings Dialog using PosDialog template.
 * Allows configuration of server URL and theme mode.
 *
 * Opened before sign-in from the pairing screen and from the offline launch screen. The dialog only
 * reports what changed; each caller decides whether a new address may be stored.
 */
@Composable
fun PosApiSettingsDialog(
    currentUrl: String,
    themeMode: ThemeMode?,
    onDismiss: () -> Unit,
    onSave: (url: String?, themeMode: ThemeMode?) -> Unit,
) {
    var apiUrl by remember { mutableStateOf(currentUrl) }
    var selectedTheme by remember { mutableStateOf(themeMode ?: ThemeMode.SYSTEM) }

    val trimmedUrl = apiUrl.trim()
    val urlChanged = trimmedUrl != currentUrl && trimmedUrl.isNotBlank()
    val urlError = if (urlChanged) ServerUrlKeys.errorFor(ServerUrlPolicy.evaluate(trimmedUrl)) else null
    val themeChanged = selectedTheme != (themeMode ?: ThemeMode.SYSTEM)
    val canSave = (urlChanged || themeChanged) && urlError == null

    PosDialog(
        onDismiss = onDismiss,
        title = str(SettingsKeys.title),
        titleIcon = Icons.Default.Settings,
        maxWidth = 400.dp,
        footer = {
            PosSecondaryButton(
                text = str(CommonKeys.cancel),
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            PosButton(
                text = str(CommonKeys.save),
                onClick = {
                    onSave(
                        if (urlChanged) trimmedUrl else null,
                        if (themeChanged) selectedTheme else null,
                    )
                },
                enabled = canSave,
                modifier = Modifier.weight(1f),
            )
        },
    ) {
        // Appearance section
        SectionHeader(icon = Icons.Default.Palette, title = str(SettingsKeys.appearanceSection))

        Text(
            text = str(ApiSettingsKeys.theme),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ThemeSegmentedButtons(
            selectedTheme = selectedTheme,
            onThemeSelected = { selectedTheme = it },
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // API Server section
        SectionHeader(icon = Icons.Default.Link, title = str(ApiSettingsKeys.apiServerSection))

        // Current URL display
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.small,
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = str(ApiSettingsKeys.currentUrl),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = currentUrl,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        PosTextField(
            value = apiUrl,
            onValueChange = { apiUrl = it },
            label = str(ApiSettingsKeys.apiBaseUrl),
            leadingIcon = Icons.Default.Link,
            imeAction = ImeAction.Done,
            isError = urlError != null,
            errorMessage = urlError?.let { str(it) },
        )
    }
}

@Composable
private fun SectionHeader(
    icon: ImageVector,
    title: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
