package org.example.project.core.config

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.example.project.core.domain.dto.salesinvoice.ArNumberingConfigDto
import org.example.project.core.domain.enums.datev.Kontenrahmen
import org.example.project.core.utils.datev.DatevAccountMappingDefaults
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.features.setup.database.tables.ShopConfigurationTable
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins the two reads `getDatevAccountMapping()` and `getArNumbering()` on the real manager before
 * they move out of it (Task 12): stored row decoded, no row, undecodable row.
 */
@DisplayName("ShopConfigurationManager: DATEV mapping and AR numbering reads")
class ShopConfigurationManagerDatevArTest {
    private fun store(
        key: ConfigKey,
        blob: JsonElement?,
    ) {
        transaction {
            val shopId =
                ShopInfo
                    .all()
                    .first()
                    .id.value
            if (blob == null) {
                ShopConfigurationTable.deleteWhere {
                    (ShopConfigurationTable.shopId eq shopId) and (ShopConfigurationTable.key eq key.key)
                }
            } else {
                ShopConfigurationTable.upsert {
                    it[ShopConfigurationTable.shopId] = shopId
                    it[ShopConfigurationTable.key] = key.key
                    it[ShopConfigurationTable.value] = blob
                    it[ShopConfigurationTable.description] = "characterisation"
                    it[ShopConfigurationTable.updatedAt] = null
                }
            }
        }
        runBlocking { ShopConfigurationManager.refreshConfiguration() }
    }

    private val defaultAr = ArNumberingConfigDto(prefix = "RG", startValue = 1, padding = 5)

    @Test
    fun `a stored DATEV mapping is decoded`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val seed = DatevAccountMappingDefaults.seed(Kontenrahmen.SKR04, emptyList())
            store(ConfigKey.DATEV_ACCOUNT_MAPPING, Json.encodeToJsonElement(seed))
            val read = assertNotNull(runBlocking { ShopConfigurationManager.getDatevAccountMapping() })
            assertEquals(Kontenrahmen.SKR04, read.kontenrahmen)
        }

    @Test
    fun `no stored DATEV mapping reads as null`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            store(ConfigKey.DATEV_ACCOUNT_MAPPING, null)
            assertNull(runBlocking { ShopConfigurationManager.getDatevAccountMapping() })
        }

    @Test
    fun `an undecodable DATEV mapping reads as null`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            store(ConfigKey.DATEV_ACCOUNT_MAPPING, buildJsonObject { put("kontenrahmen", "NOT_A_CHART") })
            assertNull(runBlocking { ShopConfigurationManager.getDatevAccountMapping() })
        }

    @Test
    fun `a stored AR numbering is decoded`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            store(ConfigKey.AR_NUMBERING, Json.encodeToJsonElement(ArNumberingConfigDto("AR", 42, 7)))
            assertEquals(ArNumberingConfigDto("AR", 42, 7), runBlocking { ShopConfigurationManager.getArNumbering() })
        }

    @Test
    fun `a missing AR numbering row falls back to the default`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            store(ConfigKey.AR_NUMBERING, null)
            assertEquals(defaultAr, runBlocking { ShopConfigurationManager.getArNumbering() })
        }

    @Test
    fun `an undecodable AR numbering row falls back to the default`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            store(ConfigKey.AR_NUMBERING, buildJsonObject { put("prefix", "X") })
            assertEquals(defaultAr, runBlocking { ShopConfigurationManager.getArNumbering() })
        }
}
