package org.example.project.core.presentation.error

import org.example.project.core.presentation.strings.keys.CommonKeys

/**
 * The server's own `detail` when it is the more useful thing to show, or `null` when the
 * localized message should win.
 *
 * Only a handful of [org.example.project.core.error.PosErrorCode]s have a dedicated translation
 * in [PosApiException.toUiError]; every other code falls through to
 * [CommonKeys.unknownErrorMessage] — "Something went wrong. Please try again." That generic line
 * throws away a sentence the server wrote specifically to tell the operator what to fix, e.g.
 * *"A Steuernummer is not a USt-IdNr; leave this empty and set the tax number instead."*
 * Losing it turns an actionable 400 into a dead end.
 *
 * Precedence is deliberate: a mapped, localized message always wins. This only fills the gap
 * where the alternative is the generic fallback.
 *
 * The returned detail is server-authored and therefore **not localized**. That is a considered
 * trade — an actionable sentence in the server's language beats a localized dead end — and not a
 * licence to skip translation. Mapping the code in `toUiError` remains the better fix for any
 * error operators hit regularly; this is the safety net for the long tail.
 *
 * Pure and Compose-free so the precedence rules can be unit-tested; the rendering half lives in
 * `shared-ui`'s `displayMessage()`.
 */
fun UiError.serverDetailFallback(): String? {
    if (messageKey != CommonKeys.unknownErrorMessage) return null
    val detail = (this as? UiError.FromServer)?.rawDetail?.trim()
    return detail?.takeIf { it.isNotEmpty() }
}
