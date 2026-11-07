package org.example.project.admin.features.permissions.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.components.AdminSplitPane
import org.example.project.admin.core.presentation.components.DeleteConfirmDialog
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.permissions.presentation.components.CreateGroupDialog
import org.example.project.admin.features.permissions.presentation.components.GroupEditorPanel
import org.example.project.core.domain.dto.employee.PermissionGroupDto
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.core.utils.isCompact
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.guard.LocalLeaveGuard
import org.example.project.ui.guard.rememberDirtySource
import org.example.project.ui.layout.PaneBreakpoints
import org.example.project.ui.theme.PosSpacing
import org.koin.compose.viewmodel.koinViewModel

/**
 * Berechtigungen — the permission-group editor.
 *
 * Two panes: the groups on the left, the selected group's editor on the right (from 600dp of content;
 * below that a drill: the list, then the editor under a back button). The two are sibling scroll
 * containers — list = LazyColumn, editor = verticalScroll — and must stay siblings: nesting one scroll
 * in the other crashes the measure pass on wasm. The destination is gated on `PERMISSION_ADMIN`, which
 * is reserved and so held only by the admin group; the server enforces the same capability on every
 * one of these calls.
 */
@Composable
fun PermissionGroupsScreen(viewModel: PermissionGroupsViewModel = koinViewModel()) {
    LaunchedEffect(Unit) { viewModel.refresh() }

    // Screen-level so the source outlives the pane swap when the window crosses 600dp.
    val dirty = (viewModel.state as? PermissionGroupsState.Data)?.isDirty == true
    rememberDirtySource(dirty = dirty, onDiscard = viewModel::discardDraft)

    PosErrorWatcher(
        error = (viewModel.state as? PermissionGroupsState.Error)?.error,
        title = str(AdminStringKeys.NavPermissions),
        onClear = viewModel::clearError,
        retryAction = viewModel::refresh,
    )

    Box(Modifier.fillMaxSize()) {
        when (val s = viewModel.state) {
            is PermissionGroupsState.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            is PermissionGroupsState.Error -> Box(Modifier.fillMaxSize()) // surfaced via the overlay
            is PermissionGroupsState.Data -> PermissionGroupsContent(s, viewModel)
        }
    }

    viewModel.createDraft?.let { draft ->
        val data = viewModel.state as? PermissionGroupsState.Data
        CreateGroupDialog(
            draft = draft,
            byArea = data?.catalogByArea.orEmpty(),
            onEdit = viewModel::editCreate,
            onDismiss = viewModel::closeCreate,
            onSubmit = viewModel::submitCreate,
        )
    }
}

@Composable
private fun PermissionGroupsContent(
    data: PermissionGroupsState.Data,
    viewModel: PermissionGroupsViewModel,
) {
    val guard = LocalLeaveGuard.current
    // The page's Delete only asks; the filled red confirm lives in this dialog (SYS-20).
    var confirmDelete by remember { mutableStateOf<PermissionGroupDto.PermissionGroupResponseDto?>(null) }
    confirmDelete?.let { target ->
        DeleteConfirmDialog(
            entityType = str(AdminStringKeys.DeleteEntityPermissionGroup),
            entityName = target.name,
            onConfirm = {
                confirmDelete = null
                viewModel.deleteSelected()
            },
            onDismiss = { confirmDelete = null },
        )
    }
    Box(Modifier.fillMaxSize()) {
        // The phone top bar (AdminApp, window-size class) already names the screen; hide our own title on the same test.
        val phone = LocalWindowSizeClass.current.isCompact()
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    if (!phone) {
                        Text(str(AdminStringKeys.NavPermissions), style = MaterialTheme.typography.headlineSmall)
                    }
                    Text(
                        str(AdminStringKeys.PermissionGroupsSubtitle, data.groups.size.toString()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PosButton(text = str(AdminStringKeys.PermissionGroupCreate), onClick = viewModel::openCreate)
            }

            AdminSplitPane(
                modifier = Modifier.fillMaxSize().padding(top = 12.dp),
                minWidthForSplit = PaneBreakpoints.PHONE_MAX,
                railWidth = 280.dp,
                detailOpen = viewModel.detailOpen,
                onCloseDetail = viewModel::closeDetail,
                list = {
                    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(data.groups, key = { it.code }) { group ->
                            GroupRow(
                                group = group,
                                selected = group.code == data.selectedCode,
                                // select() drops the draft even for the open group, so every row asks.
                                onClick = { guard.requestLeave { viewModel.select(group.code) } },
                            )
                        }
                    }
                },
                detail = {
                    val selected = data.selected
                    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        if (selected == null) {
                            Text(
                                str(AdminStringKeys.PermissionGroupNoneSelected),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            GroupEditorPanel(
                                data = data,
                                group = selected,
                                onEditName = viewModel::editName,
                                onEditDescription = viewModel::editDescription,
                                onToggleCapability = viewModel::toggleCapability,
                                onSave = viewModel::save,
                                onDiscard = viewModel::discardDraft,
                                onDelete = { confirmDelete = selected },
                            )
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun GroupRow(
    group: PermissionGroupDto.PermissionGroupResponseDto,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
            ).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = PosSpacing.md),
    ) {
        Text(group.code, style = MaterialTheme.typography.titleSmall)
        // A seeded group's name is stored in English and stays editable through the API, so it is
        // translated by code; a group an operator created keeps the name it was given. The editor's
        // name field beside this list is deliberately NOT translated -- it writes back to the API.
        val groupLabel = AdminStringKeys.permissionGroupName(group.code)?.let { str(it) } ?: group.name
        Text(
            text = groupLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text =
                str(AdminStringKeys.PermissionGroupRowSummary, group.capabilities.size.toString(), group.memberCount.toString()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
