package org.example.project.admin.features.dashboard.presentation.checklist

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.settings.presentation.SettingsSection
import org.example.project.core.domain.dto.setup.SetupReadinessDto

/**
 * Maps server readiness onto the checklist rows.
 *
 * Returns **every applicable row with its state**, always. Deciding that an all-passing install
 * should render nothing belongs to the card, not here: the card also offers "Alle anzeigen", and a
 * builder that erased the passed rows would leave that toggle with nothing to show.
 *
 * A pure function on purpose: the interesting logic is the three-state TSE row and the restaurant-mode
 * omission, and both are worth testing without standing up Compose.
 *
 * Rows are ordered by dependency, then by how early an operator hits them — a terminal before the TSE
 * that needs it, master data before the products that reference it.
 *
 * @param canAdministerTse whether the signed-in operator may commission a TSE (ADMIN-only). The card
 *   renders for MANAGER+, so this is routinely false for someone who can still act on every other row.
 */
fun buildSetupChecklist(
    readiness: SetupReadinessDto,
    canAdministerTse: Boolean,
): List<SetupChecklistItem> {
    val rows =
        buildList {
            add(
                SetupChecklistItem(
                    id = "terminal",
                    state = readiness.hasFiscalTerminal.toState(),
                    labelKey = AdminStringKeys.ChecklistTerminalLabel,
                    recommendationKey = AdminStringKeys.ChecklistTerminalRecommendation,
                    destination = ChecklistDestination.TERMINALS,
                ),
            )

            // Blocked, not failed, while no fiscal terminal exists: commissioning needs a device to bind
            // the TSE client to, so this row points at Terminals rather than at a screen the operator
            // cannot yet act on.
            val blockedByTerminal = !readiness.tseCommissioned && !readiness.hasFiscalTerminal
            // Same treatment for a different reason: commissioning is ADMIN-only but this card renders
            // for MANAGER+, so offering a manager the click would send them to a gated route that
            // bounces straight back to the dashboard. Checked second, so a manager with no terminal
            // still gets pointed at Terminals — somewhere they genuinely can act.
            val blockedByRole = !readiness.tseCommissioned && !blockedByTerminal && !canAdministerTse
            add(
                SetupChecklistItem(
                    id = "tse",
                    state =
                        when {
                            readiness.tseCommissioned -> ChecklistState.PASSED
                            blockedByTerminal || blockedByRole -> ChecklistState.BLOCKED
                            else -> ChecklistState.FAILED
                        },
                    labelKey = AdminStringKeys.ChecklistTseLabel,
                    recommendationKey =
                        when {
                            blockedByTerminal -> AdminStringKeys.ChecklistTseBlockedRecommendation
                            blockedByRole -> AdminStringKeys.ChecklistTseRoleBlockedRecommendation
                            else -> AdminStringKeys.ChecklistTseRecommendation
                        },
                    destination =
                        when {
                            blockedByTerminal -> ChecklistDestination.TERMINALS
                            // Deliberately nowhere: the row stays visible with its state, but a manager is
                            // not offered a navigation they cannot follow.
                            blockedByRole -> null
                            else -> ChecklistDestination.TSE
                        },
                ),
            )

            // VAT and payment types are auto-seeded during setup, so in practice they pass from day one.
            // They are kept because an operator can delete either, and both are single booleans on data
            // already fetched — they cost nothing and catch a rare but total failure.
            add(
                SetupChecklistItem(
                    id = "vat",
                    state = readiness.hasVatRates.toState(),
                    labelKey = AdminStringKeys.ChecklistVatLabel,
                    recommendationKey = AdminStringKeys.ChecklistVatRecommendation,
                    destination = ChecklistDestination.SETTINGS,
                    settingsSection = SettingsSection.VAT_RATES,
                ),
            )

            add(
                SetupChecklistItem(
                    id = "paymentTypes",
                    state = readiness.hasPaymentTypes.toState(),
                    labelKey = AdminStringKeys.ChecklistPaymentTypesLabel,
                    recommendationKey = AdminStringKeys.ChecklistPaymentTypesRecommendation,
                    destination = ChecklistDestination.SETTINGS,
                    settingsSection = SettingsSection.PAYMENT_TYPES,
                ),
            )

            add(
                SetupChecklistItem(
                    id = "products",
                    state = (readiness.productCount > 0).toState(),
                    labelKey = AdminStringKeys.ChecklistProductsLabel,
                    recommendationKey = AdminStringKeys.ChecklistProductsRecommendation,
                    destination = ChecklistDestination.PRODUCTS,
                ),
            )

            // Omitted entirely rather than shown as failing when the shop is counter-service: a shop with
            // no tables should not be told it is missing them.
            if (readiness.restaurantMode) {
                add(
                    SetupChecklistItem(
                        id = "tables",
                        state = (readiness.tableCount > 0).toState(),
                        labelKey = AdminStringKeys.ChecklistTablesLabel,
                        recommendationKey = AdminStringKeys.ChecklistTablesRecommendation,
                        destination = ChecklistDestination.TABLES,
                    ),
                )
            }
        }

    return rows
}

private fun Boolean.toState(): ChecklistState = if (this) ChecklistState.PASSED else ChecklistState.FAILED
