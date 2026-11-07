package org.example.project.ui.screens.settings.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.example.project.core.presentation.strings.keys.ShopInfoKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosTextButton

/**
 * Shop logo preview with upload/remove controls.
 *
 * Host-specific concerns (resolving the image URL against the host's server base URL, and the
 * file picker) are injected instead of referenced directly, so the section stays platform-neutral.
 * Its only host today is adminApp, which resolves [logoUrl] through `resolveAdminImageUrl` (its
 * base URL is the page origin) and picks a file via its own `pickImage()`; composeApp's settings
 * keep only device-bound tabs and do not show the shop logo.
 *
 * @param logoUrl Server-relative logo path, or blank if no logo is set.
 * @param isUploading Whether an upload is currently in flight; shows a progress row instead of the action buttons.
 * @param isDisabled Whether the action buttons should be disabled (e.g. read-only permissions or a disabled form).
 * @param resolveImageUrl Resolves [logoUrl] to a full, loadable image URL, or null if it can't be resolved.
 * @param onPickImage Opens the host's image picker.
 * @param onRemoveLogo Called when the user confirms removing the current logo.
 */
@Composable
fun ShopLogoSection(
    logoUrl: String,
    isUploading: Boolean,
    isDisabled: Boolean,
    resolveImageUrl: (String) -> String?,
    onPickImage: () -> Unit,
    onRemoveLogo: () -> Unit,
) {
    val fullLogoUrl = if (logoUrl.isNotBlank()) resolveImageUrl(logoUrl) else null

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Logo preview
        Box(
            modifier =
                Modifier
                    .size(80.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        MaterialTheme.shapes.medium,
                    ),
            contentAlignment = Alignment.Center,
        ) {
            if (fullLogoUrl != null) {
                AsyncImage(
                    model = fullLogoUrl,
                    contentDescription = str(ShopInfoKeys.logoSection),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Store,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }

        // Action buttons
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (isUploading) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = str(ShopInfoKeys.uploadingLogo),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                PosSecondaryButton(
                    text = str(ShopInfoKeys.uploadLogo),
                    icon = Icons.Default.Upload,
                    onClick = onPickImage,
                    enabled = !isDisabled,
                )

                if (fullLogoUrl != null) {
                    PosTextButton(
                        text = str(ShopInfoKeys.removeLogo),
                        onClick = onRemoveLogo,
                        enabled = !isDisabled,
                        icon = Icons.Default.Delete,
                        colors =
                            ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                    )
                }
            }
        }
    }
}
