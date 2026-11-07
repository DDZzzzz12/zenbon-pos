package org.example.project.features.appupdate.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.core.domain.dto.update.UpdateDto
import org.example.project.core.presentation.strings.StringKey
import org.example.project.core.presentation.strings.keys.AppUpdateKeys
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.appupdate.domain.AppUpdateFailure
import org.example.project.features.appupdate.domain.AppUpdateState
import org.example.project.features.appupdate.presentation.viewmodels.AppUpdateViewModel
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosDialog
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosTextButton

private const val BYTES_PER_MB = 1_048_576L

/** Renders the self-update flow for whatever state [viewModel] is in. No logic of its own. */
@Composable
fun AppUpdateDialog(viewModel: AppUpdateViewModel) {
    val state by viewModel.state.collectAsState()
    val error by viewModel.error.collectAsState()

    PosErrorWatcher(error = error, title = str(AppUpdateKeys.availableTitle), onClear = viewModel::clearError)

    when (val current = state) {
        AppUpdateState.Idle, AppUpdateState.Checking -> Unit
        AppUpdateState.UpToDate -> UpToDateDialog(onClose = viewModel::dismiss)
        is AppUpdateState.Available -> AvailableDialog(current.info, viewModel)
        is AppUpdateState.Downloading -> DownloadingDialog(current)
        is AppUpdateState.ReadyToInstall -> PermissionDialog(viewModel)
        is AppUpdateState.Installing -> BusyDialog(str(AppUpdateKeys.installing))
        is AppUpdateState.Failed -> FailedDialog(current, viewModel)
    }
}

@Composable
private fun UpToDateDialog(onClose: () -> Unit) {
    PosDialog(
        onDismiss = onClose,
        title = str(AppUpdateKeys.checkButton),
        footer = { CloseButton(onClose) },
    ) {
        Text(str(AppUpdateKeys.upToDate), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun AvailableDialog(
    info: UpdateDto.AndroidClientDto,
    viewModel: AppUpdateViewModel,
) {
    PosDialog(
        onDismiss = viewModel::dismiss,
        title = str(AppUpdateKeys.availableTitle),
        footer = {
            PosTextButton(text = str(AppUpdateKeys.later), onClick = viewModel::dismiss, modifier = Modifier.weight(1f))
            PosButton(text = str(AppUpdateKeys.download), onClick = viewModel::startUpdate, modifier = Modifier.weight(1f))
        },
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                str(AppUpdateKeys.availableBody, info.versionName, info.sizeBytes / BYTES_PER_MB),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (info.releaseNotes.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(info.releaseNotes, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DownloadingDialog(state: AppUpdateState.Downloading) {
    val total = state.info.sizeBytes
    val percent = if (total > 0) (state.downloadedBytes * 100 / total).coerceIn(0, 100) else 0L
    PosDialog(
        onDismiss = {},
        title = str(AppUpdateKeys.availableTitle),
        dismissOnClickOutside = false,
        showCloseButton = false,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(str(AppUpdateKeys.downloading, percent), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            // raw Material 3 by design: no Pos* progress wrapper
            LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PermissionDialog(viewModel: AppUpdateViewModel) {
    PosDialog(
        onDismiss = viewModel::dismiss,
        title = str(AppUpdateKeys.permissionTitle),
        footer = {
            PosTextButton(text = str(AppUpdateKeys.later), onClick = viewModel::dismiss, modifier = Modifier.weight(1f))
            PosButton(text = str(AppUpdateKeys.continueInstall), onClick = viewModel::resumeInstall, modifier = Modifier.weight(1f))
        },
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(str(AppUpdateKeys.permissionBody), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            PosSecondaryButton(
                text = str(AppUpdateKeys.openSettings),
                onClick = viewModel::openPermissionSettings,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun BusyDialog(message: String) {
    PosDialog(
        onDismiss = {},
        title = str(AppUpdateKeys.availableTitle),
        dismissOnClickOutside = false,
        showCloseButton = false,
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun FailedDialog(
    state: AppUpdateState.Failed,
    viewModel: AppUpdateViewModel,
) {
    PosDialog(
        onDismiss = viewModel::dismiss,
        title = str(AppUpdateKeys.availableTitle),
        footer = {
            CloseButton(viewModel::dismiss)
            if (state.info != null) {
                PosButton(text = str(AppUpdateKeys.retry), onClick = viewModel::startUpdate, modifier = Modifier.weight(1f))
            }
        },
    ) {
        Text(str(failureText(state.failure)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun RowScope.CloseButton(onClose: () -> Unit) {
    PosTextButton(text = str(CommonKeys.close), onClick = onClose, modifier = Modifier.weight(1f))
}

private fun failureText(failure: AppUpdateFailure): StringKey =
    when (failure) {
        AppUpdateFailure.DOWNLOAD_FAILED -> AppUpdateKeys.failureDownload
        AppUpdateFailure.HASH_MISMATCH -> AppUpdateKeys.failureHash
        AppUpdateFailure.PERMISSION_MISSING -> AppUpdateKeys.permissionBody
        AppUpdateFailure.INSTALL_CANCELLED -> AppUpdateKeys.failureCancelled
        AppUpdateFailure.INSTALL_FAILED -> AppUpdateKeys.failureInstall
    }
