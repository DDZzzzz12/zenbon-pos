package org.example.project.admin.features.auditlog.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.admin.core.presentation.components.AdminPageTitle
import org.example.project.admin.core.presentation.layout.adminListMaxWidth
import org.example.project.admin.core.presentation.strings.AdminLocale
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.core.presentation.strings.str
import org.example.project.core.utils.ProvideWindowSizeClass
import org.example.project.features.auditlog.presentation.screens.AuditLogContent

/**
 * Audit-Log rail destination.
 *
 * A thin wrapper over the shared content: it supplies the current language, which the shared
 * composable cannot resolve for itself, and provides the window size class.
 *
 * UI-FG-15: the page has a headline above the tabs like its Compliance siblings (none on a phone,
 * where the top bar shows it), and the whole page is capped at the list width so cards do not
 * stretch over 1100dp at 1400.
 *
 * [ProvideWindowSizeClass] is required because adminApp has no app-root provider — its screens
 * call `currentWindowAdaptiveInfo()` themselves — while shared-ui components read
 * `LocalWindowSizeClass`, a `staticCompositionLocalOf` that throws when unset. `SettingsScreen`
 * does the same for the same reason.
 */
@Composable
fun AuditLogDestination() {
    ProvideWindowSizeClass {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.fillMaxSize().adminListMaxWidth()) {
                AdminPageTitle(
                    text = str(AdminStringKeys.NavAuditLog),
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
                )
                AuditLogContent(
                    modifier = Modifier.fillMaxSize(),
                    language = AdminLocale.language,
                )
            }
        }
    }
}
