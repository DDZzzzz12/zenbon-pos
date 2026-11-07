package org.example.project.features.auth.presentation.state

/**
 * The credentials saved with "remember me", for pre-filling the login form. It exists only when remember-me was on.
 *
 * [toString] masks [secret]: this sits in the login screen's UI state, and a logged state must never carry the
 * password.
 */
data class RememberedLogin(
    val identifier: String,
    val secret: String,
) {
    override fun toString(): String = "RememberedLogin(identifier=$identifier, secret=***)"
}
