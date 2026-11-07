package org.example.project.admin.features.dashboard.checklist

import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.dashboard.presentation.checklist.ChecklistDestination
import org.example.project.admin.features.dashboard.presentation.checklist.ChecklistState
import org.example.project.admin.features.dashboard.presentation.checklist.buildSetupChecklist
import org.example.project.admin.features.settings.presentation.SettingsSection
import org.example.project.core.domain.dto.setup.SetupReadinessDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Everything ready to trade — the baseline each test perturbs one field of. */
private fun ready() =
    SetupReadinessDto(
        hasFiscalTerminal = true,
        tseCommissioned = true,
        hasVatRates = true,
        hasPaymentTypes = true,
        productCount = 12,
        tableCount = 8,
        restaurantMode = true,
    )

/**
 * The role a test is speaking for. Only the TSE row reads it, so every other case runs as admin —
 * the role would be noise in those assertions.
 */
private const val ADMIN = true
private const val MANAGER = false

class SetupChecklistRowsTest {
    @Test
    fun allPassingStillReturnsEveryRowBecauseTheCardDecidesWhatToHide() {
        val rows = buildSetupChecklist(ready(), canAdministerTse = ADMIN)
        assertEquals(6, rows.size)
        assertTrue(rows.all { it.state == ChecklistState.PASSED })
    }

    @Test
    fun oneFailureStillShowsEveryRowSoProgressIsVisible() {
        // The point of the design: not a shrinking list of complaints.
        val rows = buildSetupChecklist(ready().copy(productCount = 0), canAdministerTse = ADMIN)
        assertEquals(6, rows.size)
        assertEquals(1, rows.count { it.state != ChecklistState.PASSED })
        assertEquals(ChecklistState.FAILED, rows.first { it.id == "products" }.state)
    }

    @Test
    fun tseIsBlockedAndRoutesToTerminalsWhenNoFiscalTerminalExists() {
        val rows =
            buildSetupChecklist(
                ready().copy(hasFiscalTerminal = false, tseCommissioned = false),
                canAdministerTse = ADMIN,
            )
        val tse = rows.first { it.id == "tse" }
        assertEquals(ChecklistState.BLOCKED, tse.state)
        // Sending the operator to a commissioning screen they cannot use is the wrong turn.
        assertEquals(ChecklistDestination.TERMINALS, tse.destination)
        assertEquals(AdminStringKeys.ChecklistTseBlockedRecommendation, tse.recommendationKey)
    }

    @Test
    fun theMissingTerminalStillOutranksTheRoleGateForAManager() {
        // Terminals is somewhere a manager genuinely can act, so the prerequisite keeps priority.
        val rows =
            buildSetupChecklist(
                ready().copy(hasFiscalTerminal = false, tseCommissioned = false),
                canAdministerTse = MANAGER,
            )
        val tse = rows.first { it.id == "tse" }
        assertEquals(ChecklistState.BLOCKED, tse.state)
        assertEquals(ChecklistDestination.TERMINALS, tse.destination)
        assertEquals(AdminStringKeys.ChecklistTseBlockedRecommendation, tse.recommendationKey)
    }

    @Test
    fun tseFailsAndRoutesToTseWhenAFiscalTerminalExists() {
        val rows = buildSetupChecklist(ready().copy(tseCommissioned = false), canAdministerTse = ADMIN)
        val tse = rows.first { it.id == "tse" }
        assertEquals(ChecklistState.FAILED, tse.state)
        assertEquals(ChecklistDestination.TSE, tse.destination)
        assertEquals(AdminStringKeys.ChecklistTseRecommendation, tse.recommendationKey)
    }

