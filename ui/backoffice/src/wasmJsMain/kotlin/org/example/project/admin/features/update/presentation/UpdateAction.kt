package org.example.project.admin.features.update.presentation

/** The in-flight operation, so exactly one button shows a spinner and the rest disable. */
enum class UpdateAction {
    CHECK,
    STAGE,
    APPLY,
    SAVE_TOKEN,
    CLEAR_TOKEN,
    SAVE_SETTINGS,
}
