package org.example.project.admin.features.settings.presentation.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import org.example.project.admin.core.auth.holds
import org.example.project.core.auth.TokenStore
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.presentation.strings.keys.CapabilityKeys
import org.example.project.core.presentation.strings.keys.RulesKeys
import org.example.project.core.presentation.strings.keys.SettingsKeys
import org.example.project.core.presentation.strings.str
import org.example.project.features.settings.presentation.state.EmployeeRotationItem
import org.example.project.features.settings.presentation.viewmodel.RulesViewModel
import org.example.project.ui.components.PosErrorWatcher
import org.example.project.ui.components.settings.PosSettingGroup
import org.example.project.ui.components.settings.PosSettingRow
import org.example.project.ui.components.settings.PosSettingsReadOnlyNotice
import org.example.project.ui.theme.LocalPosLayout
import org.example.project.ui.theme.PosSpacing
import org.example.project.ui.theme.posMaxContentWidth
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Geschäftsregeln — the section that answers "who may bill a table, and how do sessions rotate".
 *
 * Renders the same `ORDER_RULES` / `BILLING_RULES` toggles composeApp's BUSINESS tab showed
 * (removed with the till-only sweep), backed by the same shared-ui [RulesViewModel], so the till
 * and the web admin cannot drift on a rule that decides who is allowed to take money.
 *
 * The two order toggles are **mutually exclusive**: requiring an employee on a free table and
 * round-robin auto-assignment answer the same question two different ways, so each disables the
 * other. [RulesViewModel] also forces the other off when one is switched on — the `enabled` guards
 * here are the visible half of that rule, not decoration.
 *
 * The rotation pool only appears while round-robin is on, since it has no meaning otherwise, and is
 * read-only without the capability. The server enforces the same gate; this is the client half per
 * the project's two-level guard rule.
 *
 * Reference screen for the Zenbon settings pattern: groups of [PosSettingRow]s in
 * [PosSettingGroup] cards, every switch saving immediately (that is what [RulesViewModel] does), a
 * [PosSettingsReadOnlyNotice] instead of silently disabled controls, and layout spacing from
 * [LocalPosLayout].
 */
@Composable
fun BusinessRulesSection(viewModel: RulesViewModel = koinViewModel()) {
    LaunchedEffect(Unit) { viewModel.load() }

    val state by viewModel.state.collectAsState()
    val tokenStore = koinInject<TokenStore>()
    val canEdit = tokenStore.holds(Capability.CONFIG_WRITE)
    val layout = LocalPosLayout.current

    PosErrorWatcher(
        error = state.error,
        title = str(SettingsKeys.orderRulesSection),
        onClear = viewModel::clearError,
        retryAction = viewModel::load,
    )

    Column(
        modifier =
            Modifier
                .posMaxContentWidth()
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(layout.screenPadding),
        verticalArrangement = Arrangement.spacedBy(layout.paneGap),
    ) {
        if (!canEdit) PosSettingsReadOnlyNotice(capabilityName = str(CapabilityKeys.configWrite))

        PosSettingGroup(title = str(SettingsKeys.orderRulesSection)) {
            RuleToggle(
                title = str(SettingsKeys.employeeSelectionOnFreeTable),
                description = str(SettingsKeys.employeeSelectionOnFreeTableDescription),
                checked = state.employeeSelectionOnFreeTable,
                onCheckedChange = viewModel::setEmployeeSelectionOnFreeTable,
                enabled = canEdit && !state.isLoading && !state.roundRobinAssignment,
            )
            RuleToggle(
                title = str(RulesKeys.roundRobinAssignment),
                description = str(RulesKeys.roundRobinAssignmentDescription),
                checked = state.roundRobinAssignment,
                onCheckedChange = viewModel::setRoundRobinAssignment,
                enabled = canEdit && !state.isLoading && !state.employeeSelectionOnFreeTable,
            )
        }

        if (state.roundRobinAssignment) {
            RotationPool(
                employees = state.rotationPoolEmployees,
                canEdit = canEdit && tokenStore.holds(Capability.EMPLOYEE_WRITE),
                enabled = !state.isLoading,
                onToggle = viewModel::setRotationPool,
            )
        }

        PosSettingGroup(title = str(SettingsKeys.billingRulesSection)) {
            RuleToggle(
                title = str(SettingsKeys.allowCrossEmployeeBilling),
                description = str(SettingsKeys.allowCrossEmployeeBillingDescription),
                checked = state.allowCrossEmployeeBilling,
                onCheckedChange = viewModel::setAllowCrossEmployeeBilling,
                enabled = canEdit && !state.isLoading,
            )
        }
    }
}

@Composable
private fun RuleToggle(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
) {
    PosSettingRow(title = title, description = description) {
        // raw Material 3 by design: no Pos* switch component exists
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun RotationPool(
    employees: List<EmployeeRotationItem>,
    canEdit: Boolean,
    enabled: Boolean,
    onToggle: (String, Boolean) -> Unit,
) {
    PosSettingGroup(title = str(RulesKeys.rotationPoolHeader)) {
        Text(
            text = str(RulesKeys.rotationPoolDescription),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = PosSpacing.xl, vertical = PosSpacing.lg),
        )
        if (employees.isEmpty()) {
            PosSettingRow(title = str(RulesKeys.rotationPoolEmptyToast)) {}
        } else {
            employees.forEach { employee ->
                PosSettingRow(title = employee.displayName, description = employee.employeeNumber) {
                    // raw Material 3 by design: no Pos* switch component exists
                    Switch(
                        checked = employee.inRotationPool,
                        onCheckedChange = { onToggle(employee.id, it) },
                        enabled = canEdit && enabled && employee.isActive,
                    )
                }
            }
        }
    }
}
