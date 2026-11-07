package org.example.project.features.terminal.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.example.project.core.auth.LocalSettingManager
import org.example.project.core.browser.BrowserKiosk
import org.example.project.core.browser.PendingPairingCode
import org.example.project.core.browser.isBrowserClient
import org.example.project.core.domain.dto.terminal.PairingQrPayload
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.network.isPinningSupported
import org.example.project.core.presentation.strings.keys.CommonKeys
import org.example.project.core.presentation.strings.keys.ServerUrlKeys
import org.example.project.core.presentation.strings.keys.TerminalPairingKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.ServerUrlPolicy
import org.example.project.core.utils.isQrScannerAvailable
import org.example.project.features.terminal.presentation.viewmodels.DevicePairingViewModel
import org.example.project.ui.components.PosApiSettingsDialog
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.components.PosTextField
import org.koin.compose.viewmodel.koinViewModel
import qrscanner.CameraLens
import qrscanner.QrScanner

/**
 * New-device pairing screen (Terminal Phase RB1).
 *
 * Manual 6-digit code entry is universal; QR scanning is Android-only (desktop shows
 * manual entry only). On success [onPaired] is invoked with the terminal details so the
 * host navigation can route based on the terminal role.
 */
@Composable
fun DevicePairingScreen(
    onPaired: (terminal: TerminalDto.TerminalResponseDto) -> Unit,
    viewModel: DevicePairingViewModel = koinViewModel(),
) {
    val settingManager = LocalSettingManager.current
    val apiBaseUrl by settingManager.url.collectAsState()
    val state by viewModel.state.collectAsState()

    // Reset the retained VM's code on every entry to this screen, but not on a configuration
    // change (rotation), which keeps the saveable flag and so keeps what the operator typed.
    var entered by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!entered) {
            viewModel.onScreenEntered(PendingPairingCode.take())
            entered = true
        }
    }

    PosErrorWatcher(
        error = state.error,
        title = str(CommonKeys.error),
        onClear = { viewModel.clearError() },
        // Pairing renders as the unauthenticated state, so the default suppression would
        // swallow exactly the failures this screen exists to show.
        suppressWhenSignedOut = false,
    )
    val scannerAvailable = isQrScannerAvailable()
    var showScanner by remember { mutableStateOf(false) }
    var showApiSettingsDialog by remember { mutableStateOf(false) }

    // Open the API settings dialog automatically when the ViewModel detects an unconfigured URL.
    LaunchedEffect(state.showUrlDialog) {
        if (state.showUrlDialog) showApiSettingsDialog = true
    }

    val errorMessage =
        state.error?.let { err ->
            str(err.messageKey, *err.messageArgs.toTypedArray())
        }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Devices,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp),
            )
            Text(
                text = str(TerminalPairingKeys.title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = str(TerminalPairingKeys.subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            // Server URL configuration
            PosCard {
                body {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = str(TerminalPairingKeys.currentServerUrl),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = apiBaseUrl ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        PosTextButton(
                            text = str(TerminalPairingKeys.changeServerUrl),
                            onClick = { showApiSettingsDialog = true },
                        )
                    }
                }
            }

            if (isBrowserClient && remember { BrowserKiosk.isInsecureOrigin() }) {
                PosCard {
                    body {
                        Text(
                            text = str(TerminalPairingKeys.insecureConnection),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                        )
                    }
                }
            }

            if (errorMessage != null) {
                PosCard {
                    body {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                        )
                    }
                }
            }

            if (showScanner && scannerAvailable) {
                Box(modifier = Modifier.fillMaxWidth().height(300.dp)) {
                    QrScanner(
                        modifier = Modifier.fillMaxSize(),
                        flashlightOn = false,
                        cameraLens = CameraLens.Back,
                        openImagePicker = false,
                        onCompletion = { scanned ->
                            showScanner = false
                            val payload = PairingQrPayload.parse(scanned)
                            if (payload != null) {
                                val trimmedUrl = payload.serverUrl.trim()
                                val urlError = ServerUrlKeys.errorFor(ServerUrlPolicy.evaluate(trimmedUrl))
                                if (urlError != null) {
                                    viewModel.onServerUrlRejected(urlError)
                                } else {
                                    settingManager.setApiBaseUrl(trimmedUrl)
                                    viewModel.pair(payload.code, payload.rootFp, onPaired)
                                }
                            } else {
                                viewModel.onScanFailed()
                            }
                        },
                        imagePickerHandler = { },
                        onFailure = {
                            showScanner = false
                            viewModel.onScanFailed()
                        },
                    )
                }
                PosSecondaryButton(
                    text = str(CommonKeys.cancel),
                    onClick = { showScanner = false },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                PosTextField(
                    value = state.code,
                    onValueChange = viewModel::updateCode,
                    label = str(TerminalPairingKeys.codeLabel),
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isPinningSupported && apiBaseUrl.orEmpty().startsWith("https://", ignoreCase = true)) {
                    PosTextField(
                        value = state.rootCode,
                        onValueChange = viewModel::updateRootCode,
                        label = str(TerminalPairingKeys.rootCodeLabel),
                        imeAction = ImeAction.Done,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = str(TerminalPairingKeys.rootCodeHint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                PosButton(
                    text = str(TerminalPairingKeys.pairButton),
                    onClick = { viewModel.pair(state.code, state.rootCode, onPaired) },
                    enabled = state.code.isNotBlank() && !state.isPairing && !apiBaseUrl.isNullOrBlank(),
                    isLoading = state.isPairing,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (scannerAvailable) {
                    PosTextButton(
                        text = str(TerminalPairingKeys.scanQr),
                        onClick = { showScanner = true },
                    )
                }
            }
        }
    }

    if (showApiSettingsDialog) {
        PosApiSettingsDialog(
            currentUrl = apiBaseUrl ?: "",
            themeMode = settingManager.themeMode.collectAsState().value,
            onDismiss = {
                showApiSettingsDialog = false
                viewModel.onUrlDialogDismissed()
            },
            onSave = { newUrl, newTheme ->
                if (newUrl != null) {
                    settingManager.setApiBaseUrl(newUrl)
                }
                if (newTheme != null) {
                    settingManager.setThemeMode(newTheme)
                }
                showApiSettingsDialog = false
                viewModel.onUrlDialogDismissed()
            },
        )
    }
}
