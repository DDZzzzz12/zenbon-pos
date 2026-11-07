package org.example.project.ui.components.form

import org.example.project.core.presentation.strings.StringKey

/**
 * The editing state of one record — the saved [original], the user's [draft] and per-field
 * [errors] — as an immutable value, so a ViewModel can keep it in its `StateFlow` state:
 *
 * ```kotlin
 * _state.update { it.copy(form = it.form.edit { p -> p.copy(name = newName) }) }
 * ```
 *
 * Drives [PosSaveBar] (`isDirty`, `errorCount`), [PosChangedMarker] and the
 * "Änderungen verwerfen?" guard ([PosDiscardChangesDialog]). Replaces per-screen dirty flags.
 *
 * @param T the record's form model; must implement `equals` meaningfully (a data class), because
 *   [isDirty] is `draft != original` — editing a value and editing it back is not a change.
 * @property errors field id → message key. Field ids are the caller's (usually the property name).
 */
data class PosFormState<T>(
    val original: T,
    val draft: T = original,
    val errors: Map<String, StringKey> = emptyMap(),
) {
    val isDirty: Boolean get() = draft != original
    val errorCount: Int get() = errors.size
    val hasErrors: Boolean get() = errors.isNotEmpty()

    fun errorFor(field: String): StringKey? = errors[field]

    /** Applies a change to the draft and clears the errors of [touchedFields], if any are named. */
    fun edit(
        vararg touchedFields: String,
        transform: (T) -> T,
    ): PosFormState<T> = copy(draft = transform(draft), errors = errors - touchedFields.toSet())

    fun withErrors(errors: Map<String, StringKey>): PosFormState<T> = copy(errors = errors)

    /** After a successful save: [saved] (the server's version, if it returns one) becomes the baseline. */
    fun saved(saved: T = draft): PosFormState<T> = PosFormState(original = saved)

    /** Abbrechen / Verwerfen: back to the saved values, errors cleared. */
    fun discarded(): PosFormState<T> = PosFormState(original = original)
}
