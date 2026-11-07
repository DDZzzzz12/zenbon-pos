package org.example.project.core.presentation.components.fileBrowser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.example.project.core.domain.fileBrowser.models.SelectedFile
import org.example.project.core.presentation.strings.keys.FileBrowserKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.formatFileSize
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant
import org.example.project.ui.components.PosIconButton

/**
 * Preview pane showing the selected file with thumbnail and metadata.
 */
@Composable
fun FileBrowserPreview(
    file: SelectedFile,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PosCard(
        modifier = modifier,
        variant = PosCardVariant.Plain,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        body {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.Top,
            ) {
                // Thumbnail or icon
                Box(
                    modifier =
                        Modifier
                            .size(80.dp)
                            .background(
                                MaterialTheme.colorScheme.surface,
                                MaterialTheme.shapes.small,
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    if (file.mimeType?.startsWith("image/") == true) {
                        AsyncImage(
                            model = file.bytes,
                            contentDescription = file.name,
                            modifier =
                                Modifier
                                    .aspectRatio(1f)
                                    .padding(4.dp),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Icon(
                            Icons.Default.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // File info
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = formatFileSize(file.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        file.mimeType?.let { mimeType ->
                            Text(
                                text = mimeType,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Clear button
                PosIconButton(
                    icon = Icons.Default.Close,
                    contentDescription = str(FileBrowserKeys.clearSelection),
                    onClick = onClearSelection,
                    size = 32.dp,
                    iconSize = 20.dp,
                )
            }
        }
    }
}
