package org.example.project.core.config

import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.dbQuery
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `getBrowserStationRoles` is the enforcement input: whatever is stored, it must fail CLOSED
 * (an empty set) and never throw.
 */
class BrowserStationRolesParsingTest {
    private suspend fun store(raw: String): Set<TerminalRole> {
        TestData.setBrowserStationRoles(emptySet()) // ensures the shop row exists
        val shopId =
            transaction {
                ShopInfo
                    .all()
                    .limit(1)
                    .first()
                    .id.value
            }
        dbQuery { ConfigurationRepository().setSetting(shopId, ConfigKey.BROWSER_STATIONS, raw) }
        ShopConfigurationManager.refreshConfiguration()
        return ShopConfigurationManager.getBrowserStationRoles()
    }

    @Test
    fun `well-formed roles are returned`() =
        testApp {
            assertEquals(setOf(TerminalRole.CASHIER), store("""{"roles":["CASHIER"]}"""))
        }

    @Test
    fun `unknown and ineligible roles are dropped`() =
        testApp {
            assertEquals(setOf(TerminalRole.ORDER_STATION), store("""{"roles":["NOPE","ORDER_STATION","ORDER_DISPLAY"]}"""))
        }

    @Test
    fun `a non-object value is empty`() =
        testApp {
            assertEquals(emptySet(), store("""["CASHIER"]"""))
            assertEquals(emptySet(), store("\"CASHIER\""))
        }

    @Test
    fun `malformed json is empty`() =
        testApp {
            assertEquals(emptySet(), store("{roles: [CASHIER"))
        }

    @Test
    fun `a roles value that is not an array takes the exception path and is empty`() =
        testApp {
            assertEquals(emptySet(), store("""{"roles":"CASHIER"}"""))
            assertEquals(emptySet(), store("""{"roles":[{"a":1}]}"""))
        }
}
