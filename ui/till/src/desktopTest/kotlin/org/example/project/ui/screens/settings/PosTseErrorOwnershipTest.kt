package org.example.project.ui.screens.settings

import org.example.project.ui.components.PosDialogScan
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `PosTseContent` must keep exactly ONE owner of `TseSettingsUiState.error`.
 *
 * **The tangle this pins.** That state carries a single `error: UiError?` slot written by eleven
 * sites across three unrelated concerns — the initial load (config + tseInfo), the config save, and
 * the DSFinV-K/TSE export-verify-delete family. `PosTseContent` grew **two** `LaunchedEffect`s keyed
 * on it: one unconditional, one gated on `config != null`, and both calling `clearError()`. Two
 * distinct bugs followed:
 *
 *  1. With config loaded, a single failure enqueued **two dialogs** (differently titled, so the
 *     overlay's content-based de-duplication did not collapse them) and the two effects raced on
 *     the clear.
 *  2. With config null, the unconditional effect cleared the error out from under the inline
 *     `ErrorState` — the full-pane "load failed, Retry" placeholder that renders from the very same
 *     slot. That is exactly what the other effect's gate had been written to prevent, and its
 *     comment said so; the second effect defeated it anyway.
 *
 * **The rule now enforced.** One effect owns the slot. It *always* raises the dialog, because the
 * dialog is what carries the traceId, the server's `detail` and the error code, and what gets the
 * failure recorded by `ClientErrorReporter`; the inline placeholder shows none of those. What is
 * conditional is the **clear**: with `config != null` the error was an action failure and is
 * transient, so it is cleared; with `config == null` the inline `ErrorState` is still rendering
 * from that slot, so clearing would blank the screen behind the dialog, and the ViewModel ends it
 * instead when a retry starts or a load succeeds.
 *
 * Each assertion below maps to one property that must survive:
 *  - one error produces exactly one surface → one effect, one `showError`;
 *  - a TSE error keeps its dialog → `DisplayMode.DIALOG` is still forced;
 *  - the inline `ErrorState` keeps working → its `config == null` branch still exists, and the
 *    clear is still conditional rather than unconditional;
 *  - traceId/detail/reporting still reach the operator → the call still passes `error =`, the
 *    typed overload.
 *
 * **Why a source scan and not a ViewModel test.** There is no defect in `TseSettingsViewModel` and
 * this change does not touch it: the slot, its eleven writers and `clearError()` are unchanged. The
 * bug lived entirely in composable effect wiring, so the only faithful behavioural test would drive
 * `PosTseContent` under a Compose test harness and count surfaces. No such harness exists for
 * shared-ui today, and standing one up means faking three API clients (`TseSettingsApiClient` alone
 * declares 30 methods) whose calls all fire from the ViewModel's `init`. That is a great deal of
 * unverifiable scaffolding to assert something a reader can see directly in the source. If the
 * harness is ever built, the assertions it should make are: one `UiError` in ⇒ exactly one queued
 * notification out; that notification carries the `UiError` (so traceId renders); with `config`
 * null the error survives the dialog and the inline `ErrorState` is still composed; with `config`
 * non-null the error is cleared exactly once.
 *
 * **How this fails safe.** A scan that stops matching would report the rule upheld, so the anchors
 * are asserted before they are trusted: the file must be found, must be at least
 * [MINIMUM_EXPECTED_LINES] lines, and the effect body must be located. Source is comment- and
 * string-stripped first, so the prose in `PosTseContent`'s own ownership comment — which quotes
 * `LaunchedEffect(uiState.error)` — cannot be miscounted as a second reader.
 */
class PosTseErrorOwnershipTest {
    private companion object {
        /** Relative to composeApp's project directory, the working directory of its test tasks. */
        val SOURCE = File("../shared/src/commonMain/kotlin/org/example/project/ui/screens/settings/PosTseContent.kt")

        /** 709 lines when this was written; a stub or a truncated read must not pass. */
        const val MINIMUM_EXPECTED_LINES = 400

        val ERROR_EFFECT = Regex("""LaunchedEffect\s*\(\s*uiState\.error\s*\)""")

        /** The inline full-pane placeholder's branch in `PosTseContent`. */
        val INLINE_ERROR_BRANCH = Regex("""uiState\.error\s*!=\s*null\s*&&\s*uiState\.config\s*==\s*null""")

        val CLEAR_ERROR = Regex("""clearError\s*\(\s*\)""")

        val CONFIG_LOADED_GUARD = Regex("""uiState\.config\s*!=\s*null""")

        val SHOW_ERROR = Regex("""showError\s*\(""")

        val TYPED_ARGUMENT = Regex("""\berror\s*=""")
    }

    /** `PosTseContent.kt` with comments and string literals blanked out. */
    private fun code(): String {
        assertTrue(
            SOURCE.isFile,
            "PosTseContent not found at ${SOURCE.absolutePath}. This guard runs from composeApp's project " +
                "directory; if the file moved, fix the path rather than letting the scan find nothing — a scan " +
                "over no source reports the ownership rule upheld.",
        )
        val text = SOURCE.readText()
        assertTrue(
            text.lineSequence().count() >= MINIMUM_EXPECTED_LINES,
            "PosTseContent is only ${text.lineSequence().count()} lines, expected at least $MINIMUM_EXPECTED_LINES. " +
                "Either the file was gutted or this guard is reading the wrong one.",
        )
        return PosDialogScan.stripCommentsAndStrings(text)
    }

    /** Body of the single error effect, between its braces. */
    private fun errorEffectBody(code: String): String {
        val match =
            ERROR_EFFECT.find(code)
                ?: error(
                    "no `LaunchedEffect(uiState.error)` found in ${SOURCE.path}. The error surfacing was renamed " +
                        "or removed; re-point this guard at whatever now owns TseSettingsUiState.error.",
                )
        val open = code.indexOf('{', match.range.last)
        assertTrue(open >= 0, "the error effect in ${SOURCE.path} has no body block.")
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open + 1, i)
                }
            }
        }
        error("unbalanced braces in the error effect in ${SOURCE.path}.")
    }

    @Test
    fun `exactly one effect owns the TSE error slot`() {
        val readers = ERROR_EFFECT.findAll(code()).count()

        assertEquals(
            1,
            readers,
            "PosTseContent has $readers `LaunchedEffect(uiState.error)` effects; there must be exactly one.\n" +
                "TseSettingsUiState.error is a single slot written by the load, the config save and the export " +
                "family. When two effects read it, one failure enqueues two dialogs and they race on " +
                "clearError() — and if either clears unconditionally it blanks the inline ErrorState that renders " +
                "from the same slot. Both bugs were live before this rule.\n" +
                "Widen the existing effect's handling instead of adding another reader.",
        )
    }

    @Test
    fun `the one owner still shows a dialog carrying the typed error`() {
        val code = code()
        val body = errorEffectBody(code)

        assertEquals(
            1,
            SHOW_ERROR.findAll(code).count(),
            "PosTseContent must contain exactly one showError call — the single error owner's. A second one is " +
                "the two-dialog bug returning by another route.",
        )

        assertTrue(
            TYPED_ARGUMENT.containsMatchIn(body),
            "the TSE error effect no longer passes `error = …` to showError. Without the typed overload the " +
                "UiError never reaches the notification, so PosErrorDialog cannot render the traceId, the " +
                "server's detail or the error code, and ClientErrorReporter records nothing — the operator is " +
                "left with a generic sentence they cannot correlate with the server log.",
        )

        assertTrue(
            body.contains("DisplayMode.DIALOG"),
            "the TSE error effect no longer forces DisplayMode.DIALOG. The TSE is the fiscal signing device and " +
                "its failures must not be dismissible as a toast — a swiped-away failure can mean transactions " +
                "going unsigned or a missing DSFinV-K/TAR archive. Other screens deliberately let the bus decide; " +
                "this one does not.",
        )
    }

    @Test
    fun `the clear is conditional so the inline ErrorState survives`() {
        val code = code()
        val body = errorEffectBody(code)

        assertTrue(
            INLINE_ERROR_BRANCH.containsMatchIn(code),
            "PosTseContent no longer renders the inline ErrorState for `error != null && config == null`. That " +
                "branch is the screen's empty state when the initial load fails — a full-pane message with a " +
                "Retry that calls loadTseData(). If it is genuinely gone, the conditional clear below has nothing " +
                "left to protect and this guard should be revisited deliberately, not deleted in passing.",
        )

        assertEquals(
            1,
            CLEAR_ERROR.findAll(body).count(),
            "the TSE error effect must call clearError() exactly once.",
        )

        assertTrue(
            CONFIG_LOADED_GUARD.containsMatchIn(body),
            "the TSE error effect clears the error unconditionally. It must clear only when " +
                "`uiState.config != null`.\n" +
                "With config null the load failed, the screen has no content, and the inline ErrorState is " +
                "rendering from this very slot — clearing blanks the screen behind the dialog. That error is " +
                "ended by the ViewModel instead, which nulls it when a retry starts and when a load succeeds.",
        )
    }
}
