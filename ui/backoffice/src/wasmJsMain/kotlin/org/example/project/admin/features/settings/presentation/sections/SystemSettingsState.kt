package org.example.project.admin.features.settings.presentation.sections

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.domain.enums.setup.StartPageTarget
import org.example.project.core.presentation.error.UiError
import org.example.project.core.presentation.strings.StringKey

/**
 * State for the System section — the API-docs switch and the server-wide default page for `/`.
 *
 * [apiDocsSetting]/[startPageDefault] are the working draft; the `saved*` twins are what the server
 * holds. [apiDocsEnabled] is what is actually served (setting, `POS_API_DOCS` and dev mode combined)
 * and [apiDocsLocked] means `POS_API_DOCS` forces it, so the switch is read-only. Start-page targets
 * stay wire strings, as the server sends them; [availableTargets] carries `pos` only while a
 * browser-station role is enabled.
 */
data class SystemSettingsState(
    val apiDocsSetting: Boolean = false,
    val savedApiDocsSetting: Boolean = false,
    val apiDocsEnabled: Boolean = false,
    val apiDocsLocked: Boolean = false,
    val startPageDefault: String = StartPageTarget.NONE.wire,
    val savedStartPageDefault: String = StartPageTarget.NONE.wire,
    val availableTargets: List<String> = listOf(StartPageTarget.NONE.wire, StartPageTarget.ADMIN.wire),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: UiError? = null,
    val successMessage: StringKey? = null,
    /** Bumped by every Save refused for invalid input, so the section reveals the field; transient, not part of [isDirty]. */
    val validationAttempt: Int = 0,
) {
    /**
     * The draft target is not offered — typically a stored `pos` after browser stations were switched
     * off. The draft keeps it so the operator sees what was saved; the server would refuse it with a 400.
     */
    /** Docs are served although the stored switch is off and nothing locks it: dev mode forces them on. */
    val apiDocsOnInDevMode: Boolean get() = apiDocsEnabled && !apiDocsSetting && !apiDocsLocked

    val startPageUnavailable: Boolean get() = startPageDefault !in availableTargets

    /** An unavailable target counts as dirty: the stored value has to be replaced before anything saves. */
    val isDirty: Boolean
        get() = apiDocsSetting != savedApiDocsSetting || startPageDefault != savedStartPageDefault || startPageUnavailable

    /**
     * Field errors in the shape the form-focus helper reads. The start-page card renders its own
     * message (it needs the target's label as an argument); the key here names what is wrong.
     */
    val fieldErrors: Map<SystemSettingsField, StringKey>
        get() =
            if (startPageUnavailable) {
                mapOf(SystemSettingsField.START_PAGE to AdminStringKeys.SettingsSystemStartPageUnavailable)
            } else {
                emptyMap()
            }

    /**
     * Whether the Save button is enabled: something changed (the pristine-edit rule) and nothing is in
     * flight. Validity is deliberately NOT part of it — an unavailable start page is refused when Save is
     * pressed (see `SystemSettingsViewModel.save`), which reveals the field.
     */
    val canSave: Boolean get() = isDirty && !isSaving && !isLoading
}
