package org.example.project.core.config

import org.example.project.core.domain.enums.setup.StartPageTarget
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebAccessSettingsTest {
    @Test
    fun `defaults are docs off and no start-page default`() =
        testApp(setupData = { TestData.createShopInfo() }) {
            TestData.clearWebAccessSettings()
            assertFalse(ShopConfigurationManager.isApiDocsSettingEnabled())
            assertEquals(StartPageTarget.NONE, ShopConfigurationManager.getStartPageDefault())
        }

    @Test
    fun `fresh-install defaults are pinned`() {
        assertEquals("false", ConfigKey.API_DOCS_ENABLED.defaultValue)
        assertEquals("none", ConfigKey.START_PAGE_DEFAULT.defaultValue)
    }

    @Test
    fun `stored values are read back`() =
        testApp(setupData = { TestData.createShopInfo() }) {
            try {
                TestData.setApiDocsEnabled(true)
                TestData.setStartPageDefault(StartPageTarget.ADMIN)
                assertTrue(ShopConfigurationManager.isApiDocsSettingEnabled())
                assertEquals(StartPageTarget.ADMIN, ShopConfigurationManager.getStartPageDefault())
            } finally {
                TestData.clearWebAccessSettings()
            }
        }
}
