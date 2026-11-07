package org.example.project.core.domain.rules

import org.example.project.core.auth.PermissionManager
import org.example.project.core.auth.SettingManager
import org.example.project.core.auth.TokenStore
import org.example.project.core.data.models.RTable
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.rTable.TableStatus
import org.example.project.core.presentation.strings.keys.RulesKeys
import org.example.project.testutils.fakes.FakeKeyValueStore
import org.example.project.testutils.fakes.FakePermissionManager
import org.example.project.testutils.fakes.FakeTokenStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The till's half of two rules the server also enforces.
 *
 * Opening a free table is gated by [Capability.TABLE_WRITE] (order-station shell, 2026-09-06), and an
 * order station never owns a table session (2026-09-14): it opens a free table only through employee
 * rotation and never a takeaway or virtual one, while it may book onto any table that is already open.
 * A station denial carries the sentence to show; the older denials stay silent.
 *
 * The doubles are real objects over in-memory storage (the module has no interfaces here), so the
 * settings default to off unless a test turns round-robin on.
 */
class TableAccessRuleEngineTest {
    private fun engineFor(
        capabilities: Set<Capability>,
        stationRole: String? = null,
        roundRobin: Boolean = false,
        employeeSelection: Boolean = false,
    ): TableAccessRuleEngine {
        val tokenStore: TokenStore = FakeTokenStore.create()
        tokenStore.stationRole = stationRole
        val permissionManager: PermissionManager = FakePermissionManager.create()
        permissionManager.setReportedCapabilities(capabilities.map { it.name }.toSet())
        val settingManager = SettingManager(FakeKeyValueStore())
        settingManager.setRoundRobinAssignment(roundRobin)
        settingManager.setEmployeeSelectionOnFreeTable(employeeSelection)
        return TableAccessRuleEngine(
            settingManager = settingManager,
            permissionManager = permissionManager,
            tokenStore = tokenStore,
        )
    }

    private fun station(roundRobin: Boolean) = engineFor(setOf(Capability.TABLE_WRITE), stationRole = "ORDER_STATION", roundRobin = roundRobin)

    private fun table(
        status: TableStatus,
        isTakeaway: Boolean = false,
        isVirtual: Boolean = false,
        currentSessionId: Long? = null,
    ) = RTable(
        id = 1L,
        tableNumber = "1",
        seats = 4L,
        locationTypeId = 1L,
        locationTypeName = "Innen",
        isVirtual = isVirtual,
        isTakeaway = isTakeaway,
        tableStatus = status.name,
        currentSessionId = currentSessionId,
        gridX = 0,
        gridY = 0,
        rotation = 0f,
    )

    @Test
    fun `a station opens a free normal table when round-robin is on`() {
        assertEquals(TableAccessRuleEngine.AccessResult.Allowed, station(roundRobin = true).checkTableAccess(table(TableStatus.FREE)))
    }

    @Test
    fun `a station on a free normal table without round-robin is told it needs rotation`() {
        assertEquals(
            TableAccessRuleEngine.AccessResult.Denied(RulesKeys.stationTableRequiresRotation),
            station(roundRobin = false).checkTableAccess(table(TableStatus.FREE)),
        )
    }

    @Test
    fun `a station never opens a free takeaway table`() {
        assertEquals(
            TableAccessRuleEngine.AccessResult.Denied(RulesKeys.stationCannotOpenVirtualTable),
            station(roundRobin = true).checkTableAccess(table(TableStatus.FREE, isTakeaway = true)),
        )
    }

    @Test
    fun `a station never opens a free virtual table`() {
        assertEquals(
            TableAccessRuleEngine.AccessResult.Denied(RulesKeys.stationCannotOpenVirtualTable),
            station(roundRobin = true).checkTableAccess(table(TableStatus.FREE, isVirtual = true)),
        )
    }

    /**
     * The station branch runs before the employee-selection check, so a station is never asked to
     * pick an employee — turning that setting on must not change the rotation denial.
     */
    @Test
    fun `a station is never offered employee selection on a free table`() {
        val engine = engineFor(setOf(Capability.TABLE_WRITE), stationRole = "ORDER_STATION", roundRobin = false, employeeSelection = true)

        assertEquals(
            TableAccessRuleEngine.AccessResult.Denied(RulesKeys.stationTableRequiresRotation),
            engine.checkTableAccess(table(TableStatus.FREE)),
        )
    }

    /** A RESERVED table has no session either, so it gets the same station check as a FREE one. */
    @Test
    fun `a station cannot open a reserved takeaway table that has no session`() {
        assertEquals(
            TableAccessRuleEngine.AccessResult.Denied(RulesKeys.stationCannotOpenVirtualTable),
            station(roundRobin = true).checkTableAccess(table(TableStatus.RESERVED, isTakeaway = true)),
        )
    }

    /** Decision 5: an open session is always bookable, takeaway included, rotation or not. */
    @Test
    fun `a station books onto an occupied takeaway or normal table that already has a session`() {
        val engine = station(roundRobin = false)

        assertEquals(
            TableAccessRuleEngine.AccessResult.Allowed,
            engine.checkTableAccess(table(TableStatus.OCCUPIED, isTakeaway = true, currentSessionId = 42L)),
        )
        assertEquals(
            TableAccessRuleEngine.AccessResult.Allowed,
            engine.checkTableAccess(table(TableStatus.OCCUPIED, currentSessionId = 42L)),
        )
    }

    /** The station branch must not leak onto a person: a cashier still opens a free takeaway table. */
    @Test
    fun `a person holding TABLE_WRITE still opens a free takeaway table without round-robin`() {
        val engine = engineFor(setOf(Capability.TABLE_WRITE))

        assertEquals(TableAccessRuleEngine.AccessResult.Allowed, engine.checkTableAccess(table(TableStatus.FREE, isTakeaway = true)))
    }

    /** The capability, not the role, denies — silently, as before (no reason). */
    @Test
    fun `a principal without TABLE_WRITE may not open a free table`() {
        val engine = engineFor(setOf(Capability.TABLE_READ))

        assertEquals(TableAccessRuleEngine.AccessResult.Denied(), engine.checkTableAccess(table(TableStatus.FREE)))
    }

    @Test
    fun `an occupied table is not gated by TABLE_WRITE`() {
        val engine = engineFor(setOf(Capability.TABLE_READ))

        assertEquals(TableAccessRuleEngine.AccessResult.Allowed, engine.checkTableAccess(table(TableStatus.OCCUPIED)))
    }

    /** The station's no-session check must not leak onto a person opening a reserved table. */
    @Test
    fun `a person may still open a reserved takeaway table that has no session`() {
        val engine = engineFor(setOf(Capability.TABLE_WRITE))

        assertEquals(
            TableAccessRuleEngine.AccessResult.Allowed,
            engine.checkTableAccess(table(TableStatus.RESERVED, isTakeaway = true)),
        )
    }

    @Test
    fun `canSelectSelf is false for a station and true for a person`() {
        assertFalse(engineFor(setOf(Capability.TABLE_WRITE), stationRole = "ORDER_STATION").canSelectSelf())
        assertTrue(engineFor(setOf(Capability.TABLE_WRITE)).canSelectSelf())
    }
}
