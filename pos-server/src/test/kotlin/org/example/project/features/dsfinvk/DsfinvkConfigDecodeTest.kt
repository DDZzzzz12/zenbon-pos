package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.database.tables.ConfigKey
import org.example.project.features.setup.database.tables.ShopConfigurationTable
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testJson
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Round-3 STAM-5b: the KASSEN_MELDUNG_CONFIG holds the § 146a-Meldung identity and the KASSE_SERIENNR
 * used as every file's Z_KASSE_ID join key. An ABSENT config row is register-sanctioned — the export
 * may fall back to a fabricated "SN-<shopId>" serial. But a row that EXISTS yet cannot be decoded must
 * NOT silently swap in that fabricated serial (it would corrupt the fiscal identity); the export must
 * fail loudly instead.
 */
@DisplayName("DSFinV-K generateExport — Kassenmeldung config decode failure vs absent (STAM-5b)")
class DsfinvkConfigDecodeTest {
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    /** Seeds a minimal exportable closing (one ZBon + one paid Bill) against the given shop. */
    private fun seedClosing(shop: ShopInfo) {
        transaction {
            val admin = TestData.createAdmin()
            val tws = TestData.createTableWithSession("CFG1")
            val now = currentTimeStamp()

            val zBon =
                ZBon.new {
                    this.shopInfo = shop
                    this.employee = admin
                    this.startTime = now
                    this.endTime = now
                    this.cancelledAmount = BigDecimal.ZERO
                    this.totalDiscounts = BigDecimal.ZERO
                    this.totalPosRevenue = BigDecimal.ZERO
                    this.adminTip = BigDecimal.ZERO
                    this.openingBalance = BigDecimal.ZERO
                    this.expenses = BigDecimal.ZERO
                    this.cashRevenue = BigDecimal.ZERO
                    this.closingBalance = BigDecimal.ZERO
                    this.createdAt = now
                    this.trainingRevenue = BigDecimal.ZERO
                    this.trainingBillCount = 0
                    this.refundAmount = BigDecimal.ZERO
                    this.stornoBillCount = 0
                    this.zbonNummer = 1L
                }
            val session = TableSession.findById(tws.session.id)!!
            Bill.new {
                this.employeeId = admin
                this.tableSession = session
                this.billNumber = "CFG-1"
                this.billAmount = BigDecimal("10.00")
                this.paidAmount = BigDecimal("10.00")
                this.tips = BigDecimal.ZERO
                this.startTime = now
                this.endTime = now
                this.zBon = zBon
            }
        }
    }

    private val request = DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01")

    @Test
    fun `corrupt KASSEN_MELDUNG_CONFIG - generateExport fails loudly`() {
        val shop = transaction { TestData.createShopInfo(shopTaxNumber = "12345/67890", ustIdNr = null) }
        seedClosing(shop)
        // Seed a config ROW whose stored value cannot be decoded into KassenMeldungConfigDto.
        // getSettingAsString returns the raw "{not valid json" primitive content; decoding it throws.
        transaction {
            ShopConfigurationTable.insert {
                it[ShopConfigurationTable.shopId] = shop.id.value
                it[ShopConfigurationTable.key] = ConfigKey.KASSEN_MELDUNG_CONFIG.key
                it[ShopConfigurationTable.value] = JsonPrimitive("{not valid json")
            }
        }

        val ex =
            assertFailsWith<PosException> {
                runBlocking { service.generateExport(request) }
            }
        assertTrue(
            ex.message?.contains("cannot be decoded") == true &&
                ex.message?.contains("KASSE_SERIENNR") == true,
            "message must explain the config could not be decoded; got: ${ex.message}",
        )
    }

    @Test
    fun `absent KASSEN_MELDUNG_CONFIG - generateExport succeeds with SN fallback`() {
        val shop = transaction { TestData.createShopInfo(shopTaxNumber = "12345/67890", ustIdNr = null) }
        seedClosing(shop)
        // No config row seeded — the register-sanctioned SN-<shopId> fallback applies.

        val zip = runBlocking { service.generateExport(request) }
        assertTrue(zip.isNotEmpty(), "export ZIP must not be empty when config is simply absent")
    }

    @Test
    fun `KASSE_SERIENNR containing a slash or underscore fails the export (m13)`() {
        // Anhang E p. 71: „Aus technischen Gründen dürfen weder Slash („/") noch Unterstriche
        // („_") in der Seriennummer der Kasse verwendet werden." The same operator value is
        // emitted BOTH as Z_KASSE_ID — the join key across every file — and as KASSE_SERIENNR, so
        // a banned character corrupts every join in the package. The default generator is safe;
        // this guards the value the export actually emits, wherever it came from (a restored
        // hand-edited backup, a future edit endpoint, direct DB manipulation).
        val shop = transaction { TestData.createShopInfo(shopTaxNumber = "12345/67890", ustIdNr = null) }
        seedClosing(shop)
        transaction {
            ShopConfigurationTable.insert {
                it[ShopConfigurationTable.shopId] = shop.id.value
                it[ShopConfigurationTable.key] = ConfigKey.KASSEN_MELDUNG_CONFIG.key
                it[ShopConfigurationTable.value] =
                    testJson.parseToJsonElement("""{"kasseSeriennr":"POS_1/A"}""")
            }
        }

        val ex =
            assertFailsWith<PosException> {
                runBlocking { service.generateExport(request) }
            }
        assertTrue(
            ex.message?.contains("KASSE_SERIENNR") == true &&
                (ex.message?.contains("/") == true || ex.message?.contains("_") == true),
            "message must name KASSE_SERIENNR and the banned character; got: ${ex.message}",
        )
    }

    @Test
    fun `valid KASSEN_MELDUNG_CONFIG - generateExport succeeds`() {
        val shop = transaction { TestData.createShopInfo(shopTaxNumber = "12345/67890", ustIdNr = null) }
        seedClosing(shop)
        transaction {
            ShopConfigurationTable.insert {
                it[ShopConfigurationTable.shopId] = shop.id.value
                it[ShopConfigurationTable.key] = ConfigKey.KASSEN_MELDUNG_CONFIG.key
                it[ShopConfigurationTable.value] =
                    testJson.parseToJsonElement("""{"kasseSeriennr":"TSE-SERIAL-001"}""")
            }
        }

        val zip = runBlocking { service.generateExport(request) }
        assertTrue(zip.isNotEmpty(), "export ZIP must not be empty for a decodable config")
    }
}
