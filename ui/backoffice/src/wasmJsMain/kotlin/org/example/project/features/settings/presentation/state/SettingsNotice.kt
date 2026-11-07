package org.example.project.features.settings.presentation.state

import org.example.project.core.presentation.strings.StringKey

/**
 * A completed action's confirmation, held as localized keys so the screen resolves it in the
 * operator's language. Replaces the English `successMessage` text the backup, restore and
 * Kassenmeldung actions used to raise under the "Export erfolgreich abgeschlossen" title (F63).
 *
 * @property title Heading that names what happened (backup created, restore finished, PDF exported)
 * @property message Body; may hold `{0}` placeholders filled from [args]
 * @property args Values substituted into [message] — a file path, never translated text
 */
data class SettingsNotice(
    val title: StringKey,
    val message: StringKey,
    val args: List<String> = emptyList(),
)
