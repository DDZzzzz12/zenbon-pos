package org.example.project.features.startpage

import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.enums.terminal.TerminalRole
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The settings live in the process-wide ShopConfigurationManager cache, so each test resets them. */
class WebEntryRegistryTest {
    private suspend fun reset() {
        TestData.clearWebAccessSettings()
        TestData.setBrowserStationRoles(emptySet())
    }

    private suspend fun posLabelFor(roles: Set<TerminalRole>): String? {
        TestData.setBrowserStationRoles(roles)
        return WebEntryRegistry
            .available(ShopConfigurationManager)
            .firstOrNull { it.id == WebEntryRegistry.POS.id }
            ?.labelKey
    }

    @Test
    fun `the pos label names the enabled roles`() =
        testApp(setupData = { TestData.createShopInfo() }) {
            try {
                assertNull(posLabelFor(emptySet()))
                assertEquals(StartPageStrings.POS_CASHIER, posLabelFor(setOf(TerminalRole.CASHIER)))
                assertEquals(StartPageStrings.POS_STATION, posLabelFor(setOf(TerminalRole.ORDER_STATION)))
                assertEquals(
                    StartPageStrings.POS_BOTH,
                    posLabelFor(setOf(TerminalRole.CASHIER, TerminalRole.ORDER_STATION)),
                )
            } finally {
                reset()
            }
        }

    @Test
    fun `api docs follow the setting`() =
        testApp(setupData = { TestData.createShopInfo() }) {
            try {
                reset()
                val hidden = WebEntryRegistry.available(ShopConfigurationManager).map { it.id }
                assertEquals(listOf("admin", "manual"), hidden)
                TestData.setApiDocsEnabled(true)
                val shown = WebEntryRegistry.available(ShopConfigurationManager).map { it.id }
                assertEquals(listOf("admin", "manual", "api_docs"), shown)
            } finally {
                reset()
            }
        }
}
