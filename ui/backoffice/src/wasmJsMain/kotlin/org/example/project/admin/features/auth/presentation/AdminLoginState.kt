package org.example.project.admin.features.auth.presentation

import org.example.project.core.presentation.error.UiError

/**
 * UI state for the admin login screen.
 */
data class AdminLoginState(
    val identifier: String = "",
    val secret: String = "",
    val isSubmitting: Boolean = false,
    val error: UiError? = null,
) {
    val canSubmit: Boolean
        get() = identifier.isNotBlank() && secret.isNotBlank() && !isSubmitting
}
