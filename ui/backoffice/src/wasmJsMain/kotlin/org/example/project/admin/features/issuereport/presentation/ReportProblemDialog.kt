package org.example.project.admin.features.issuereport.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.example.project.admin.core.util.dom.BlockDomOverlayWhileVisible
import org.example.project.core.domain.dto.issuereport.IssueReportDto
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.IssueReportKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosDialogAction
import org.example.project.ui.components.PosDialogKind
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosTextField
import org.example.project.ui.guard.rememberGuardedDismiss
import org.koin.compose.viewmodel.koinViewModel

/**
 * "Report a problem": a form, then the exact text the server would post, then the issue number.
 *
 * Opened from the user menu, which is global — so it can appear over the document scanner's camera
 * `<video>` and must hide that DOM overlay ([BlockDomOverlayWhileVisible]). `PosDialog` scrolls its own body;
 * the only nested `verticalScroll` is the preview text box, and it is safe only because it carries
 * a bounded height ([PREVIEW_MAX_HEIGHT]) *before* the scroll modifier — an unbounded nested scroll
 * is the infinite-height measure crash on wasm.
 *
 * The refusal note sits at the top of each stage, not below the (long) preview, so a failed send is
 * on screen the moment it happens; the `PosErrorWatcher` toast can be hidden behind the dialog.
 *
 * "Send report" stays inactive until the ViewModel arms it ([IssueReportState.sendArmed]): it sits
 * where "Preview" sat, and a double-click must not post a report nobody read.
 *
 * Clicking outside does not dismiss: losing a half-written report to a stray click is worse than
 * one extra press of the close button.
 *
 * @param screen the navigation destination the operator was on, sent as context; null when unknown.
 */
@Composable
fun ReportProblemDialog(
    screen: String?,
    onDismiss: () -> Unit,
    viewModel: IssueReportViewModel = koinViewModel(),
) {
    BlockDomOverlayWhileVisible()

    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.open(screen) }

    PosErrorWatcher(
        error = state.error,
        title = str(IssueReportKeys.errorTitle),
        onClear = viewModel::clearError,
    )

    val close = {
        viewModel.dismiss()
        onDismiss()
    }
    val created = state.created
    val preview = state.preview
    val guardedClose = rememberGuardedDismiss(dirty = state.isDirty, onClose = close)

    PosDialog(
        onDismiss = guardedClose,
        title = str(IssueReportKeys.dialogTitle),
        titleIcon = Icons.Default.BugReport,
        maxWidth = 640.dp,
        dismissOnClickOutside = false,
        kind = PosDialogKind.Form,
        primary =
            when {
                created != null -> PosDialogAction(text = str(CommonKeys.close), onClick = guardedClose)
                preview != null ->
                    PosDialogAction(
                        text = str(IssueReportKeys.sendButton),
                        onClick = viewModel::send,
                        enabled = state.canSubmitPreview,
                        isLoading = state.isBusy,
                    )
                else ->
                    PosDialogAction(
                        text = str(IssueReportKeys.previewButton),
                        onClick = viewModel::loadPreview,
                        enabled = state.canSend && state.canReport,
                        isLoading = state.isBusy,
                    )
            },
        secondary =
            if (preview != null && created == null) {
                PosDialogAction(text = str(CommonKeys.back), onClick = viewModel::backToForm, enabled = !state.isBusy)
            } else {
                null
            },
        showSecondary = created == null,
    ) {
        when {
            created != null -> CreatedContent(created)
            preview != null -> PreviewContent(state, preview)
            else -> FormContent(state, viewModel)
        }
    }
}

@Composable
private fun FormContent(
    state: IssueReportState,
    viewModel: IssueReportViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FailureNote(state)
        if (state.isPublicTarget) PublicWarning()
        PosTextField(
            value = state.title,
            onValueChange = viewModel::onTitle,
            label = str(IssueReportKeys.titleLabel),
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth(),
        )
        PosTextField(
            value = state.description,
            onValueChange = viewModel::onDescription,
            label = str(IssueReportKeys.descriptionLabel),
            enabled = !state.isBusy,
            singleLine = false,
            minLines = 5,
            modifier = Modifier.fillMaxWidth(),
        )
        if (!state.hasRequiredText) Hint(str(IssueReportKeys.requiredHint))
        IncludeLogsRow(state, viewModel)
        Hint(str(IssueReportKeys.contextNote))
    }
}

/**
 * The checkbox is disabled, with the reason beside it, for a public target — the server refuses a
 * log excerpt there too, and the ViewModel ignores the toggle.
 */
@Composable
private fun IncludeLogsRow(
    state: IssueReportState,
    viewModel: IssueReportViewModel,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // raw Material 3 by design: no Pos* checkbox component exists
        Checkbox(
            checked = state.includeLogs && !state.isPublicTarget,
            onCheckedChange = viewModel::onIncludeLogs,
            enabled = !state.isPublicTarget && !state.isBusy,
        )
        Column {
            Text(str(IssueReportKeys.includeLogsLabel), style = MaterialTheme.typography.bodyMedium)
            if (state.isPublicTarget) Hint(str(IssueReportKeys.includeLogsDisabledPublic))
        }
    }
}

@Composable
private fun PreviewContent(
    state: IssueReportState,
    preview: IssueReportDto.PreviewDto,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (preview.isPublic) PublicWarning()
        Text(str(IssueReportKeys.previewTitle), style = MaterialTheme.typography.titleSmall)
        FailureNote(state)
        Hint(str(IssueReportKeys.previewRepository, preview.repository))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            SelectionContainer {
                // heightIn BEFORE verticalScroll: the bound is what makes this nested scroll legal.
                Column(
                    Modifier
                        .heightIn(max = PREVIEW_MAX_HEIGHT)
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(preview.title, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
                    Text(preview.body, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun CreatedContent(created: IssueReportDto.CreatedDto) {
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            str(IssueReportKeys.sentToast, created.issueNumber),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        PosSecondaryButton(
            text = str(IssueReportKeys.openIssue),
            icon = Icons.AutoMirrored.Filled.OpenInNew,
            onClick = { uriHandler.openUri(created.issueUrl) },
        )
    }
}

@Composable
private fun PublicWarning() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            str(IssueReportKeys.publicWarning),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}

/** The last refusal, kept at the top of the stage until the operator edits or retries. */
@Composable
private fun FailureNote(state: IssueReportState) {
    val failure = state.failure ?: return
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            str(IssueReportKeys.notSentDraftKept, str(failure.messageKey, *failure.messageArgs.toTypedArray())),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The preview text box scrolls inside this height instead of pushing the refusal note off screen. */
private val PREVIEW_MAX_HEIGHT = 280.dp
