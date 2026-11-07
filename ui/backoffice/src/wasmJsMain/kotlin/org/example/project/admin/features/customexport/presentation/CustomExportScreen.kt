package org.example.project.admin.features.customexport.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.auth.canManageCustomExports
import org.example.project.admin.core.presentation.components.AdminPageTitle
import org.example.project.admin.core.presentation.components.AdminSplitPane
import org.example.project.admin.core.presentation.components.DeleteConfirmDialog
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.customexport.presentation.components.DefinitionEditor
import org.example.project.admin.features.customexport.presentation.components.DefinitionList
import org.example.project.core.auth.TokenStore
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.guard.LocalLeaveGuard
import org.example.project.ui.guard.rememberDirtySource
import org.example.project.ui.layout.PaneBreakpoints
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/** How wide the saved-definitions pane is. Matches the group list on the permissions screen. */
private val LIST_PANE_WIDTH = 300.dp

/**
 * Eigene Exporte — pick a subject, tick the columns, name them, download the CSV.
 *
 * Two panes, modelled on `PermissionGroupsScreen`: the saved definitions on the left, the one being
 * edited on the right. Below 600dp of content width the two panes become a drill (`AdminSplitPane`):
 * the list alone until a definition or draft is open, then the editor under a back button.
 *
 * ## Two tiers, and the screen shows the difference
 *
 * `CUSTOM_EXPORT_READ` is what got the operator here at all, and it is enough to open a saved
 * definition and run it. Building one is `CUSTOM_EXPORT_MANAGE`, which the server enforces on the
 * create and delete routes — so without it the new/save/delete controls are not rendered and the
 * editor's inputs are read-only. Editing a definition a reader cannot save would be a trap, since a
 * run always uses the *stored* definition, never what is on screen.
 *
 * ## The scroll containers
 *
 * Exactly two, and they are **siblings**, never nested: the left pane is a `LazyColumn`, the right
 * pane a `verticalScroll`. Everything inside the editor — both halves of the field picker included —
 * sizes to its content. A lazy list or a second `verticalScroll` inside the right pane would be
 * measured with an unbounded height and take the wasm canvas down at runtime, which the compiler
 * cannot warn about. On a phone only one of them is composed at a time.
 */
@Composable
fun CustomExportScreen(viewModel: CustomExportViewModel = koinViewModel()) {
    // Not the view model's `init`: adminApp shares one ViewModelStoreOwner, so a load there would
    // render the previous session's definitions after the next login.
    LaunchedEffect(Unit) { viewModel.load() }

    val state = viewModel.state
    val canManage = koinInject<TokenStore>().canManageCustomExports
    var pendingDelete by remember { mutableStateOf<Long?>(null) }
    val leaveGuard = LocalLeaveGuard.current

    // The draft lives in the view model; the screen outlives the pane swap at the phone width. The
    // phone Back button is asked centrally by AdminSplitPane (its onCloseDetail below).
    rememberDirtySource(dirty = state.isDirty, onDiscard = viewModel::closeDraft)

    PosErrorWatcher(
        error = state.error,
        title = str(AdminStringKeys.CustomExportTitle),
        onClear = viewModel::clearError,
        retryAction = viewModel::load,
    )

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AdminPageTitle(str(AdminStringKeys.CustomExportTitle))
            if (canManage) {
                PosButton(
                    text = str(AdminStringKeys.CustomExportNewDefinition),
                    onClick = { leaveGuard.requestLeave { viewModel.startDraft() } },
                    enabled = !state.isSaving && !state.isRunning,
                )
            }
        }

        // A short file is the failure the server's row cap exists to make visible. It has to be said
        // on the screen the operator is standing on, not only in the log.
        state.truncatedRowCount?.let { rowCount ->
            Text(
                text = str(AdminStringKeys.CustomExportTruncated, rowCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }

        val busy = state.isSaving || state.isRunning
        // At >= PHONE_MAX this is the old rail (300dp) beside the editor; below it, the list alone until a
        // definition or draft is open, then the editor under a back button. Never both, so the two
        // scroll containers stay siblings and never nest (see the KDoc above).
        AdminSplitPane(
            modifier = Modifier.fillMaxSize(),
            minWidthForSplit = PaneBreakpoints.PHONE_MAX,
            railWidth = LIST_PANE_WIDTH,
            detailOpen = state.draft != null,
            // Same guard as the editor's Cancel button: no closing while a save or run is in flight.
            onCloseDetail = { if (!busy) viewModel.closeDraft() },
            list = {
                DefinitionList(
                    definitions = state.definitions,
                    subjects = state.subjects,
                    selectedId = state.selectedId,
                    onSelect = { id -> leaveGuard.requestLeave { viewModel.editDefinition(id) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            detail = {
                Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    val draft = state.draft
                    if (draft == null) {
                        Text(
                            text = str(AdminStringKeys.CustomExportSelectPrompt),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        val savedDefinition = state.definitions.firstOrNull { it.id == draft.id }
                        DefinitionEditor(
                            draft = draft,
                            subject = state.draftSubject,
                            subjects = state.subjects,
                            isBroken = savedDefinition?.isBroken == true,
                            isDirty = state.isDraftDirty,
                            canManage = canManage,
                            isSaving = state.isSaving,
                            isRunning = state.isRunning,
                            onNameChange = viewModel::updateName,
                            onSubjectChange = viewModel::selectSubject,
                            onToggleField = viewModel::toggleField,
                            onHeadingChange = viewModel::updateHeading,
                            onMoveUp = viewModel::moveFieldUp,
                            onMoveDown = viewModel::moveFieldDown,
                            onDateRangeChange = viewModel::setDateRange,
                            onToggleFilter = viewModel::toggleFilter,
                            onSave = viewModel::save,
                            onRun = { draft.id?.let { viewModel.run(it) } },
                            onDelete = { pendingDelete = draft.id },
                            onClose = { leaveGuard.requestLeave(viewModel::closeDraft) },
                        )
                    }
                }
            },
        )
    }

    pendingDelete?.let { id ->
        DeleteConfirmDialog(
            entityType = str(AdminStringKeys.DeleteEntityExport),
            entityName =
                state.definitions
                    .firstOrNull { it.id == id }
                    ?.name
                    .orEmpty(),
            consequence = str(AdminStringKeys.CustomExportDeleteConfirm),
            onConfirm = {
                pendingDelete = null
                viewModel.delete(id)
            },
            onDismiss = { pendingDelete = null },
        )
    }
}
