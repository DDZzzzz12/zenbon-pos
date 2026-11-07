package org.example.project.core.presentation.components.fileBrowser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.example.project.core.domain.enums.setup.Language
import org.example.project.core.domain.fileBrowser.models.FileEntry
import org.example.project.core.domain.fileBrowser.models.SelectedFile
import org.example.project.core.presentation.strings.keys.FileBrowserKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalLanguageManager
import org.example.project.core.utils.formatFileSize
import org.example.project.core.utils.formatReadableDateTime
import org.example.project.ui.components.PosAutoSizeText
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant

/**
 * List view of files and folders with details.
 */
@Composable
fun FileBrowserList(
    entries: List<FileEntry>,
    selectedFiles: List<SelectedFile>,
    thumbnailCache: Map<String, ByteArray>,
    isMultiSelect: Boolean,
    onEntryClick: (FileEntry) -> Unit,
    onEntryDoubleClick: (FileEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(entries, key = { it.path }) { entry ->
            FileBrowserListItem(
                entry = entry,
                isSelected = selectedFiles.any { it.path == entry.path },
                thumbnail = thumbnailCache[entry.path],
                onClick = { onEntryClick(entry) },
                onDoubleClick = { onEntryDoubleClick(entry) },
            )
            if (entry != entries.last()) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
            }
        }
    }
}

@Composable
private fun FileBrowserListItem(
    entry: FileEntry,
    isSelected: Boolean,
    thumbnail: ByteArray?,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
) {
    // The operator's chosen language, not the device locale — see formatEntryDate.
    val language by LocalLanguageManager.current.currentLanguage

    PosCard(
        modifier = Modifier.fillMaxWidth(),
        variant = PosCardVariant.Plain,
        selected = isSelected,
        onClick = onClick,
    ) {
        body {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                // Thumbnail or icon
                Box(
                    modifier =
                        Modifier
                            .size(48.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                MaterialTheme.shapes.small,
                            ).then(
                                if (isSelected && !entry.isDirectory) {
                                    Modifier.border(
                                        width = 2.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = MaterialTheme.shapes.small,
                                    )
                                } else {
                                    Modifier
                                },
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        entry.isDirectory -> {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        entry.mimeType?.startsWith("image/") == true && thumbnail != null -> {
                            AsyncImage(
                                model = thumbnail,
                                contentDescription = entry.name,
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .clip(MaterialTheme.shapes.small),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        entry.mimeType == "application/pdf" && thumbnail != null -> {
                            AsyncImage(
                                model = thumbnail,
                                contentDescription = entry.name,
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .clip(MaterialTheme.shapes.small),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        else -> {
                            Icon(
                                Icons.Default.Description,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    }

                    // Selection indicator
                    if (isSelected && !entry.isDirectory) {
                        Box(
                            modifier =
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .size(16.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.shapes.extraSmall,
                                    ),
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = str(FileBrowserKeys.selected),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // File info
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    PosAutoSizeText(
                        text = entry.name,
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (!entry.isDirectory) {
                            Text(
                                text = formatFileSize(entry.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = formatEntryDate(entry.lastModified, language),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Directory indicator
                if (entry.isDirectory) {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } // body
    }
}

/**
 * The entry's last-modified date, in the app's language.
 *
 * Replaces a `SimpleDateFormat("MMM d, yyyy", Locale.getDefault())`, which was JVM-only and followed
 * the *device* locale rather than the language the operator chose in the app. Month names now come
 * from [formatReadableDateTime], the same source every other date in this client uses.
 */
private fun formatEntryDate(
    timestamp: Long,
    language: Language,
): String {
    if (timestamp <= 0) return ""
    return Instant
        .fromEpochMilliseconds(timestamp)
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .toString()
        .formatReadableDateTime(language, showTime = false)
}
