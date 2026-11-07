package org.example.project.admin.features.reports.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import org.example.project.admin.core.auth.accessPolicy
import org.example.project.admin.features.reports.presentation.CloseReadinessViewModel
import org.example.project.core.auth.AccessPolicy
import org.example.project.core.auth.TokenStore
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.presentation.strings.keys.MonthlyCloseKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.reports.presentation.ReportsGranularity
import org.example.project.ui.components.PosErrorWatcher
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * The month-close view of the period the Berichte screen is showing: closing KPIs, the readiness
 * checklist, what does not reconcile, and the Z-Bon / Kassenbuch roll-ups.
 *
 * Shown only to holders of `MONTHLY_CLOSE`, the capability `MonthlyCloseRules` puts on
 * `GET /api/v1/monthly-close`; everyone else who may open Berichte would otherwise be shown an error
 * they cannot act on (CLAUDE.md rule 8 — the server still enforces it). It used to be gated on
 * `CATALOG_MANAGE` (via `canManageProducts`), a leftover of the old "is a manager" check, so a group
 * holding one without the other saw the wrong answer (T5-5). See [showsMonthlyClose].
 *
 * Reloads on every period change: [granularity] and [anchor] are the LaunchedEffect keys, so the
 * block follows the stepper without the screen having to call the ViewModel.
 */
@Composable
fun MonthlyCloseBlock(
    granularity: ReportsGranularity,
    anchor: LocalDate,
    compareEnabled: Boolean,
    modifier: Modifier = Modifier,
    viewModel: CloseReadinessViewModel = koinViewModel(),
) {
    val tokenStore = koinInject<TokenStore>()
    if (!showsMonthlyClose(tokenStore.accessPolicy)) return

    LaunchedEffect(granularity, anchor) { viewModel.load(granularity, anchor) }

    val state = viewModel.state

    PosErrorWatcher(
        error = state.error,
        title = str(MonthlyCloseKeys.tabTitle),
        onClear = viewModel::clearError,
        retryAction = { viewModel.load(granularity, anchor) },
    )

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = str(MonthlyCloseKeys.tabTitle),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        if (state.isLoading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        val data = state.data ?: return@Column
        CloseKpiRow(data.kpis, currencyCode = data.currency, compareEnabled = compareEnabled)
        CloseChecklistSection(data.checklist, currencyCode = data.currency)
        CloseDiscrepanciesSection(data.checklist, currencyCode = data.currency)
        CloseZBonRollupSection(
            periodStart = data.periodStart,
            periodEnd = data.periodEnd,
            rollup = data.zBonRollup,
            currencyCode = data.currency,
        )
        CloseKassenbuchRollupSection(data.kassenbuchRollup, currencyCode = data.currency)
    }
}

/** Whether the month-close block is shown — exactly the server's gate on `GET /api/v1/monthly-close`. */
internal fun showsMonthlyClose(policy: AccessPolicy): Boolean = policy.allows(Capability.MONTHLY_CLOSE)
