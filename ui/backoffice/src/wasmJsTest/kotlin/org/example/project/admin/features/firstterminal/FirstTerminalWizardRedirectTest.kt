package org.example.project.admin.features.firstterminal

import org.example.project.admin.features.firstterminal.presentation.FirstTerminalSetupState
import org.example.project.admin.features.firstterminal.presentation.FirstTerminalStep
import org.example.project.admin.features.firstterminal.presentation.shouldLeaveWizardForTerminals
import org.example.project.core.domain.dto.terminal.TerminalDto
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirstTerminalWizardRedirectTest {
    private val created =
        TerminalDto.TerminalResponseDto(
            id = 1,
            deviceId = "",
            terminalId = "TER-001",
            terminalBrand = null,
            terminalModel = null,
            terminalSerialNr = null,
            terminalSwBrand = "POS",
            terminalSwVersion = null,
            isActive = true,
            tseClientRegistered = false,
            registeredAt = "2026-07-26T10:00:00",
            lastSeenAt = "2026-07-26T10:00:00",
            role = "CASHIER",
            fiscal = true,
        )

    @Test
    fun deepLinkOnServerWithTerminals_leaves() {
        val state = FirstTerminalSetupState(evaluated = true, needsFirstTerminal = false)
        assertTrue(state.shouldLeaveWizardForTerminals())
    }

    @Test
    fun beforeTheProbeAnswers_staysPut() {
        assertFalse(FirstTerminalSetupState(evaluated = false).shouldLeaveWizardForTerminals())
    }

    @Test
    fun emptyServer_staysPut() {
        val state = FirstTerminalSetupState(evaluated = true, needsFirstTerminal = true)
        assertFalse(state.shouldLeaveWizardForTerminals())
    }

    @Test
    fun midFlowAfterTheFirstTerminalWasCreated_staysPut() {
        // needsFirstTerminal is only cleared at DONE, but a refreshed probe could flip it earlier.
        val state =
            FirstTerminalSetupState(
                evaluated = true,
                needsFirstTerminal = false,
                step = FirstTerminalStep.ISSUE_CODE,
                createdTerminal = created,
            )
        assertFalse(state.shouldLeaveWizardForTerminals())
    }

    @Test
    fun doneStep_staysPut() {
        val state = FirstTerminalSetupState(evaluated = true, needsFirstTerminal = false, step = FirstTerminalStep.DONE)
        assertFalse(state.shouldLeaveWizardForTerminals())
    }

    @Test
    fun createdTerminalStillOnCreateStep_staysPut() {
        val state = FirstTerminalSetupState(evaluated = true, needsFirstTerminal = false, createdTerminal = created)
        assertFalse(state.shouldLeaveWizardForTerminals())
    }
}
