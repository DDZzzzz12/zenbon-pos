package org.example.project.ui.screens.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.core.presentation.components.LanguageSelector
import org.example.project.core.presentation.strings.keys.AppUpdateKeys
import org.example.project.core.presentation.strings.keys.LoginKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.appupdate.presentation.components.AppUpdateDialog
import org.example.project.features.appupdate.presentation.viewmodels.AppUpdateViewModel
import org.example.project.features.auth.presentation.state.LoginFormState
import org.example.project.features.auth.presentation.state.RememberedLogin
import org.example.project.features.auth.presentation.viewmodels.LoginViewModel
import org.example.project.features.auth.presentation.viewmodels.ServerStatus
import org.example.project.ui.components.LoginForm
import org.example.project.ui.components.LoginFormLabels
import org.example.project.ui.components.LoginScaffold
import org.example.project.ui.components.PosIconButton
import org.example.project.ui.theme.PosSpacing

@Composable
fun LoginScreen(
    modifier: Modifier = Modifier,
    onLogin: (identifier: String, secret: String, rememberMe: Boolean) -> Unit,
    isAuthenticated: Boolean,
    loginViewModel: LoginViewModel,
    appUpdateViewModel: AppUpdateViewModel,
) {
    val uiState by loginViewModel.uiState.collectAsState()

    LaunchedEffect(Unit) { appUpdateViewModel.check(manual = false) }

    LoginScaffold(
        modifier = modifier,
        brandTitle = str(LoginKeys.title),
        brandSubtitle = str(LoginKeys.subtitle),
        welcomeTitle = str(LoginKeys.welcomeBack),
        welcomeSubtitle = str(LoginKeys.pleaseSignIn),
        utilities = {
            StatusDot(status = uiState.serverStatus, onClick = { loginViewModel.checkHealth() })
            if (appUpdateViewModel.supportsUpdates) {
                PosIconButton(
                    icon = Icons.Default.Refresh,
                    contentDescription = str(AppUpdateKeys.checkButton),
                    onClick = { appUpdateViewModel.check(manual = true) },
                )
            }
            LanguageSelector(compact = true)
        },
    ) {
        LoginFormFields(
            onLogin = onLogin,
            isLoading = uiState.isLoading,
            isAuthenticated = isAuthenticated,
            loadRememberedLogin = loginViewModel::loadRememberedLogin,
        )
    }

    AppUpdateDialog(appUpdateViewModel)
}

@Composable
private fun LoginFormFields(
    onLogin: (String, String, Boolean) -> Unit,
    isLoading: Boolean,
    isAuthenticated: Boolean,
    loadRememberedLogin: suspend () -> RememberedLogin?,
) {
    val form = remember { LoginFormState() }

    // Once each time the form appears, never during composition: the remembered password is sealed and opens off the
    // UI thread. It goes straight into the form and nowhere else, and leaving the screen cancels the load. The load can
    // finish after the user has started typing; LoginFormState.prefill then keeps what they typed.
    LaunchedEffect(Unit) { loadRememberedLogin()?.let(form::prefill) }

    LoginForm(
        identifier = form.identifier,
        secret = form.secret,
        onIdentifierChange = form::onIdentifierChange,
        onSecretChange = form::onSecretChange,
        onSubmit = { onLogin(form.identifier, form.secret, form.rememberMe) },
        labels =
            LoginFormLabels(
                identifier = str(LoginKeys.username),
                secret = str(LoginKeys.password),
                submit = str(LoginKeys.loginButton),
                showPassword = str(LoginKeys.showPassword),
                hidePassword = str(LoginKeys.hidePassword),
            ),
        isLoading = isLoading || isAuthenticated,
        canSubmit = !isLoading && !isAuthenticated,
        identifierIcon = Icons.Default.Person,
        secretIcon = Icons.Default.Lock,
        modifier = Modifier.fillMaxWidth(),
        belowFields = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Checkbox(
                    checked = form.rememberMe,
                    onCheckedChange = form::onRememberMeChange,
                )
                Text(
                    text = str(LoginKeys.rememberMe),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        },
    )
}

@Composable
private fun StatusDot(
    status: ServerStatus,
    onClick: () -> Unit,
) {
    val color =
        when (status) {
            ServerStatus.CONNECTED -> MaterialTheme.colorScheme.primary
            ServerStatus.CHECKING -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.error
        }

    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.2f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = PosSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PosSpacing.sm),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(color),
            )
            Text(
                text =
                    when (status) {
                        ServerStatus.CONNECTED -> str(LoginKeys.statusOnline)
                        ServerStatus.CHECKING -> str(LoginKeys.statusChecking)
                        // ERROR is an unexpected health-check exception. From the counter it is the
                        // same fact as unreachable — there is no working server — and the remedy is
                        // identical, so it reads Offline rather than inventing a fourth word staff
                        // cannot act on differently. Listed explicitly (no `else`) so a future
                        // ServerStatus fails to compile here instead of silently reading Offline.
                        ServerStatus.DISCONNECTED, ServerStatus.ERROR -> str(LoginKeys.statusOffline)
                    },
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
