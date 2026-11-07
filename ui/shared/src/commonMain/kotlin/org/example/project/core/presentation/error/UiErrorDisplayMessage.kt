package org.example.project.core.presentation.error

import androidx.compose.runtime.Composable
import org.example.project.core.presentation.strings.str

/**
 * The single line to show a user for this error.
 *
 * Resolves the localized message, except where [serverDetailFallback] says the server's own
 * `detail` is the more useful thing — see that function for the precedence rules and why the
 * unlocalized fallback is a deliberate trade.
 *
 * Prefer this over rendering `str(error.messageKey, …)` directly: most [org.example.project.core.error.PosErrorCode]s
 * have no dedicated translation, so the raw key resolves to a generic "something went wrong"
 * and silently discards whatever the server said was wrong.
 */
@Composable
fun UiError.displayMessage(): String = serverDetailFallback() ?: str(messageKey, *messageArgs.toTypedArray())
