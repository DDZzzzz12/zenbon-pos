package org.example.project.ui.components

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * `PosDialog` already scrolls its body — `Column(Modifier.weight(1f, fill = false).verticalScroll(…))`
 * in shared-ui's `PosDialog.kt`. A second vertically scrollable container inside that body
 * (`verticalScroll`, `LazyColumn`, `LazyVerticalGrid`) is measured with an infinite maximum height
 * and Compose throws
 * `IllegalStateException: Vertically scrollable component was measured with an infinity maximum
 * height constraints`. On the wasm till the frame is dropped and the dialog never opens: the TSE
 * commissioning dialog could not be used at all on the 2026-09-11 upgrade rehearsal, and the same
 * shape sat in the storno receipt panel, the decommission dialog, the customer dialog and both
 * reservation dialogs.
 *
 * Scanning source text rather than composing every dialog is deliberate: the failure is a
 * `String`-level pattern — somebody wrapped dialog content in its own scroll container — and the
 * three clients share the same `PosDialog`, so one scan covers them all.
 *
 * A `LazyColumn` with an explicit bounded height (`Modifier.height(…)`/`heightIn(max = …)`) is
 * safe; such a file goes into [allowed] with the reason.
 */
class NoNestedScrollInPosDialogTest {
    private val forbidden = listOf("verticalScroll(", "LazyColumn(", "LazyVerticalGrid(")

    /** Files whose nested container is bounded on purpose — name → why it cannot hit infinity. */
    private val allowed =
        mapOf(
            "PosTableLookupDialog.kt" to "LazyColumn under heightIn(max = 320.dp)",
            "VoucherDialog.kt" to "LazyVerticalGrid under heightIn(max = 300.dp)",
            "PaymentTypePickerDialog.kt" to "LazyVerticalGrid under heightIn(max = 400.dp)",
            "PosAddPrinterDialog.kt" to "LazyVerticalGrid and LazyColumn under heightIn(max = …)",
        )

    private fun uiFiles(): List<File> =
        listOf(
            File("src/commonMain/kotlin/org/example/project"),
            File("../shared/src/commonMain/kotlin/org/example/project"),
            File("../backoffice/src/wasmJsMain/kotlin/org/example/project"),
        ).filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            // The definition itself legitimately scrolls; it is the one place that may.
            .filterNot { it.name == "PosDialog.kt" }

    @Test
    fun `no PosDialog content nests a vertically scrollable container`() {
        val files = uiFiles()
        assertTrue(files.size > 100, "expected to find the clients' UI sources; found ${files.size}")

        val offenders =
            files
                .filterNot { it.name in allowed }
                .flatMap { file ->
                    val source = file.readText()
                    val code = PosDialogScan.stripCommentsAndStrings(source)
                    PosDialogScan.contentBodies(code).flatMap { body ->
                        forbidden
                            .flatMap { token -> PosDialogScan.indicesOf(code, token, body) }
                            .map { index -> "${file.name}:${PosDialogScan.lineOf(code, index)}: ${source.substring(index).lineSequence().first().trim()}" }
                    }
                }

        assertTrue(
            offenders.isEmpty(),
            "PosDialog already scrolls its body — drop the inner verticalScroll/LazyColumn (use a plain " +
                "Column, or bound the lazy list's height and add the file to `allowed` with the reason).\n" +
                offenders.joinToString("\n"),
        )
    }
}
