package org.example.project.admin.core.presentation.components

/**
 * Fills the `{0}` slot of a localized delete-dialog title template with the object's name, so the
 * dialog asks about a named thing ("„Cola“ löschen?") instead of a generic "Löschen?". The name is
 * trimmed; a blank name falls back to [fallback] so the title never carries an empty pair of quotes.
 */
fun deleteConfirmTitle(
    template: String,
    name: String,
    fallback: String = name,
): String {
    val shown = name.trim().ifEmpty { fallback.trim() }
    return template.replace("{0}", shown)
}
