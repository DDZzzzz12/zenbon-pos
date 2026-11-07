package org.example.project.admin.features.settings.presentation.sections

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.example.project.admin.core.auth.canConfigureSystem
import org.example.project.admin.core.presentation.strings.AdminLocale
import org.example.project.core.auth.TokenStore
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.core.utils.isCompact
import org.example.project.ui.layout.PaneBreakpoints
import org.example.project.ui.screens.settings.PosPrinterContent
import org.koin.compose.koinInject

/**
 * Einstellungen → Drucker.
 *
 * A thin wrapper over the shared content, supplying the current language. Printing itself is
 * server-side — the browser never talks to a printer — and IPP discovery goes through
 * `PrinterDiscoveryApiClient`, so nothing hardware-bound crosses to wasm.
 *
 * `AdminApp` provides [LocalWindowSizeClass] at the root, so no local provider is needed.
 *
 * Two different questions, two different measures. The page header is `isCompact` from the **window**
 * class: below 600dp the shell's top bar already names the page, from 600dp it does not. The layout
 * (phone cards + FAB + modal history, or cards beside the history panel) is decided from the width
 * this section actually **has**, which is what [PosPrinterContent]'s `compactLayout` is for.
 */
@Composable
fun PrinterSection() {
    // CONFIG_WRITE: what ConfigRules puts on PUT /api/v1/config/{shopId}/print-settings (admin20 FAIL-C20).
    val canEditPrintSettings = koinInject<TokenStore>().canConfigureSystem
    BoxWithConstraints(Modifier.fillMaxSize()) {
        PosPrinterContent(
            language = AdminLocale.language,
            isCompact = LocalWindowSizeClass.current.isCompact(),
            canEditPrintSettings = canEditPrintSettings,
            compactLayout = maxWidth < PaneBreakpoints.PHONE_MAX,
        )
    }
}
