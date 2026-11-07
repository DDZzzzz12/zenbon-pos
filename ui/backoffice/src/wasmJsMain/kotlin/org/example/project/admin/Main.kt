package org.example.project.admin

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import org.example.project.admin.core.data.LegacyStorageMigration
import org.example.project.admin.core.di.adminModule
import org.example.project.admin.core.presentation.strings.AdminTranslations
import org.koin.core.context.startKoin

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    AdminTranslations.init()
    // Before Koin, so the first TokenStore read already sees the adopted session (F52).
    LegacyStorageMigration().run()
    startKoin { modules(adminModule) }
    ComposeViewport(document.getElementById("ComposeTarget")!!) {
        AdminApp()
    }
}
