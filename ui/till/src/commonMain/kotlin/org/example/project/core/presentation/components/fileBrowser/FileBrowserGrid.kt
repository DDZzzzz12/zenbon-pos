package org.example.project.core.presentation.components.fileBrowser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.example.project.core.domain.fileBrowser.models.FileEntry
import org.example.project.core.domain.fileBrowser.models.SelectedFile
import org.example.project.core.presentation.strings.keys.FileBrowserKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardVariant

/**
 * Grid view of files and folders with thumbnails.
 */
@Composable
fun FileBrowserGrid(
    entries: List<FileEntry>,
    selectedFiles: List<SelectedFile>,
    thumbnailCache: Map<String, ByteArray>,
    isMultiSelect: Boolean,
    onEntryClick: (FileEntry) -> Unit,
    onEntryDoubleClick: (FileEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 100.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(entries, key = { it.path }) { entry ->
            FileBrowserGridItem(
                entry = entry,
                isSelected = selectedFiles.any { it.path == entry.path },
                thumbnail = thumbnailCache[entry.path],
                onClick = { onEntryClick(entry) },
                onDoubleClick = { onEntryDoubleClick(entry) },
            )
        }
    }
}

@Composable
private fun FileBrowserGridItem(
    entry: FileEntry,
    isSelected: Boolean,
    thumbnail: ByteArray?,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
) {
    PosCard(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        onClick = onClick,
        selected = isSelected,
        variant = PosCardVariant.Plain,
    ) {
        body {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // Thumbnail or icon
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        entry.isDirectory -> {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
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
                        entry.mimeType == "application/pdf" -> {
                            Icon(
                                Icons.Default.Description,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                        else -> {
                            Icon(
                                Icons.Default.Image,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // Selection indicator
                    if (isSelected && !entry.isDirectory) {
                        Box(
                            modifier =
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.shapes.small,
                                    ),
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = str(FileBrowserKeys.selected),
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                }

                // File name
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
