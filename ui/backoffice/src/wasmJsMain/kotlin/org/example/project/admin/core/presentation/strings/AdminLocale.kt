package org.example.project.admin.core.presentation.strings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.example.project.core.domain.enums.setup.Language

/**
 * Observable holder for the admin panel's current UI language.
 *
 * Defaults to the system default (German). A language-switcher UI is a later
 * slice; reading [language] inside composition makes text reactive to changes.
 */
object AdminLocale {
    var language by mutableStateOf(Language.DEFAULT)
}
