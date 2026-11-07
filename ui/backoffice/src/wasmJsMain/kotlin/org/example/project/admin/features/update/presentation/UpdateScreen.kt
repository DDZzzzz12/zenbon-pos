package org.example.project.admin.features.update.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.example.project.admin.core.util.formatAdminInstant
import org.example.project.core.domain.dto.update.UpdateDto
import org.example.project.core.domain.enums.update.UpdateBlockedReason
import org.example.project.core.presentation.strings.keys.UpdateKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.core.utils.isCompact
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosCardStatus
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosSegmentedButtonRow
import org.example.project.ui.components.PosTextField
import org.example.project.ui.guard.rememberDirtySource
import org.koin.compose.viewmodel.koinViewModel

/**
 * The software-update screen: what is running, what is available, and the three deliberate steps
 * between them.
 *
 * ### Everything that can be true gets its own sentence
 *
 * The status card is driven by [UpdateSituation], not by a boolean pair, because the conditions an
 * operator has to tell apart are not "fine" and "broken". A rejected token, a token this host
 * cannot decrypt, a host with no key at all, an unreachable GitHub and a preflight that named open
 * bills all need different actions — and the first of those must never render as "up to date".
 *
 * ### `UPDATE_MANAGE` gates every control, not just the dangerous ones
 *
 * The toggle, the token field and all three actions disable together below the capability, and the
 * card says why rather than leaving a row of dead buttons. The ViewModel refuses the same
 * operations independently (see its KDoc) so hiding a control is not the only thing standing
 * between a reader and an install, and the server enforces the capability again on every route.
 */
