package org.example.project.features.launch.presentation.viewmodels

import org.example.project.core.presentation.error.UiError

/**
 * A refused station-session exchange, as a one-shot event.
 *
 * Carries the typed [UiError] rather than a String, so the pairing screen has the
 * [org.example.project.core.error.PosErrorCode] and HTTP status for diagnostics and a localized
 * title/message key to render — the project's rule is that ViewModel state never carries a
 * pre-rendered String error.
 *
 * The ceiling answers a revoked or deactivated terminal with a plain `{"error":"..."}` body, not
 * RFC 7807, so `ProblemDetail` parses into an all-null record and the decoder's message is null.
 * [serverDetail] is therefore only *sometimes* present, and the UI falls back to
 * `error.messageKey` — otherwise the refusal is exactly the silent reset it is meant to explain.
 */
data class StationRefusal(
    val error: UiError,
) {
    /** The server's own sentence when it wrote one; null when the localized message must win. */
    val serverDetail: String?
        get() = (error as? UiError.FromServer)?.rawDetail?.trim()?.takeIf { it.isNotEmpty() }
}
