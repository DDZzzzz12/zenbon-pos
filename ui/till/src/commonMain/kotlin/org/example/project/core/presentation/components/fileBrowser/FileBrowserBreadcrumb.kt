package org.example.project.core.presentation.components.fileBrowser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.ui.components.PosAutoSizeText

/**
 * Breadcrumb navigation bar showing the current path.
 * Clickable segments allow quick navigation to parent directories.
 */
@Composable
fun FileBrowserBreadcrumb(
    currentPath: String,
    onPathSegmentClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pathSegments = currentPath.split("/").filter { it.isNotBlank() }
    val scrollState = rememberScrollState()

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        pathSegments.forEachIndexed { index, segment ->
            if (index > 0) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val path =
                buildString {
                    append("/")
                    append(pathSegments.take(index + 1).joinToString("/"))
                }

            PosAutoSizeText(
                text = if (index == 0) "/" else segment,
                style = MaterialTheme.typography.bodyMedium,
                color =
                    if (index == pathSegments.lastIndex) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                modifier =
                    Modifier
                        .clickable { onPathSegmentClick(path) }
                        .padding(horizontal = 4.dp),
            )
        }
    }
}
