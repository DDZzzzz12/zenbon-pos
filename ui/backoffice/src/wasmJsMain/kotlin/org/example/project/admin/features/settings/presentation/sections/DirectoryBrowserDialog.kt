package org.example.project.admin.features.settings.presentation.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.SettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosMiddleEllipsisText
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.components.PosTextField

/**
 * Lists the sub-folders of a directory **on the server** so a storage root can be picked without
 * typing a path. The browser is a server-side listing, so the lack of a native directory picker in a
 * browser is irrelevant here. It is the single directory picker of the admin app (document storage,
 * DATEV export, backup).
 *
 * When the current folder is writable a "New folder" row lets the operator name a sub-folder; the
 * combined path is handed to [onSelect] and the server creates it lazily on first use.
 *
 * Purely presentational — every action is a callback, and the ADMIN gate that decides whether this
 * dialog can be opened at all lives in the caller.
 */
@Composable
fun DirectoryBrowserDialog(
    browser: DirectoryBrowserState,
    onNavigate: (String) -> Unit,
    onParent: () -> Unit,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    allowNewFolder: Boolean = true,
) {
    PosDialog(
        onDismiss = onDismiss,
        title = str(AdminStringKeys.SettingsDocStorageBrowserTitle),
        kind = PosDialogKind.Form,
        primary =
            PosDialogAction(
                text = str(AdminStringKeys.SettingsDocStorageBrowserSelect),
                onClick = { onSelect(browser.currentPath) },
                enabled = browser.canWrite && browser.currentPath.isNotEmpty(),
            ),
    ) {
        // No verticalScroll: PosDialog scrolls its own content.
        Column(modifier = Modifier.fillMaxWidth()) {
            // Measured middle ellipsis: Compose's own middle-ellipsis overflow renders as an END ellipsis on wasm,
            // which cut off the folder the operator is in (UI review follow-up 5).
            PosMiddleEllipsisText(
                text = browser.currentPath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            )
            if (!browser.canWrite) {
                Text(
                    text = str(AdminStringKeys.SettingsDocStorageBrowserNotWritable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (browser.parentPath != null) {
                DirectoryRow(
                    icon = Icons.Default.KeyboardArrowUp,
                    label = str(AdminStringKeys.SettingsDocStorageBrowserParent),
                    showChevron = false,
                    onClick = onParent,
                )
            }
            if (browser.entries.isEmpty() && !browser.isLoading) {
                Text(
                    text = str(AdminStringKeys.SettingsDocStorageBrowserEmpty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            browser.entries.forEach { entry ->
                DirectoryRow(
                    icon = if (entry.isWritable) Icons.Default.Folder else Icons.Default.FolderOpen,
                    label = entry.name,
                    showChevron = true,
                    dimmed = !entry.isWritable,
                    onClick = { onNavigate(entry.path) },
                )
            }
            if (browser.canWrite && allowNewFolder) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                NewFolderRow(currentPath = browser.currentPath, onCreate = onSelect)
            }
        }
    }
}

@Composable
private fun DirectoryRow(
    icon: ImageVector,
    label: String,
    showChevron: Boolean,
    onClick: () -> Unit,
    dimmed: Boolean = false,
) {
    val tint =
        if (dimmed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) else MaterialTheme.colorScheme.primary
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (showChevron) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NewFolderRow(
    currentPath: String,
    onCreate: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    if (!expanded) {
        PosTextButton(
            text = str(SettingsKeys.newFolder),
            icon = Icons.Default.CreateNewFolder,
            onClick = { expanded = true },
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 8.dp)) {
        PosTextField(
            value = name,
            onValueChange = { name = it },
            label = str(SettingsKeys.newFolder),
            placeholder = str(SettingsKeys.folderNameHint),
            modifier = Modifier.fillMaxWidth(),
            imeAction = ImeAction.Done,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            PosTextButton(
                text = str(CommonKeys.cancel),
                onClick = {
                    expanded = false
                    name = ""
                },
            )
            PosTextButton(
                text = str(SettingsKeys.create),
                enabled = name.isNotBlank(),
                onClick = {
                    val separator = if (currentPath.endsWith("/")) "" else "/"
                    onCreate(currentPath + separator + name.trim())
                },
            )
        }
    }
}
