package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testJson
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Round-3 STAM-6: LOC_LAND / stamm_abschluss.LAND must be a real ISO 3166 ALPHA-3 code.
 * Common German-language country names are recognized; an unrecognizable value FAILS the
 * export loudly instead of silently masquerading as DEU (which corrupts the master data).
 */
@DisplayName("DSFinV-K country-code mapping (round-3 STAM-6)")
class DsfinvkCountryCodeTest {
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    private fun seed(country: String) =
        transaction {
            val admin = TestData.createAdmin()
            val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo(shopCountry = country)
            shop.shopCountry = country
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
            val tws = TestData.createTableWithSession("CC1")
            val session = TableSession.findById(tws.session.id)!!
            Bill.new {
                this.employeeId = admin
                this.tableSession = session
                this.billNumber = "CC-1"
                this.billAmount = BigDecimal("10.00")
                this.paidAmount = BigDecimal("10.00")
                this.tips = BigDecimal.ZERO
                this.startTime = now
                this.endTime = now
                this.zBon = zBon
            }
        }

    private fun export(): ByteArray =
        runBlocking {
            service.generateExport(DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"))
        }

    private fun locLand(zip: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.substringAfterLast('/') == "location.csv") {
                    val lines =
                        zis
                            .readBytes()
                            .decodeToString()
                            .split("\r\n")
                            .filter { it.isNotBlank() }
                    val header = lines.first().split(";")
                    val idx = header.indexOfFirst { it.trim('"') == "LOC_LAND" }
                    return lines[1].split(";")[idx].trim('"')
                }
                entry = zis.nextEntry
            }
        }
        error("location.csv not found")
    }

    @Test
    fun `a recognized German country name maps to its ISO ALPHA-3 code`() {
        seed("Deutschland")
        assertEquals("DEU", locLand(export()))
    }

    @Test
    fun `an unrecognizable country value fails the export`() {
        seed("Narnia")
        val ex = assertFailsWith<PosException> { export() }
        assertTrue(
            ex.message!!.contains("Narnia") || ex.message!!.contains("recognizable"),
            "export must fail loudly on bad country master data; got: ${ex.message}",
        )
        Unit
    }

    /**
     * m16: the fall-through accepted "any three letters" as a valid alpha-3 code, so a typo like
     * "XYZ" was stamped verbatim onto LAND / LOC_LAND for every Kassenabschluss — indistinguishable
     * in the export from a real code, and never caught the way the 6-letter "Narnia" is. LAND must
     * be a genuine ISO 3166-1 alpha-3 country.
     */
    @Test
    fun `a three-letter non-country fails the export`() {
        seed("XYZ")
        val ex = assertFailsWith<PosException> { export() }
        assertTrue(
            ex.message!!.contains("XYZ") || ex.message!!.contains("recognizable"),
            "a 3-letter string that is not an ISO country must fail like any other bad value; " +
                "got: ${ex.message}",
        )
        Unit
    }

    /** A real, less-common alpha-3 the lookup table does not enumerate must still pass. */
    @Test
    fun `an uncommon but real alpha-3 code is accepted`() {
        seed("JPN")
        assertEquals("JPN", locLand(export()))
    }
}
