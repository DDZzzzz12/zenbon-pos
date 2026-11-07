package org.example.project.features.auth.presentation.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What the login form holds.
 *
 * The remembered credentials load off the UI thread, because opening the sealed password can block, so they can
 * arrive after the user has started typing. [prefill] then leaves the form alone.
 */
@Stable
class LoginFormState {
    var identifier by mutableStateOf("")
        private set

    var secret by mutableStateOf("")
        private set

    var rememberMe by mutableStateOf(false)
        private set

    /** Set by the first change the user makes. A plain flag: nothing renders it. */
    private var edited = false

    fun onIdentifierChange(value: String) {
        edited = true
        identifier = value
    }

    fun onSecretChange(value: String) {
        edited = true
        secret = value
    }

    fun onRememberMeChange(value: Boolean) {
        edited = true
        rememberMe = value
    }

    /** Pre-fills the form from [remembered], unless the user has already changed a field. */
    fun prefill(remembered: RememberedLogin) {
        if (edited) return
        identifier = remembered.identifier
        secret = remembered.secret
        rememberMe = true
    }
}
