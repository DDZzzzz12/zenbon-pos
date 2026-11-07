package org.example.project.features.launch.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInBrowser
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.example.project.core.auth.LocalSettingManager
import org.example.project.core.network.defaultApiBaseUrl
import org.example.project.core.presentation.strings.keys.LaunchKeys
import org.example.project.core.presentation.strings.keys.ServerUrlKeys
import org.example.project.core.presentation.strings.keys.SetupKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.ServerUrlPolicy
import org.example.project.features.launch.presentation.components.QrCodeView
import org.example.project.ui.components.PosButton
import org.example.project.ui.components.PosTextField

/**
 * Screen shown when the server has not completed initial setup.
 *
 * Explains that setup must be done in the web admin panel, shows a QR code for the admin URL,
 * and lets the user edit the server URL before retrying.
 *
 * The URL is editable only when [canChangeServerAddress] — a device that is not paired. A paired
 * till lands here when its own server was reset, and gets the reason instead of the field. An
 * edited address is stored through [onChangeServerAddress] (the logic half of the guard,
 * `LaunchCoordinator.changeServerAddress`, which also re-runs the launch), never written here.
 */
@Composable
fun WebSetupRequiredScreen(
    canChangeServerAddress: Boolean,
    onCheckAgain: () -> Unit,
    onChangeServerAddress: (address: String) -> Unit,
) {
    val settingManager = LocalSettingManager.current
    val apiBaseUrl by settingManager.url.collectAsState()
    val adminUrl = remember(apiBaseUrl) { apiBaseUrl.orEmpty().trimEnd('/') + "/admin" }
    var editedUrl by remember(apiBaseUrl) { mutableStateOf(apiBaseUrl ?: "") }

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
                imageVector = Icons.Default.OpenInBrowser,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp),
            )
            Text(
                text = str(SetupKeys.webSetupRequiredTitle),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = str(SetupKeys.webSetupRequiredSubtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Text(
                text = str(SetupKeys.openWebAdminHint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            QrCodeView(data = adminUrl, modifier = Modifier.size(180.dp))

            Text(
                text = adminUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(8.dp))

            val trimmedUrl = editedUrl.trim()
            val urlEdited = canChangeServerAddress && trimmedUrl.isNotBlank() && trimmedUrl != apiBaseUrl
            val urlError = if (urlEdited) ServerUrlKeys.errorFor(ServerUrlPolicy.evaluate(trimmedUrl)) else null

            if (canChangeServerAddress) {
                PosTextField(
                    value = editedUrl,
                    onValueChange = { editedUrl = it },
                    label = str(SetupKeys.serverUrlLabel),
                    modifier = Modifier.fillMaxWidth(),
                    isError = urlError != null,
                    errorMessage = urlError?.let { str(it) },
                )

                // "Still on the default" -- compared against the platform default rather than a
                // literal, which on wasm is the page origin and never equalled localhost.
                if (apiBaseUrl == defaultApiBaseUrl()) {
                    Text(
                        text = str(SetupKeys.defaultUrlHint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                Text(
                    text = str(LaunchKeys.serverAddressLockedWhilePaired),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            PosButton(
                text = str(SetupKeys.checkAgainButton),
                onClick = {
                    if (urlEdited) {
                        // Re-runs the launch itself once the address is accepted; a rejected one
                        // surfaces as the coordinator's typed error and nothing is stored.
                        onChangeServerAddress(trimmedUrl)
                    } else {
                        onCheckAgain()
                    }
                },
                enabled = urlError == null,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
