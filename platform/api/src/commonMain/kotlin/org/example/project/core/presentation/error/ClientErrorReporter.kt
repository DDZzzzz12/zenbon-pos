package org.example.project.core.presentation.error

/**
 * Sends errors the user was actually shown to the server, where they are written to the
 * client-error log alongside the server's own failures.
 *
 * Reporting is best-effort and must never interfere with showing the error: an implementation
 * swallows its own failures. The whole point is diagnosing a broken client, so a client that is
 * already failing must not be made worse by the reporting path.
 */
interface ClientErrorReporter {
    /**
     * Report [error], which is being surfaced to the user right now.
     *
     * [shownTitle] and [shownMessage] are passed in already localized rather than derived here:
     * resolving them needs the composition's string context, and the log should record what the
     * operator actually read — that is the text they will quote to support.
     *
     * @param screen route or screen the error surfaced on, for the log line.
     */
    fun report(
        error: UiError,
        shownTitle: String,
        shownMessage: String,
        screen: String? = null,
    )
}
