package org.example.project.ui.components

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A `UiError` must never be flattened into `showError`'s string overload.
 *
 * **The defect this closes.** [org.example.project.core.presentation.components.notification.OverlayEventBus]
 * has two `showError` overloads. The typed one takes the `UiError` itself, attaches it to the
 * `NotificationState`, and reports it to `ClientErrorReporter`; `PosNotificationHost` then renders
 * `PosErrorDialog`, which is the only surface that shows the **traceId with its copy button**, the
 * server's RFC 7807 `detail`, the `PosErrorCode` and the per-field validation list. The string
 * overload takes two already-rendered strings and leaves `NotificationState.error` null, so the host
 * falls back to the plain popup: a title and a generic message, nothing to quote to support, and no
 * record in the client error log.
 *
 * Six call sites across five screens had resolved `error.messageKey` themselves and passed the
 * resulting text to the string overload. The observed symptom was a change-log screen returning 500
 * while the server logged `ERROR [c6ef2618-…] StatusPages`, and the operator being shown a generic
 * sentence with no way to connect the two — the trace id had reached the client and was discarded at
 * the final call.
 *
 * **The rule is about the dropped payload, not the overload.** The string overload is perfectly
 * correct where there is no `UiError` behind the message: `AdminApp`'s nav-access-denied toast is a
 * client-side routing decision with no server error, and `NotificationViewModel`'s WebSocket
 * connection failures carry a `NotificationConnectionError`, not a `UiError`. Banning the overload
 * outright would flag all four and teach the next reader to work around the guard. So a call is
 * flagged only when a `UiError` is demonstrably being taken apart to feed it.
 *
 * **How a `UiError` is recognized.** Its property accesses are the signature: `.messageKey`,
 * `.messageArgs`, `.rawDetail`, `.fieldErrors`. `.messageKey` is deliberately matched only as a
 * *property* — `NotificationConnectionErrorKeys.messageKey(reason)` is a **function** returning a
 * `StringKey` and is not a `UiError` at all, so a match followed by `(` is ignored. Both shapes the
 * five real bypasses used are detected: the signal inline in the argument list
 * (`message = getString(err.messageKey, …)`), and the signal hoisted into a local
 * (`val errorMessage = error?.let { str(it.messageKey, …) }` then `message = errorMessage`).
 *
 * Scoping is **per call site**, never per file: `PosTseContent` legitimately reads `it.messageKey`
 * for an inline `ErrorState` while both its `showError` calls pass `error =`, and a file-level rule
 * would flag it.
 *
 * **How this fails safe.** A textual scanner that silently stops matching reports a clean sweep,
 * which is worse than no guard — and that is not hypothetical here: the first draft of this scan
 * used [PosDialogScan]'s `(?<![\w.])` lead-in, which excludes a receiver, so it found **zero**
 * `showError` calls and passed. The floors below are what caught it:
 *  - at least [MINIMUM_EXPECTED_SOURCES] Kotlin sources must be walked (951 when written);
 *  - at least [MINIMUM_EXPECTED_CALL_SITES] `showError` call sites must be found (7 when written).
 *
 * Raise either freely; never lower one to make a failure go away.
 *
 * **Known limitation.** Resolving a hoisted local is by name within the file, so a call passing an
 * identifier that merely *shares a name* with an unrelated `val` derived from a `UiError` elsewhere
 * in the same file would be flagged. No such case exists today, and the failure message names the
 * file and line so a reader can judge it in seconds. Tightening it further would need a real Kotlin
 * parser, which is more machinery than this one rule is worth.
 *
 * Sources are comment- and string-stripped first (via [PosDialogScan]), so a commented-out bypass
 * cannot fail the build and a string literal containing `showError(` cannot invent a call site.
 */