    @Test
    fun aManagerGetsAnUnclickableTseRowBecauseCommissioningIsAdminOnly() {
        // The card renders for MANAGER+, but the TSE route is ADMIN-gated: offering the click would
        // bounce them straight back to the dashboard.
        val rows = buildSetupChecklist(ready().copy(tseCommissioned = false), canAdministerTse = MANAGER)
        val tse = rows.first { it.id == "tse" }
        assertEquals(ChecklistState.BLOCKED, tse.state)
        assertNull(tse.destination)
        assertEquals(AdminStringKeys.ChecklistTseRoleBlockedRecommendation, tse.recommendationKey)
    }

    @Test
    fun aManagerStillSeesTheTseRowRatherThanHavingItHidden() {
        // A missing TSE blocks trading, so hiding the row would be worse than showing it unclickable.
        val rows = buildSetupChecklist(ready().copy(tseCommissioned = false), canAdministerTse = MANAGER)
        assertEquals(6, rows.size)
        assertTrue(rows.any { it.id == "tse" })
    }

    @Test
    fun theRoleNeverAffectsARowOtherThanTse() {
        val readiness = ready().copy(tseCommissioned = false)
        val asAdmin = buildSetupChecklist(readiness, canAdministerTse = ADMIN).filterNot { it.id == "tse" }
        val asManager = buildSetupChecklist(readiness, canAdministerTse = MANAGER).filterNot { it.id == "tse" }
        assertEquals(asAdmin, asManager)
    }

    @Test
    fun aCommissionedTsePassesForAManagerToo() {
        // No dead-end to avoid once it is done — and a passed row is never clickable anyway.
        val rows = buildSetupChecklist(ready(), canAdministerTse = MANAGER)
        assertEquals(6, rows.size)
        assertTrue(rows.all { it.state == ChecklistState.PASSED })
        assertEquals(ChecklistState.PASSED, rows.first { it.id == "tse" }.state)
    }

    @Test
    fun theTableRowIsOmittedEntirelyForACounterServiceShop() {
        val rows =
            buildSetupChecklist(
                ready().copy(restaurantMode = false, tableCount = 0, productCount = 0),
                canAdministerTse = ADMIN,
            )
        assertEquals(5, rows.size)
        assertNull(rows.firstOrNull { it.id == "tables" })
    }

    @Test
    fun aRestaurantWithNoTablesFailsThatRow() {
        val rows = buildSetupChecklist(ready().copy(tableCount = 0), canAdministerTse = ADMIN)
        assertEquals(ChecklistState.FAILED, rows.first { it.id == "tables" }.state)
    }

    @Test
    fun eachIndividualFailureProducesItsOwnFailingRow() {
        val cases =
            listOf(
                "terminal" to ready().copy(hasFiscalTerminal = false),
                "vat" to ready().copy(hasVatRates = false),
                "paymentTypes" to ready().copy(hasPaymentTypes = false),
                "products" to ready().copy(productCount = 0),
                "tables" to ready().copy(tableCount = 0),
            )
        cases.forEach { (id, readiness) ->
            val row = buildSetupChecklist(readiness, canAdministerTse = ADMIN).first { it.id == id }
            assertTrue(row.state != ChecklistState.PASSED, "$id should not pass")
        }
    }

    @Test
    fun settingsRowsCarryTheSectionTheyNavigateTo() {
        val rows =
            buildSetupChecklist(
                ready().copy(hasVatRates = false, hasPaymentTypes = false),
                canAdministerTse = ADMIN,
            )
        assertEquals(SettingsSection.VAT_RATES, rows.first { it.id == "vat" }.settingsSection)
        assertEquals(SettingsSection.PAYMENT_TYPES, rows.first { it.id == "paymentTypes" }.settingsSection)
    }

    @Test
    fun rowsComeBackInDependencyOrder() {
        // A terminal before the TSE that needs it; master data before the products referencing it.
        val ids = buildSetupChecklist(ready().copy(productCount = 0), canAdministerTse = ADMIN).map { it.id }
        assertEquals(listOf("terminal", "tse", "vat", "paymentTypes", "products", "tables"), ids)
    }
}
