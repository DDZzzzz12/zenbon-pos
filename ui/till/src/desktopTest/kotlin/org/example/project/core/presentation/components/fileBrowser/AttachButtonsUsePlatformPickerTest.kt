package org.example.project.core.presentation.components.fileBrowser

import kotlin.test.Test
import kotlin.test.assertTrue

/** E2E-2026-09-18 #7: on Android the attach buttons must go through the SAF picker. */
class AttachButtonsUsePlatformPickerTest {
    private val dir = "src/commonMain/kotlin/org/example/project/features/financialdocuments/presentation/components"
    private val screens = listOf("CreateKassenbuchEntryPanel.kt", "CreateCorrectionDialog.kt", "DocumentUploadDialog.kt")

    @Test
    fun `every attach button asks the platform picker first`() {
        screens.forEach { name ->
            val text =
                java.io
                    .File(dir)
                    .resolve(name)
                    .readText()
            assertTrue(text.contains("rememberDocumentPickerLauncher("), "$name must create the platform picker")
            assertTrue(
                text.contains("?.launch() ?: run { showFileBrowser = true }"),
                "$name must launch the platform picker and fall back to FileBrowserDialog only where it is null",
            )
        }
    }
}
