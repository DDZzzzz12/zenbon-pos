package org.example.project.features.dsfinvk

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.terminal.domain.models.Terminal
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testJson
import org.example.project.testutils.testKassenbuchDocuments
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.util.zip.ZipInputStream
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end regression guard for the Z-Bon aggregate exclusion (spec 2026-07-09 §3): drives the
 * REAL [DsfinvkExportServiceImpl.generateExport] (not a single consumer) and proves the aggregate
 * entry's `KB-<id>` BON_ID appears in NONE of the exported CSVs, while a manual entry's BON_ID
 * does. Pins the five-call-site wiring in `generateExport` against future regression.
 */
@DisplayName("DSFinV-K generateExport — aggregate exclusion (end-to-end)")
class DsfinvkExportEndToEndExclusionTest {
    private val service = DsfinvkExportServiceImpl(testJson, ConfigurationRepository(), SalesInvoiceBillInvoiceLink())

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
    }

    @AfterEach
    fun teardown() {
        TestDatabase.teardown()
    }

    @Test
    fun `generateExport emits no KB- rows for the aggregate entry but keeps the manual entry`() {
        val repository =
            org.example.project.features.kassenbuch.data.repository
                .KassenbuchRepositoryImpl(testKassenbuchDocuments())

        val ids =
            transaction {
                val admin = TestData.createAdmin()
                val shop = ShopInfo.all().firstOrNull() ?: TestData.createShopInfo()
                val now = currentTimeStamp()

                // generateExport requires at least one Z-Bon in the window (else noDataInRange).
                Terminal.new {
                    deviceId = "unknown"
                    clientId = "TERM-TEST"
                    registeredAt = now
                    lastSeenAt = now
                }
                ZBon.new {
                    this.shopInfo = shop
                    this.employee = admin
                    // Window must COVER the manual entry created below: since the closing-membership
                    // fix (audit H3/H4), Vorgänge outside every exported closing's window are
                    // excluded rather than exported with Z_NR=0. Use the full day so the entry's
                    // repo-stamped createdAt falls inside regardless of stamping precision.
                    this.startTime = LocalDateTime(now.year, now.month, now.day, 0, 0, 0)
                    this.endTime = LocalDateTime(now.year, now.month, now.day, 23, 59, 59)
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

                // The Z-Bon's own aggregate entry: a STANDARD + a NON_CASH_PAYMENT line, marked.
                val aggregate =
                    repository.createEntry(
                        shop = shop,
                        employee = admin,
                        headerData =
                            KassenbuchDto.KassenbuchData(
                                entryType = KassenbuchEntryType.CASH_SALE,
                                belegNr = "2026-07-0800",
                                vorzBetrag = "80.0",
                                belegDatum = "09.07.2026",
                                belegtext = "ZBon Nr. 1",
                                isZbonAggregate = true,
                            ),
                        lineItemsData =
                            listOf(
                                KassenbuchDto.LineItemData(vorzBetrag = "100.0", ustSatz = "19", kind = KassenbuchLineKind.STANDARD),
                                KassenbuchDto.LineItemData(vorzBetrag = "-20.0", ustSatz = null, kind = KassenbuchLineKind.NON_CASH_PAYMENT),
                            ),
                    )

                // A manual cash-expense entry that MUST still be exported.
                val manual =
                    repository.createEntry(
                        shop = shop,
                        employee = admin,
                        headerData =
                            KassenbuchDto.KassenbuchData(
                                entryType = KassenbuchEntryType.CASH_EXPENSE,
                                belegNr = "2026-07-0801",
                                vorzBetrag = "-10.0",
                                belegDatum = "09.07.2026",
                                belegtext = "Barausgabe",
                            ),
                        lineItemsData = listOf(KassenbuchDto.LineItemData(vorzBetrag = "-10.0", ustSatz = "19")),
                    )

                aggregate.id.value to manual.id.value
            }

        val (aggregateId, manualId) = ids
        val aggBonId = "KB-$aggregateId"
        val manBonId = "KB-$manualId"

        val zip =
            runBlocking {
                service.generateExport(
                    DsfinvkExportDto.ExportRequestDto(startDate = "2000-01-01", endDate = "2100-01-01"),
                )
            }
        assertTrue(zip.isNotEmpty(), "export ZIP must not be empty")

        // Concatenate every CSV in the export and scan across all of them at once so the
        // aggregate is proven absent from lines/transactions/transactions_tse/datapayment/etc.
        val allCsv = readAllCsv(zip)
        assertFalse(
            allCsv.contains(aggBonId),
            "aggregate BON_ID $aggBonId must not appear in ANY export CSV; found in:\n$allCsv",
        )
        assertTrue(
            allCsv.contains(manBonId),
            "manual entry BON_ID $manBonId must be present in the export; got:\n$allCsv",
        )
    }

    /** Concatenates the text of every CSV entry in the export ZIP. */
    private fun readAllCsv(zip: ByteArray): String {
        val sb = StringBuilder()
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.endsWith(".csv")) {
                    sb.append(entry.name).append('\n')
                    sb.append(zis.readBytes().decodeToString()).append('\n')
                }
                entry = zis.nextEntry
            }
        }
        return sb.toString()
    }
}
