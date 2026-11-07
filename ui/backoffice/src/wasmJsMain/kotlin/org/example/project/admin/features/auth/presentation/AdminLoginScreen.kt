package org.example.project.admin.features.auth.presentation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.str
import org.example.project.ui.components.LoginForm
import org.example.project.ui.components.LoginFormLabels
import org.example.project.ui.components.LoginScaffold
import org.example.project.ui.components.PosErrorWatcher
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun AdminLoginScreen(
    viewModel: AdminLoginViewModel = koinViewModel(),
    sessionEndedNotice: SessionEndedNotice = koinInject(),
) {
    val state = viewModel.state

    PosErrorWatcher(
        error = state.error,
        title = str(AdminStringKeys.LoginFailedTitle),
        onClear = viewModel::clearError,
        // This screen *is* the unauthenticated state, so the default suppression swallowed every
        // rejected sign-in: the error was cleared without ever reaching the toast queue and a wrong
        // password looked identical to a broken app. Same reasoning as BootstrapSetupScreen.
        suppressWhenSignedOut = false,
    )

    LoginScaffold(
        brandTitle = str(AdminStringKeys.LoginTitle),
        brandSubtitle = str(AdminStringKeys.LoginSubtitle),
        welcomeTitle = str(AdminStringKeys.LoginWelcome),
        welcomeSubtitle = str(AdminStringKeys.LoginPleaseSignIn),
        utilities = { LoginLanguageMenu() },
        // On a phone the rejected-sign-in toast sits at the bottom, where it covered the switch.
        utilitiesOnTop = true,
    ) {
        if (sessionEndedNotice.pending) {
            Text(
                text = str(AdminStringKeys.LoginSessionEndedNotice),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            )
        }
        LoginForm(
            identifier = state.identifier,
            secret = state.secret,
            onIdentifierChange = viewModel::onIdentifierChange,
            onSecretChange = viewModel::onSecretChange,
            onSubmit = viewModel::login,
            labels =
                LoginFormLabels(
                    identifier = str(AdminStringKeys.LoginIdentifier),
                    secret = str(AdminStringKeys.LoginSecret),
                    submit = str(AdminStringKeys.LoginSubmit),
                    showPassword = str(AdminStringKeys.LoginShowPassword),
                    hidePassword = str(AdminStringKeys.LoginHidePassword),
                ),
            isLoading = state.isSubmitting,
            canSubmit = state.canSubmit,
        )
    }
}
