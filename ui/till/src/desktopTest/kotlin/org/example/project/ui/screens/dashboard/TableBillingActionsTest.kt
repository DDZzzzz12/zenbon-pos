package org.example.project.ui.screens.dashboard

import org.example.project.core.auth.BillingAuthority
import org.example.project.core.auth.TokenStore
import org.example.project.core.auth.isStation
import org.example.project.core.domain.dto.terminal.TerminalDto
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.testutils.fakes.FakeKeyValueStore
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Finding OS-b: with cross-employee billing enabled the order station's table panel offered neither
 * „Rechnung" nor „Rechnung anfordern", and with it disabled it offered „Rechnung anfordern" — the
 * decision read the station principal's employee number and the shop setting. The owner's decision:
 * nothing is billed under the station account, so its answer is fixed; a person's session — on any
 * device, the station's included (attribution, not device, 2026-09-28) — keeps the till's rule.
 */
class TableBillingActionsTest {
    private val none = TableBillingActions(bill = false, requestBill = false)

    /** `TokenStore.isStation` of a station session (`storeStationSession`). */
    private val station = true

    /** The panel's decision exactly as `PosTableDetailScreen` + `PosTableActionGrid` compose it. */
    private fun panel(
        isStation: Boolean,
        me: String?,
        owner: String?,
        crossEmployee: Boolean,
        canWriteBills: Boolean,
        canOverrideSession: Boolean = false,
    ): TableBillingActions =
        TableBillingActions.resolve(
            isStation = isStation,
            ownsBill = TableBillingActions.ownsBill(me, owner, crossEmployee),
            canWriteBills = canWriteBills,
            canOverrideSession = canOverrideSession,
        )

    @Test
    fun `an order station offers no billing action with cross-employee billing ON`() {
        // The station principal has an employee number; ON made it "own" every table (OS-b).
        assertEquals(none, panel(station, me = "T-DEV", owner = "W-7", crossEmployee = true, canWriteBills = false))
    }

    @Test
    fun `an order station offers no billing action with cross-employee billing OFF`() {
        // OFF used to leave „Rechnung anfordern" on the station.
        assertEquals(none, panel(station, me = "T-DEV", owner = "W-7", crossEmployee = false, canWriteBills = false))
    }

    @Test
    fun `a step-up at the station lending bill capabilities still offers nothing`() {
        for (cross in listOf(true, false)) {
            assertEquals(
                none,
                panel(station, me = "T-DEV", owner = "W-7", crossEmployee = cross, canWriteBills = true, canOverrideSession = true),
                "cross-employee=$cross",
            )
        }
    }

    @Test
    fun `the station marker agrees with the server's attribution`() {
        // The client asks the station marker (UI may not branch on a role — NoRoleGatesInScreensTest);
        // the server asks the attributed role. They agree because a station session is the ONLY
        // session registered under the station account, and a person's login clears the marker.
        val tokens = TokenStore(FakeKeyValueStore())
        tokens.storeStationSession(
            TerminalDto.StationSessionResponseDto(
                accessToken = "a",
                expiresAt = "2099-01-01T00:00:00Z",
                terminalId = "T-1",
                role = "ORDER_STATION",
                capabilities = emptySet(),
            ),
        )
        assertTrue(tokens.isStation)
        assertFalse(BillingAuthority.mayBillAs(EmployeeRole.getRole(tokens.role!!)), "the server refuses the station account")
        assertFalse(TableBillingActions.billingOffered(tokens.isStation))

        // A manager logs in on the same device: the bill is attributed to the manager.
        tokens.storeTokens(accessToken = "b", refreshToken = "r", expiresIn = 3600)
        tokens.role = EmployeeRole.MANAGER.name
        assertFalse(tokens.isStation, "a person's login clears the station marker")
        assertTrue(BillingAuthority.mayBillAs(EmployeeRole.getRole(tokens.role!!)))
        assertTrue(TableBillingActions.billingOffered(tokens.isStation), "a person's own login bills on any device")
    }

    @Test
    fun `till - own table bills whatever the setting`() {
        for (cross in listOf(true, false)) {
            assertEquals(
                TableBillingActions(bill = true, requestBill = false),
                panel(false, me = "W-7", owner = "W-7", crossEmployee = cross, canWriteBills = true),
                "cross-employee=$cross",
            )
        }
    }

    @Test
    fun `till - foreign table with cross-employee billing ON bills`() {
        assertEquals(
            TableBillingActions(bill = true, requestBill = false),
            panel(false, me = "W-7", owner = "W-9", crossEmployee = true, canWriteBills = true),
        )
    }

    @Test
    fun `till - foreign table with cross-employee billing OFF requests the bill`() {
        assertEquals(
            TableBillingActions(bill = false, requestBill = true),
            panel(false, me = "W-7", owner = "W-9", crossEmployee = false, canWriteBills = true),
        )
    }

    @Test
    fun `till - the session-override escape bills a foreign table and still offers the request`() {
        assertEquals(
            TableBillingActions(bill = true, requestBill = true),
            panel(false, me = "M-1", owner = "W-9", crossEmployee = false, canWriteBills = true, canOverrideSession = true),
        )
    }

    @Test
    fun `till - no employee number owns nothing`() {
        assertEquals(
            TableBillingActions(bill = false, requestBill = true),
            panel(false, me = null, owner = null, crossEmployee = true, canWriteBills = true),
        )
    }
}
