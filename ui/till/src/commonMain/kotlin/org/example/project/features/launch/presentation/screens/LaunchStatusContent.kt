package org.example.project.features.launch.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.example.project.core.auth.LocalSettingManager
import org.example.project.core.network.isPinningSupported
import org.example.project.core.presentation.strings.keys.LaunchKeys
import org.example.project.core.presentation.strings.keys.ServerUrlKeys
import org.example.project.core.presentation.strings.keys.SetupKeys
import org.example.project.core.presentation.strings.keys.TerminalPairingKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.ServerUrlPolicy
import org.example.project.features.launch.presentation.nav.StationBlock
import org.example.project.ui.components.PosApiSettingsDialog
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosCard
import org.example.project.ui.components.PosSecondaryButton
import org.example.project.ui.components.PosTextButton
import org.example.project.ui.components.PosTextField

/**
 * Standalone "resolving" status content for the launch/auth flow, rendered directly from
 * [org.example.project.features.launch.presentation.nav.AppDestination.Resolving] by
 * `AppNavigation`. Body copied verbatim from `LaunchGate`'s `LoadingContent`.
 */
@Composable
fun LaunchLoadingContent() {
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
            CircularProgressIndicator(modifier = Modifier.size(48.dp))
            Text(
                text = str(LaunchKeys.checkingTitle),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = str(LaunchKeys.checkingSubtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * A paired order station that cannot start yet — a server-side fault, or a session this device
 * cannot store right now: the reason, and a retry.
 *
 * Deliberately not the offline screen — the server answered, and telling staff to check the
 * network sends them after a fault that does not exist. Deliberately not the pairing screen
 * either: the device stays paired, and the same terminal starts normally once the server side is
 * repaired or the key store answers again.
 */
@Composable
fun LaunchStationBlockedContent(
    block: StationBlock,
    onRetry: () -> Unit,
) {
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
                imageVector = Icons.Default.ReportProblem,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(64.dp),
            )
            Text(
                text = str(LaunchKeys.stationBlockedTitle),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = str(block.messageKey),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            PosButton(
                text = str(LaunchKeys.retryButton),
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Standalone "offline" status content for the launch/auth flow, rendered directly from
 * [org.example.project.features.launch.presentation.nav.AppDestination.Offline] by
 * `AppNavigation`.
 *
 * Shows the address that could not be reached. A device that is not paired yet may change it here,
 * through the same [PosApiSettingsDialog] the pairing screen uses — otherwise a fresh install on the
 * platform default (`localhost`, which on a tablet is the tablet itself) never reaches its server.
 * A paired device gets the reason instead of the action, and [onChangeServerAddress] refuses it too
 * (the logic half of the guard, `LaunchCoordinator.changeServerAddress`).
 */
@Composable
fun LaunchOfflineContent(
    canChangeServerAddress: Boolean,
    onRetry: () -> Unit,
    onChangeServerAddress: (address: String) -> Unit,
    certificateUntrusted: Boolean = false,
    onPinCertificate: (checkCode: String) -> Unit = {},
    onRepair: (() -> Unit)? = null,
) {
    val settingManager = LocalSettingManager.current
    val serverAddress by settingManager.url.collectAsState()
    var showServerAddressDialog by remember { mutableStateOf(false) }
    var checkCode by remember { mutableStateOf("") }

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
                imageVector = Icons.Default.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(64.dp),
            )
            Text(
                text = str(LaunchKeys.offlineTitle),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = str(LaunchKeys.offlineSubtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            PosCard {
                body {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = str(LaunchKeys.serverAddressLabel),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = serverAddress ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (canChangeServerAddress) {
                            PosTextButton(
                                text = str(LaunchKeys.changeServerAddress),
                                onClick = { showServerAddressDialog = true },
                            )
                        } else {
                            Text(
                                text = str(LaunchKeys.serverAddressLockedWhilePaired),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (certificateUntrusted && isPinningSupported) {
                Text(
                    text = str(TerminalPairingKeys.untrustedCertificateHint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                PosTextField(
                    value = checkCode,
                    onValueChange = { checkCode = it.take(MAX_CHECK_CODE_INPUT) },
                    label = str(TerminalPairingKeys.rootCodeLabel),
                    imeAction = ImeAction.Done,
                    modifier = Modifier.fillMaxWidth(),
                )
                PosButton(
                    text = str(TerminalPairingKeys.trustCertificateButton),
                    onClick = { onPinCertificate(checkCode) },
                    enabled = checkCode.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            PosButton(
                text = str(LaunchKeys.retryButton),
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
            )
            // A paired device cannot edit the address here, so when its server is gone for good the
            // only way back is a fresh pairing, which has the address dialog.
            if (!canChangeServerAddress && onRepair != null) {
                PosSecondaryButton(
                    text = str(LaunchKeys.repairDevice),
                    onClick = onRepair,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (showServerAddressDialog && canChangeServerAddress) {
        PosApiSettingsDialog(
            currentUrl = serverAddress ?: "",
            themeMode = settingManager.themeMode.collectAsState().value,
            onDismiss = { showServerAddressDialog = false },
            onSave = { newUrl, newTheme ->
                if (newTheme != null) {
                    settingManager.setThemeMode(newTheme)
                }
                showServerAddressDialog = false
                // A rejected address surfaces as the coordinator's typed error, not here.
                if (newUrl != null) {
                    onChangeServerAddress(newUrl)
                }
            },
        )
    }
}

/**
 * The stored server address is plain HTTP. Shows it, pre-fills the likely https:// address (Caddy
 * listens on 443, so the old `:8080` is dropped) and saves only an address ServerUrlPolicy accepts.
 */
@Composable
fun LaunchInsecureUrlContent(
    currentUrl: String,
    onSave: (address: String, checkCode: String?) -> Unit,
) {
    var editedUrl by remember(currentUrl) { mutableStateOf(ServerUrlPolicy.suggestHttps(currentUrl)) }
    var checkCode by remember { mutableStateOf("") }
    val urlError = ServerUrlKeys.errorFor(ServerUrlPolicy.evaluate(editedUrl))

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
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(64.dp),
            )
            Text(
                text = str(ServerUrlKeys.insecureTitle),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = str(ServerUrlKeys.insecureSubtitle, currentUrl),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            PosTextField(
                value = editedUrl,
                onValueChange = { editedUrl = it },
                label = str(SetupKeys.serverUrlLabel),
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
                modifier = Modifier.fillMaxWidth(),
                isError = urlError != null,
                errorMessage = urlError?.let { str(it) },
            )
            if (isPinningSupported) {
                PosTextField(
                    value = checkCode,
                    onValueChange = { checkCode = it.take(MAX_CHECK_CODE_INPUT) },
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
                text = str(ServerUrlKeys.saveAndRetry),
                onClick = { onSave(editedUrl.trim(), checkCode.trim().ifEmpty { null }) },
                enabled = urlError == null,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A check code typed with separators is longer than its 26 characters; anything past this is not a code. */
private const val MAX_CHECK_CODE_INPUT = 48
