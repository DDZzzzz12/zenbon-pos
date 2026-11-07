package org.example.project.ui.components.form

/**
 * A field label marked as required: `Name *`.
 *
 * The asterisk is a typographic convention, not a word, so it needs no translation; the error a
 * required field shows when Save is pressed empty is the localized part (UI-E2-06: an empty create
 * form used to give no hint which fields it needed).
 */
fun requiredLabel(label: String): String = "$label *"
