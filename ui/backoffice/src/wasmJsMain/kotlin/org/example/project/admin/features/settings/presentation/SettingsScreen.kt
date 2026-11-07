package org.example.project.admin.features.settings.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.example.project.admin.features.issuereport.presentation.IssueReportSettingsSection
import org.example.project.admin.features.settings.presentation.sections.BrowserStationsSection
import org.example.project.admin.features.settings.presentation.sections.BusinessRulesSection
import org.example.project.admin.features.settings.presentation.sections.DocumentStorageSection
import org.example.project.admin.features.settings.presentation.sections.PaymentTypesSection
import org.example.project.admin.features.settings.presentation.sections.SecuritySessionsSection
import org.example.project.admin.features.settings.presentation.sections.ShopDataSection
import org.example.project.admin.features.settings.presentation.sections.SystemSettingsSection
import org.example.project.admin.features.settings.presentation.sections.VatMappingSection
import org.example.project.admin.features.settings.presentation.sections.VatRatesSection
import org.example.project.features.changelog.presentation.screens.ChangelogScreen
import org.example.project.features.changelog.presentation.viewmodel.ChangelogViewModel
import org.example.project.ui.auth.LocalAccessPolicy
import org.example.project.ui.screens.settings.components.OpeningHoursSection
import org.example.project.ui.theme.LocalPosLayout
import org.koin.compose.viewmodel.koinViewModel

/**
 * The Einstellungen destination. [section] arrives from the route (`SettingsRoute.toSection()`), so
 * the sidebar's Einstellungen tree navigates here with the chosen section already selected.
 *
 * `ProvideWindowSizeClass` is no longer wrapped here — `AdminApp` provides it at the app root, which
 * is what this screen's own workaround was standing in for.
 */
@Composable
fun SettingsScreen(section: SettingsSection) {
    // A hidden section can still arrive by route (a stale back-stack entry); never compose it, so
    // its screen never issues the calls the server would refuse.
    if (!section.isVisibleTo(LocalAccessPolicy.current)) {
        BusinessRulesSection()
        return
    }
    when (section) {
        SettingsSection.BUSINESS_RULES -> BusinessRulesSection()
        SettingsSection.SHOP_DATA -> ShopDataSection()
        SettingsSection.VAT_RATES -> VatRatesSection()
        SettingsSection.VAT_MAPPING -> VatMappingSection()
        SettingsSection.PAYMENT_TYPES -> PaymentTypesSection()
        SettingsSection.DOCUMENT_STORAGE -> DocumentStorageSection()
        SettingsSection.BROWSER_STATIONS -> BrowserStationsSection()
        SettingsSection.SYSTEM -> SystemSettingsSection()
        // The shared component resolves its own OpeningHoursViewModel from Koin but is a plain
        // Column, so this destination owns the scroll — without it Fri–Sun, the overrides and the
        // save button sat below the fold at ~900 px with no way to reach them (admin19 FAIL-D).
        SettingsSection.OPENING_HOURS ->
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(LocalPosLayout.current.screenPadding),
            ) {
                OpeningHoursSection()
            }
        SettingsSection.SECURITY_SESSIONS -> SecuritySessionsSection()
        SettingsSection.ISSUE_REPORTS -> IssueReportSettingsSection()
        SettingsSection.CHANGELOG -> ChangelogScreen(viewModel = koinViewModel<ChangelogViewModel>())
    }
}
