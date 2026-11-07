package org.example.project.admin.features.terminal

import org.example.project.admin.features.terminal.TerminalTestFixtures.summary
import org.example.project.admin.features.terminal.presentation.TerminalPanelAction
import org.example.project.admin.features.terminal.presentation.TerminalPanelPlan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TerminalPanelPlanTest {
    @Test
    fun aMissingTseClientMakesRegistrationThePrimaryAction() {
        val plan = TerminalPanelPlan.of(summary(1, tse = false, fiscal = true))
        assertEquals(TerminalPanelAction.REGISTER_TSE, plan.primary)
        assertEquals(listOf(TerminalPanelAction.GENERATE_CODE, TerminalPanelAction.EDIT), plan.secondary)
        assertEquals(listOf(TerminalPanelAction.DEACTIVATE, TerminalPanelAction.REVOKE_BINDING), plan.decommission)
    }

    @Test
    fun aRegisteredActiveTerminalLeadsWithEditAndHasNoFilledButton() {
        val plan = TerminalPanelPlan.of(summary(1, tse = true))
        assertNull(plan.primary)
        assertEquals(listOf(TerminalPanelAction.EDIT, TerminalPanelAction.GENERATE_CODE), plan.secondary)
        assertEquals(
            listOf(TerminalPanelAction.DEACTIVATE, TerminalPanelAction.REVOKE_BINDING, TerminalPanelAction.DEREGISTER_TSE),
            plan.decommission,
        )
    }

    @Test
    fun aNonFiscalTerminalNeverOffersTseRegistration() {
        val plan = TerminalPanelPlan.of(summary(1, tse = false, fiscal = false))
        assertNull(plan.primary)
        assertEquals(listOf(TerminalPanelAction.EDIT, TerminalPanelAction.GENERATE_CODE), plan.secondary)
    }

    @Test
    fun aDeactivatedTerminalLeadsWithReactivateAndCannotBeDeactivatedAgain() {
        val plan = TerminalPanelPlan.of(summary(1, active = false, tse = true))
        assertEquals(TerminalPanelAction.REACTIVATE, plan.primary)
        assertEquals(listOf(TerminalPanelAction.EDIT), plan.secondary)
        assertEquals(listOf(TerminalPanelAction.REVOKE_BINDING, TerminalPanelAction.DEREGISTER_TSE), plan.decommission)
    }

    @Test
    fun aDeactivatedTerminalStillMissingItsTseKeepsTheRegistrationAction() {
        val plan = TerminalPanelPlan.of(summary(1, active = false, tse = false, fiscal = true))
        assertEquals(TerminalPanelAction.REACTIVATE, plan.primary)
        assertEquals(listOf(TerminalPanelAction.REGISTER_TSE, TerminalPanelAction.EDIT), plan.secondary)
    }
}
