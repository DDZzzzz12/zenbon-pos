package org.example.project.features.kassenbuch

import org.example.project.core.domain.enums.setup.TseMode
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.features.kassenbuch.domain.SigningTerminalEligibility
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SigningTerminalEligibilityTest {
    private lateinit var shop: ShopInfo

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        shop = TestData.createShopInfo()
    }

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    private fun till(
        role: TerminalRole = TerminalRole.CASHIER,
        active: Boolean = true,
        fiscal: Boolean = true,
        registered: Boolean = true,
        ofShop: ShopInfo? = shop,
    ): Terminal =
        transaction {
            val now = currentTimeStamp()
            Terminal.new {
                deviceId = "dev-${System.nanoTime()}"
                clientId = "TER-${System.nanoTime() % 100000}"
                name = "Kasse"
                isActive = active
                this.fiscal = fiscal
                tseClientRegistered = registered
                this.role = role.name
                shopInfo = ofShop
                registeredAt = now
                lastSeenAt = now
            }
        }

    private fun reason(t: Terminal) = transaction { SigningTerminalEligibility.reasonIneligible(t, shop, TseMode.NORMAL) }

    @Test
    fun `a fit CASHIER till of the shop is eligible`() = assertNull(reason(till()))

    @Test
    fun `an order station is not`() = assertEquals("not a CASHIER till", reason(till(role = TerminalRole.ORDER_STATION)))

    @Test
    fun `an inactive till is not`() = assertEquals("INACTIVE", reason(till(active = false)))

    @Test
    fun `a non-fiscal till is not`() = assertEquals("NOT_FISCAL", reason(till(fiscal = false)))

    @Test
    fun `an unregistered till is not`() = assertEquals("NOT_REGISTERED", reason(till(registered = false)))

    @Test
    fun `a till of another shop is not`() {
        val other = TestData.createShopInfo()
        assertEquals("belongs to another shop", reason(till(ofShop = other)))
    }

    @Test
    fun `a till without a shop is treated as the default shop's`() = assertNull(reason(till(ofShop = null)))
}
