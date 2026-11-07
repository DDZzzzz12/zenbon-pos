package org.example.project.admin.features.debtors.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.components.AdminListSkeleton
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.auth.LocalAccessPolicy
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosErrorWatcher
import org.koin.compose.viewmodel.koinViewModel

/** Roughly the height of the form, so the pane does not collapse and re-expand as it loads. */
private const val SKELETON_ROWS = 6

/**
 * The debtor form, edited in place beside the list.
 *
 * Renders [DebtorDetailSections] — the same four sections [DebtorDetailScreen] shows — without a
 * scrolling container of its own, because `AdminListScaffold` already scrolls and a `verticalScroll`
 * nested in another measures to infinite height and crashes on wasm. The form is shared rather than
 * duplicated: two copies of a master-data form drift, and the one that drifts is the one nobody is
 * looking at.
 *
 * No back arrow — there is nothing to go back *to*, the list is right there. And no
 * `PosLoadingOverlay`, which would grey out the whole page including the list for a load that
 * concerns one pane.
 *
 * @param debtorId the selected debtor. Changing it reloads the form and discards unsaved edits —
 *   the same thing navigating away from the full screen does.
 * @param onSaved lets the list re-read itself, and reports **which** save it is reporting. A rename
 *   or a deactivation changes the very row the operator clicked, and a pane that saved while the
 *   rail still showed the old name would be showing two answers to one question.
 *
 *   The revision is passed rather than a bare callback because this composable **remounts**: the
 *   list reload it triggers makes the scaffold swap the content for a skeleton, which takes the pane
 *   out of composition and starts its effects again from scratch on the way back. A signal that only
 *   says "a save happened" therefore re-fires forever. The caller compares the number against the
 *   last one it acted on, in state that outlives the remount.
 */
@Composable
fun DebtorEditPane(
    debtorId: Long,
    onSaved: (savedRevision: Int) -> Unit,
) {
    val viewModel: DebtorDetailViewModel = koinViewModel()
    val state = viewModel.state
    val mayWrite = DebtorsAccess.of(LocalAccessPolicy.current).mayWrite
    // The shared ViewModel still holds the previously selected debtor until load() runs after the first frame.
    val showingThisDebtor = state.id == debtorId

    LaunchedEffect(debtorId) { viewModel.load(debtorId, keepDraft = true) }

    /*
     * The list is told **after** the save lands, not when the button is pressed: `save()` dispatches
     * into `viewModelScope` and returns immediately, so reloading on the click would re-read rows the
     * server has not written yet and put the old name straight back on screen.
     *
     * Keyed on the ViewModel's own counter rather than inferred from `isSaving` falling back to
     * false. The inference version was written first and silently never fired — the list kept the
     * pre-edit name after a successful PUT — which is exactly the kind of miss an explicit signal
     * cannot have.
     */
    LaunchedEffect(state.savedRevision) {
        if (state.savedRevision > 0) onSaved(state.savedRevision)
    }

    // A delete has to reach the list too, or the rail keeps offering a debtor the server no longer
    // has and clicking it 404s. A deleted debtor cannot be re-deleted, so this needs no revision.
    LaunchedEffect(state.isDeleted) { if (state.isDeleted) onSaved(state.savedRevision + 1) }

    PosErrorWatcher(
        error = state.error,
        title = str(AdminStringKeys.DebtorDetailTitle),
        onClear = viewModel::clearError,
        retryAction = { viewModel.load(debtorId) },
    )

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = str(AdminStringKeys.DebtorDetailTitle),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.weight(1f))
            if (mayWrite) {
                PosButton(
                    text = str(CommonKeys.save),
                    onClick = viewModel::save,
                    enabled = showingThisDebtor && state.canSave,
                    isLoading = state.isSaving,
                )
            }
        }

        if (state.isLoading || (!showingThisDebtor && state.error == null)) {
            AdminListSkeleton(rows = SKELETON_ROWS)
        } else {
            DebtorDetailSections(state, viewModel)
        }
    }
}