class NoDroppedUiErrorInShowErrorTest {
    private companion object {
        /**
         * Relative to composeApp's project directory, which is the working directory of its test tasks.
         *
         * `shared/` is deliberately absent: it is Compose-free and `OverlayEventBus` lives in
         * `shared-ui`, so no `showError` call can exist there. `shared-ui` and `adminApp` are
         * registered as task inputs in `ui/till/build.gradle.kts` — without that, this task goes
         * UP-TO-DATE when only another module changed and the guard passes by not running.
         */
        val SCAN_ROOTS =
            listOf(
                File("src/commonMain/kotlin"),
                File("../shared/src/commonMain/kotlin"),
                File("../backoffice/src/wasmJsMain/kotlin"),
            )

        /** 951 sources sat under [SCAN_ROOTS] when this was written. */
        const val MINIMUM_EXPECTED_SOURCES = 700

        /** 7 call sites existed when this was written: 4 legitimately untyped, 3 typed. */
        const val MINIMUM_EXPECTED_CALL_SITES = 5

        /**
         * No `(?<![\w.])` here — unlike `PosDialog`, `showError` is always called on a receiver
         * (`overlay.showError(`), and excluding a preceding `.` matches nothing at all.
         */
        val CALL = Regex("""(?<!\w)showError\s*\(""")

        /** The typed overload names its first parameter `error`. */
        val TYPED_ARGUMENT = Regex("""\berror\s*=""")

        /**
         * Property accesses that only exist on a `UiError`. `.messageKey` must not be followed by `(`
         * — that form is a key object's function, e.g. `NotificationConnectionErrorKeys.messageKey(…)`.
         */
        val UI_ERROR_SIGNALS =
            listOf(
                Regex("""\.messageKey\b(?!\s*\()"""),
                Regex("""\.messageArgs\b"""),
                Regex("""\.rawDetail\b"""),
                Regex("""\.fieldErrors\b"""),
            )

        /** Candidate local names appearing in an argument list, e.g. `message = errorMessage`. */
        val IDENTIFIER = Regex("""\b([a-z]\w*)\b""")
    }

    private fun sources(): List<File> =
        SCAN_ROOTS
            .flatMap { root ->
                assertTrue(
                    root.isDirectory,
                    "scan root not found at ${root.absolutePath}. This guard runs from composeApp's project " +
                        "directory; if a module moved, fix the path here rather than letting the walk return " +
                        "nothing — a scan over no files reports every screen compliant.",
                )
                root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            }.sortedBy { it.path }