@Composable
fun UpdateScreen(viewModel: UpdateViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load() }

    PosErrorWatcher(
        error = state.error,
        title = str(UpdateKeys.screenTitle),
        onClear = viewModel::clearError,
        retryAction = viewModel::load,
    )

    if (state.isLoading && state.status == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val status = state.status ?: return

    Box(Modifier.fillMaxSize()) {
        // The phone top bar (AdminApp, window-size class) already names the screen; hide our own title on the same test, the subtitle stays.
        val phone = LocalWindowSizeClass.current.isCompact()
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                if (!phone) {
                    Text(str(UpdateKeys.screenTitle), style = MaterialTheme.typography.headlineSmall)
                }
                Text(
                    str(UpdateKeys.screenSubtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!state.canManage) {
                Text(
                    str(UpdateKeys.manageDenied),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            StatusCard(state, status, viewModel)
            SourceCard(state, status, viewModel)
            TokenCard(state, viewModel)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusCard(
    state: UpdateState,
    status: UpdateDto.StatusDto,
    viewModel: UpdateViewModel,
) {
    val situation = state.situation ?: UpdateSituation.NEVER_CHECKED
    val version = status.availableVersion.orEmpty()

    PosCard(status = situation.cardStatus()) {
        body {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(situationTitle(situation, version), style = MaterialTheme.typography.titleMedium)
                Text(
                    situationDetail(situation, state.activeBlocker, version),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                state.applyingVersion?.let { applying ->
                    Text(
                        str(UpdateKeys.applyStarted, applying),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                DownloadProgress(status.download, preparing = state.busy == UpdateAction.STAGE)

                HorizontalDivider()

                LabelledValue(str(UpdateKeys.currentVersion), status.currentVersion)
                if (status.availableVersion != null) {
                    LabelledValue(str(UpdateKeys.availableVersion), status.availableVersion!!)
                }
                Text(
                    str(UpdateKeys.lastChecked, status.lastCheckedAt?.let(::formatAdminInstant) ?: str(UpdateKeys.neverChecked)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                status.availableNotes?.takeIf { it.isNotBlank() }?.let { notes ->
                    HorizontalDivider()
                    Text(str(UpdateKeys.releaseNotes), style = MaterialTheme.typography.labelLarge)
                    Text(notes, style = MaterialTheme.typography.bodySmall)
                }

                HorizontalDivider()

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Switch(
                        checked = status.autoApply,
                        onCheckedChange = viewModel::setAutoApply,
                        enabled = state.canManage && state.busy == null,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(str(UpdateKeys.autoApplyLabel), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            str(if (status.autoApply) UpdateKeys.autoApplyOnHint else UpdateKeys.autoApplyOffHint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PosSecondaryButton(
                        text = str(UpdateKeys.actionCheck),
                        onClick = viewModel::checkNow,
                        enabled = state.canManage && state.busy == null,
                        isLoading = state.busy == UpdateAction.CHECK,
                    )
                    PosSecondaryButton(
                        text = str(UpdateKeys.actionStage),
                        onClick = viewModel::stageNow,
                        enabled = state.canManage && state.busy == null && status.availableVersion != null && !status.staged,
                        isLoading = state.busy == UpdateAction.STAGE,
                    )
                    PosButton(
                        text = str(UpdateKeys.actionInstall),
                        onClick = viewModel::applyNow,
                        enabled = state.canManage && state.busy == null && status.staged,
                        isLoading = state.busy == UpdateAction.APPLY,
                    )
                }
            }
        }
    }
}

/** The bar and the sentence under it while a release is being fetched; nothing otherwise. */
@Composable
private fun DownloadProgress(
    download: UpdateDto.DownloadProgressDto?,
    preparing: Boolean,
) {
    if (download == null && !preparing) return
    val percent = download?.let(::downloadPercent)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (percent != null) {
            LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            downloadLabel(download, percent),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun downloadLabel(
    download: UpdateDto.DownloadProgressDto?,
    percent: Int?,
): String {
    if (download == null) return str(UpdateKeys.downloadPreparing)
    val done = formatDownloadSize(download.bytesDone)
    val total = download.bytesTotal
    val android = download.phase == UpdateDto.DownloadPhase.ANDROID
    return if (total != null && percent != null) {
        str(if (android) UpdateKeys.downloadingAndroid else UpdateKeys.downloadingServer, done, formatDownloadSize(total), percent.toString())
    } else {
        str(if (android) UpdateKeys.downloadingAndroidNoTotal else UpdateKeys.downloadingServerNoTotal, done)
    }
}

@Composable
private fun SourceCard(
    state: UpdateState,
    status: UpdateDto.StatusDto,
    viewModel: UpdateViewModel,
) {
    val settings = state.settings
    PosCard {
        body {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(str(UpdateKeys.sourceTitle), style = MaterialTheme.typography.titleMedium)
                Text(str(UpdateKeys.channel), style = MaterialTheme.typography.labelLarge)
                PosSegmentedButtonRow(
                    options = listOf(CHANNEL_PUBLIC, CHANNEL_PRIVATE),
                    selectedOption = settings?.channel ?: status.channel,
                    onOptionSelected = viewModel::setChannel,
                    enabled = state.canManage && state.busy == null,
                    optionLabel = { str(if (it == CHANNEL_PRIVATE) UpdateKeys.channelPrivate else UpdateKeys.channelPublic) },
                )
                Text(
                    str(
                        if ((settings?.channel ?: status.channel) == CHANNEL_PRIVATE) {
                            UpdateKeys.channelPrivateHint
                        } else {
                            UpdateKeys.channelPublicHint
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                settings?.let { LabelledValue(str(UpdateKeys.repository), "${it.repoOwner}/${it.repoName}") }
            }
        }
    }
}

/**
 * The token is write-only: this card can set one and clear one, and never shows one. What it
 * renders instead is the masked preview the API returns, purely so an operator can tell *which*
 * credential is stored.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TokenCard(
    state: UpdateState,
    viewModel: UpdateViewModel,
) {
    var draft by remember { mutableStateOf("") }
    val settings = state.settings
    // A secret: the guard only learns "there is a draft", never its value.
    rememberDirtySource(dirty = draft.isNotBlank(), onDiscard = { draft = "" })

    PosCard {
        body {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(str(UpdateKeys.tokenTitle), style = MaterialTheme.typography.titleMedium)
                Text(
                    str(UpdateKeys.tokenHint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    when (settings?.tokenState) {
                        UpdateDto.TokenState.SET -> str(UpdateKeys.tokenStateSet, settings.tokenPreview.orEmpty())
                        UpdateDto.TokenState.UNREADABLE -> str(UpdateKeys.tokenStateUnreadable)
                        else -> str(UpdateKeys.tokenStateNone)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                settings?.tokenSetAt?.let {
                    Text(
                        str(UpdateKeys.tokenSetAt, formatAdminInstant(it)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PosTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = str(UpdateKeys.tokenLabel),
                    enabled = state.canManage && state.busy == null,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PosButton(
                        text = str(UpdateKeys.actionSaveToken),
                        onClick = {
                            viewModel.saveToken(draft)
                            draft = ""
                        },
                        enabled = state.canManage && state.busy == null && draft.isNotBlank(),
                        isLoading = state.busy == UpdateAction.SAVE_TOKEN,
                    )
                    PosSecondaryButton(
                        text = str(UpdateKeys.actionClearToken),
                        onClick = viewModel::clearToken,
                        enabled = state.canManage && state.busy == null && settings?.tokenState != UpdateDto.TokenState.NONE,
                        isLoading = state.busy == UpdateAction.CLEAR_TOKEN,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabelledValue(
    label: String,
    value: String,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("$label:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private const val CHANNEL_PUBLIC = "PUBLIC"
private const val CHANNEL_PRIVATE = "PRIVATE"

@Composable
private fun situationTitle(
    situation: UpdateSituation,
    version: String,
): String =
    when (situation) {
        UpdateSituation.NEVER_CHECKED -> str(UpdateKeys.stateNeverCheckedTitle)
        UpdateSituation.UP_TO_DATE -> str(UpdateKeys.stateUpToDateTitle)
        UpdateSituation.AVAILABLE -> str(UpdateKeys.stateAvailableTitle, version)
        UpdateSituation.STAGED -> str(UpdateKeys.stateStagedTitle, version)
        UpdateSituation.AUTH_FAILED -> str(UpdateKeys.stateAuthFailedTitle)
        UpdateSituation.TOKEN_UNREADABLE -> str(UpdateKeys.stateTokenUnreadableTitle)
        UpdateSituation.TOKEN_KEY_MISSING -> str(UpdateKeys.stateTokenKeyMissingTitle)
        UpdateSituation.UNREACHABLE -> str(UpdateKeys.stateUnreachableTitle)
        UpdateSituation.STAGING_FAILED -> str(UpdateKeys.stateStagingFailedTitle)
        UpdateSituation.ROLLED_BACK -> str(UpdateKeys.stateRolledBackTitle)
        UpdateSituation.BLOCKED -> str(UpdateKeys.stateBlockedTitle)
        UpdateSituation.AUTO_APPLY_BLOCKED -> str(UpdateKeys.stateAutoBlockedTitle)
    }

@Composable
private fun situationDetail(
    situation: UpdateSituation,
    blockedReason: UpdateBlockedReason?,
    version: String,
): String =
    when (situation) {
        UpdateSituation.NEVER_CHECKED -> str(UpdateKeys.stateNeverCheckedDetail)
        UpdateSituation.UP_TO_DATE -> str(UpdateKeys.stateUpToDateDetail)
        UpdateSituation.AVAILABLE -> str(UpdateKeys.stateAvailableDetail)
        UpdateSituation.STAGED -> str(UpdateKeys.stateStagedDetail)
        UpdateSituation.AUTH_FAILED -> str(UpdateKeys.stateAuthFailedDetail)
        UpdateSituation.TOKEN_UNREADABLE -> str(UpdateKeys.stateTokenUnreadableDetail)
        UpdateSituation.TOKEN_KEY_MISSING -> str(UpdateKeys.stateTokenKeyMissingDetail)
        UpdateSituation.UNREACHABLE -> str(UpdateKeys.stateUnreachableDetail)
        UpdateSituation.STAGING_FAILED -> str(UpdateKeys.stateStagingFailedDetail, version)
        UpdateSituation.ROLLED_BACK -> str(UpdateKeys.stateRolledBackDetail, version)
        UpdateSituation.BLOCKED -> str(blockedReasonKey(blockedReason))
        // The same per-reason sentence as a live refusal, wrapped in the one thing that differs:
        // this happened unattended, last night, and it will happen again tonight.
        UpdateSituation.AUTO_APPLY_BLOCKED -> str(UpdateKeys.stateAutoBlockedDetail, str(blockedReasonKey(blockedReason)))
    }

private fun blockedReasonKey(reason: UpdateBlockedReason?) =
    when (reason) {
        UpdateBlockedReason.OPEN_BUSINESS -> UpdateKeys.blockedOpenBusiness
        UpdateBlockedReason.OPEN_TABLE_SESSION -> UpdateKeys.blockedOpenTableSession
        UpdateBlockedReason.OPEN_TSE_WORK -> UpdateKeys.blockedOpenTseWork
        UpdateBlockedReason.JOB_RUNNING -> UpdateKeys.blockedJobRunning
        UpdateBlockedReason.INSUFFICIENT_DISK -> UpdateKeys.blockedInsufficientDisk
        null -> UpdateKeys.blockedUnknown
    }

/** The accent stripe: green only where there is genuinely nothing to do. */
private fun UpdateSituation.cardStatus(): PosCardStatus? =
    when (this) {
        UpdateSituation.UP_TO_DATE -> PosCardStatus.Completed
        UpdateSituation.AVAILABLE, UpdateSituation.STAGED, UpdateSituation.NEVER_CHECKED -> PosCardStatus.Pending
        UpdateSituation.BLOCKED, UpdateSituation.AUTO_APPLY_BLOCKED, UpdateSituation.UNREACHABLE -> PosCardStatus.Warning
        UpdateSituation.AUTH_FAILED,
        UpdateSituation.TOKEN_UNREADABLE,
        UpdateSituation.TOKEN_KEY_MISSING,
        UpdateSituation.STAGING_FAILED,
        UpdateSituation.ROLLED_BACK,
        -> PosCardStatus.Error
    }
