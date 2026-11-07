package org.example.project.admin.features.settings.presentation.sections

import androidx.compose.runtime.Composable
import org.example.project.admin.core.auth.canAdministerSessions
import org.example.project.admin.core.presentation.strings.AdminLocale
import org.example.project.core.auth.TokenStore
import org.example.project.core.utils.LocalWindowSizeClass
import org.example.project.core.utils.isCompact
import org.example.project.ui.screens.settings.PosSecuritySessionsContent
import org.koin.compose.koinInject

/**
 * Einstellungen → Sicherheit & Sitzungen.
 *
 * A thin wrapper over the shared content: it supplies the two things the shared composable cannot
 * resolve for itself on wasm — the current language, and whether the signed-in user is an ADMIN
 * (the till reads that from `LocalPermissionManager`, which is SQLDelight-backed).
 *
 * `isCompact` follows the window class (the same `isCompact()` that swaps the permanent sidebar for
 * the drawer): on a phone the top bar already names the section, so the shared content's own header
 * is dropped; wider windows keep it.
 */
@Composable
fun SecuritySessionsSection() {
    val tokenStore = koinInject<TokenStore>()
    PosSecuritySessionsContent(
        language = AdminLocale.language,
        isCompact = LocalWindowSizeClass.current.isCompact(),
        isAdmin = tokenStore.canAdministerSessions,
    )
}