    /** Index of the `)` closing the `(` at [openIndex]. */
    private fun matchingParen(
        code: String,
        openIndex: Int,
    ): Int? {
        var depth = 0
        for (i in openIndex until code.length) {
            when (code[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    /** Argument lists of every `showError(…)` **call** in [code], paired with their 1-based line. */
    private fun callSites(code: String): List<Pair<Int, String>> =
        CALL
            .findAll(code)
            // `fun showError(` / `override fun showError(` are the declarations on OverlayEventBus.
            .filterNot { m -> code.substring(0, m.range.first).trimEnd().endsWith("fun") }
            .mapNotNull { m ->
                val open = code.indexOf('(', m.range.first)
                val close = matchingParen(code, open) ?: return@mapNotNull null
                PosDialogScan.lineOf(code, m.range.first) to code.substring(open + 1, close)
            }.toList()

    private fun uiErrorSignalsIn(text: String): List<String> =
        UI_ERROR_SIGNALS
            .flatMap { rx -> rx.findAll(text).map { it.value.trim() }.toList() }
            .distinct()
            .sorted()

    /**
     * The full `val <name> = …` statement, so a signal hoisted out of the call is still seen.
     *
     * Scans to the first newline at bracket depth zero that follows at least one token after the `=`.
     * The token condition is what stops a declaration whose initializer begins on the *next* line
     * (`val exportErrorMessage =` ⏎ `    uiState.error?.let { … }`) from being cut off at the `=`.
     */
    private fun declarationOf(
        code: String,
        name: String,
    ): String? {
        val start = Regex("""\bval\s+${Regex.escape(name)}\b""").find(code) ?: return null
        var depth = 0
        var sawEquals = false
        var sawToken = false
        for (i in start.range.last + 1 until code.length) {
            when (val ch = code[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                '=' -> if (depth == 0) sawEquals = true
                '\n' -> if (depth <= 0 && sawEquals && sawToken) return code.substring(start.range.first, i)
                else -> if (sawEquals && !ch.isWhitespace()) sawToken = true
            }
        }
        return code.substring(start.range.first)
    }

    /** Why this argument list drops a UiError, or null when it does not. */
    private fun violationReason(
        code: String,
        arguments: String,
    ): String? {
        if (TYPED_ARGUMENT.containsMatchIn(arguments)) return null

        val inline = uiErrorSignalsIn(arguments)
        if (inline.isNotEmpty()) return "its arguments take a UiError apart (${inline.joinToString(", ")})"

        return IDENTIFIER
            .findAll(arguments)
            .map { it.groupValues[1] }
            .distinct()
            .firstNotNullOfOrNull { name ->
                val signals = uiErrorSignalsIn(declarationOf(code, name) ?: return@firstNotNullOfOrNull null)
                if (signals.isEmpty()) {
                    null
                } else {
                    "the local `val $name` it passes is built from a UiError (${signals.joinToString(", ")})"
                }
            }
    }

    @Test
    fun `the scanner finds the showError call sites it is meant to check`() {
        val files = sources()

        assertTrue(
            files.size >= MINIMUM_EXPECTED_SOURCES,
            "walked only ${files.size} Kotlin sources under ${SCAN_ROOTS.map { it.path }}, expected at least " +
                "$MINIMUM_EXPECTED_SOURCES. Fix the roots — a scan over too few files reports the screens it " +
                "never opened as compliant.",
        )

        val found = files.sumOf { file -> callSites(PosDialogScan.stripCommentsAndStrings(file.readText())).size }

        assertTrue(
            found >= MINIMUM_EXPECTED_CALL_SITES,
            "found only $found showError call sites, expected at least $MINIMUM_EXPECTED_CALL_SITES. The pattern " +
                "has stopped matching. This exact failure already happened once: the first draft used " +
                "PosDialogScan's `(?<![\\w.])` lead-in, which excludes a receiver, so it matched zero " +
                "`overlay.showError(` calls and passed clean. Fix the pattern; never lower this floor.",
        )
    }

    @Test
    fun `no screen flattens a UiError into the string overload of showError`() {
        val offenders =
            sources().flatMap { file ->
                val code = PosDialogScan.stripCommentsAndStrings(file.readText())
                callSites(code).mapNotNull { (line, arguments) ->
                    violationReason(code, arguments)?.let { "${file.path}:$line\n      $it" }
                }
            }

        assertTrue(
            offenders.isEmpty(),
            buildString {
                append("A UiError is being flattened into showError's STRING overload, which drops it.\n")
                append("That overload leaves NotificationState.error null, so PosNotificationHost renders the\n")
                append("plain popup instead of PosErrorDialog: no traceId (and no copy button), no server\n")
                append("detail, no error code, and no ClientErrorReporter record. The operator is left with a\n")
                append("generic sentence they cannot correlate with the server log.\n\n")
                append("Fix: mount the shared watcher instead of hand-rolling the overlay call —\n")
                append("    PosErrorWatcher(error = state.error, title = str(SomeKeys.title), onClear = vm::clearError)\n")
                append("It picks dialog vs toast by what the error carries, so the call site does not choose.\n")
                append("Where the surrounding structure must stay (a gated effect, say), call the TYPED overload:\n")
                append("    overlay.showError(error = err, title = …, message = …)\n\n")
                append("If there is genuinely no UiError behind this message, the string overload is correct —\n")
                append("see AdminApp's nav-denied toast. Then this is a false positive: say so at the call site.\n")
                offenders.forEach { append("\n  $it\n") }
            },
        )
    }
}
