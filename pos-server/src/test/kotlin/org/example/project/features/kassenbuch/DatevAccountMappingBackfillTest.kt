package org.example.project.features.kassenbuch

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.config.getDatevAccountMapping
import org.example.project.core.domain.dto.datev.DatevAccountMappingDto
import org.example.project.core.domain.enums.datev.KassenbuchSpecialBooking
import org.example.project.core.domain.enums.datev.Kontenrahmen
import org.example.project.core.utils.datev.DatevAccountMappingDefaults
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.data.service.DatevBuchungsstapelMappingGate
import org.example.project.features.kassenbuch.data.service.KassenbuchBuchungsstapelRowMapper
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.features.setup.database.tables.ShopConfigurationTable
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.TippedDayFixture
import org.example.project.testutils.TippedDayFixture.Sale
import org.example.project.testutils.testApp
import org.example.project.testutils.testKassenbuchDocuments
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A shop that saved its DATEV account mapping BEFORE `special[EMPLOYEE_TIP]` existed (F51) must get
 * the default on read, never a 409.
 *
 * `ShopConfigurationManager` decodes the stored settings blob verbatim: a Kotlin default fills a
 * missing FIELD, never a missing MAP KEY, so the stored map simply lacks the slot. Without the
 * read-side backfill such a mapping fails `validate()` (the settings screen refuses to save it) and
 * every tipped day's Buchungsstapel refuses with `DATEV_ACCOUNT_MAPPING_REQUIRED` — on a mapping the
 * operator completed.
 *
 * Each test writes its own stored blob and forces a refresh, because the configuration cache is
 * process-wide and leaks between tests.
 */
@DisplayName("DATEV account mapping: the employee-tip slot is backfilled into old stored mappings (F51)")
class DatevAccountMappingBackfillTest {
    /** A mapping exactly as a pre-F51 build stored it: the chart's seed without the new slot. */
    private fun preF51Blob(kontenrahmen: Kontenrahmen): JsonObject {
        val seed = DatevAccountMappingDefaults.seed(kontenrahmen, emptyList())
        val old = seed.copy(special = seed.special - KassenbuchSpecialBooking.EMPLOYEE_TIP)
        val blob = Json.encodeToJsonElement(old).jsonObject
        assertFalse(blob.toString().contains("EMPLOYEE_TIP"), "fixture sanity: the stored blob must lack the slot")
        return blob
    }

    private fun store(blob: JsonObject) {
        transaction {
            val shopId =
                ShopInfo
                    .all()
                    .first()
                    .id.value
            ShopConfigurationTable.upsert {
                it[ShopConfigurationTable.shopId] = shopId
                it[ShopConfigurationTable.key] = ConfigKey.DATEV_ACCOUNT_MAPPING.key
                it[ShopConfigurationTable.value] = blob
                it[ShopConfigurationTable.description] = "stored by a pre-F51 build"
                it[ShopConfigurationTable.updatedAt] = null
            }
        }
        runBlocking { ShopConfigurationManager.refreshConfiguration() }
    }

    private fun readMapping(): DatevAccountMappingDto.MappingDto =
        runBlocking { ShopConfigurationManager.getDatevAccountMapping() } ?: fail("the stored mapping did not decode")

    // The settings cache loads through Koin-wired services, so every read runs inside `testApp`.

    @Test
    fun `an SKR03 mapping stored without the slot reads back with 1590 and validates`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            store(preF51Blob(Kontenrahmen.SKR03))

            val mapping = readMapping()
            assertEquals(1590, mapping.special[KassenbuchSpecialBooking.EMPLOYEE_TIP]?.konto)
            assertEquals(emptyList(), mapping.validate(), "a backfilled mapping must be storable as-is")
        }

    @Test
    fun `an SKR04 mapping stored without the slot reads back with 1370`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            store(preF51Blob(Kontenrahmen.SKR04))

            assertEquals(1370, readMapping().special[KassenbuchSpecialBooking.EMPLOYEE_TIP]?.konto)
        }

    @Test
    fun `an operator's own employee-tip account is never overwritten`() =
        testApp(setupData = { TestData.createShopInfo() }) { _ ->
            val seed = DatevAccountMappingDefaults.seed(Kontenrahmen.SKR03, emptyList())
            val own =
                seed.copy(special = seed.special + (KassenbuchSpecialBooking.EMPLOYEE_TIP to DatevAccountMappingDto.AccountCell(1755)))
            store(Json.encodeToJsonElement(own).jsonObject)

            assertEquals(1755, readMapping().special[KassenbuchSpecialBooking.EMPLOYEE_TIP]?.konto)
        }

    /**
     * Negative control: the stored blob, decoded WITHOUT the backfill, is exactly the mapping that
     * fails — so the tests above pass because of the backfill, not because the slot was never needed.
     */
    @Test
    fun `negative control - the raw old blob fails validation on the missing slot`() {
        val raw = Json.decodeFromJsonElement(DatevAccountMappingDto.MappingDto.serializer(), preF51Blob(Kontenrahmen.SKR03))
        assertTrue(raw.validate().any { it.contains("special.EMPLOYEE_TIP") }, "got ${raw.validate()}")
    }

    /**
     * The end-to-end promise: a shop on an old stored mapping closes a tipped day and exports it
     * through the mapping the export actually reads — no 409, and the tip on 1590.
     */
    @Test
    fun `a tipped day exports through an old stored mapping without a 409`() {
        val fixture = TippedDayFixture("backfill-device", "BFL")
        try {
            exportTippedDay(fixture)
        } finally {
            BillNumberTracker.clearAll()
        }
    }

    private fun exportTippedDay(fixture: TippedDayFixture) =
        testApp(setupData = { fixture.seed(listOf(Sale("anna", "3.00"))) }) { client ->
            fixture.close(client)
            store(preF51Blob(Kontenrahmen.SKR03))

            transaction {
                val mapping = DatevBuchungsstapelMappingGate.requireConfigured(runBlocking { ShopConfigurationManager.getDatevAccountMapping() })
                val today = LocalDate.now()
                val headers =
                    KassenbuchRepositoryImpl(testKassenbuchDocuments()).getEntriesByDateRange(
                        shop = ShopInfo.all().first(),
                        startDate = today.minusDays(1).toString(),
                        endDate = today.plusDays(1).toString(),
                    )
                val rows =
                    try {
                        headers.flatMap { KassenbuchBuchungsstapelRowMapper.mapHeader(it, mapping = mapping) }
                    } catch (e: PosException) {
                        fail("an old stored mapping must not refuse a tipped day: ${e.message}")
                    }
                assertEquals(1590, rows.single { it.buchungstext.startsWith("TE:Trinkgeld") }.gegenkonto)
            }
        }
}
