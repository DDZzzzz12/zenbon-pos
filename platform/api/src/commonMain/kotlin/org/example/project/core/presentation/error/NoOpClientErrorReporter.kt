package org.example.project.core.presentation.error

/**
 * Discards reports.
 *
 * The default binding wherever reporting is not wired up — tests, previews, and any host that
 * has no server to talk to. Keeps [org.example.project.ui.components.PosErrorWatcher] free of
 * null checks.
 */
object NoOpClientErrorReporter : ClientErrorReporter {
    override fun report(
        error: UiError,
        shownTitle: String,
        shownMessage: String,
        screen: String?,
    ) = Unit
}
