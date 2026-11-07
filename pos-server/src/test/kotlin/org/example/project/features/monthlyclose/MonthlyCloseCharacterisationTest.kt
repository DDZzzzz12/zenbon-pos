package org.example.project.features.monthlyclose

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.example.project.core.config.IShopConfigurationManager
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.datevexportlog.DatevExportLogDto
import org.example.project.core.domain.dto.datevxml.DatevApExportDto
import org.example.project.core.domain.dto.monthlyclose.MonthlyCloseDto
import org.example.project.core.domain.dto.statistics.StatisticsDto
import org.example.project.core.domain.enums.bill.PaymentStatus
import org.example.project.core.domain.enums.setting.DatevAutoCadence
import org.example.project.core.domain.enums.setting.DatevExportSource
import org.example.project.features.bill.database.tables.BillVatDetailTable
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.datevexportlog.data.service.DatevExportLogServiceImpl
import org.example.project.features.datevexportlog.database.tables.DatevExportLogTable
import org.example.project.features.datevexportlog.domain.models.DatevExportType
import org.example.project.features.datevexportlog.domain.service.DatevExportLogService
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.service.DatevApExportServiceImpl
import org.example.project.features.kassenbuch.domain.service.DatevApExportService
import org.example.project.features.payment.database.tables.PaymentTable
import org.example.project.features.payment.domain.models.PaymentType
import org.example.project.features.payment.domain.models.Vat
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.statistics.data.service.StatisticsServiceImpl
import org.example.project.features.statistics.domain.service.StatisticsService
import org.example.project.features.table.domain.models.TableSession
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.testutils.TestAuthHelper
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.module.Module
import org.koin.dsl.module
import java.math.BigDecimal
import java.util.Collections
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime

/**
 * Pins what the month close (`GET /api/v1/monthly-close`) reads from the backoffice statistics and
 * the accounting DATEV services, and what it makes of it: the whole response JSON for a seeded June
 * (KPIs incl. the previous-period comparison, VAT and payment breakdown, Z-Bon roll-up, checklist,
 * DATEV export section) and the exact arguments of every statistics / export-log / AP-export call.
 *
 * Runs on the production Koin graph of `testApp`. The three upstream services are replaced by
 * recorders around the real implementations (Koin-resolved), so the test does not depend on how the
 * month close receives them. Only the AP export's invoice count can be forced, because seeding a
 * creditor invoice is not what this test is about; the month close only reads `totalInvoices`.
 */
@OptIn(ExperimentalTime::class)
@DisplayName("Month close statistics and DATEV call sites (characterisation)")
class MonthlyCloseCharacterisationTest {
    private val pretty = Json { prettyPrint = true }
    private val exporter: UUID = UUID.fromString("00000000-0000-0000-0000-00000000a701")

    private class Rig(
        val apCadence: DatevAutoCadence,
        val forcedApInvoices: Int?,
    ) {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    }

    @Test
    fun `a monthly close with the previous period produces the pinned response`() =
        scenario(Rig(DatevAutoCadence.OFF, forcedApInvoices = null)) { rig, client, managerId ->
            val body = monthlyClose(client, compare = true, cadence = "MONTHLY")
            assertEquals(EXPECTED_FULL.trim(), normalise(body))
            assertEquals(
                listOf(
                    "overview(2026-06-01T00:00, 2026-07-01T00:00, $managerId, filter=null, seesEveryone=true, training=false)",
                    "overview(2026-05-02T00:00, 2026-06-01T00:00, $managerId, filter=null, seesEveryone=true, training=false)",
                    "logs(2026-06-01T00:00, 2026-07-01T00:00, type=null, source=AUTO)",
                    "logs(2026-06-01T00:00, 2026-07-01T00:00, type=null, source=null)",
                ),
                rig.calls.toList(),
            )
        }

    @Test
    fun `without the comparison the previous period is neither read nor shown`() =
        scenario(Rig(DatevAutoCadence.OFF, forcedApInvoices = null)) { rig, client, managerId ->
            val dto = Json.decodeFromString<MonthlyCloseDto.MonthlyCloseResponseDto>(monthlyClose(client, compare = false, cadence = "MANUAL"))
            assertEquals(null, dto.previousPeriodStart)
            assertEquals(listOf(null, null, null, null, null, null, null), dto.kpis.map { it.previousValue })
            assertEquals(MonthlyCloseDto.ChecklistStatus.HIDDEN, dto.checklist.single { it.id == "datev_exported" }.status)
            assertEquals(
                listOf(
                    "overview(2026-06-01T00:00, 2026-07-01T00:00, $managerId, filter=null, seesEveryone=true, training=false)",
                    "logs(2026-06-01T00:00, 2026-07-01T00:00, type=null, source=AUTO)",
                    "logs(2026-06-01T00:00, 2026-07-01T00:00, type=null, source=null)",
                ),
                rig.calls.toList(),
            )
        }

    @Test
    fun `AP auto-export on and no creditor invoices - AP is asked, not required, the check passes`() =
        scenario(Rig(DatevAutoCadence.MONTHLY, forcedApInvoices = null)) { rig, client, _ ->
            val dto = Json.decodeFromString<MonthlyCloseDto.MonthlyCloseResponseDto>(monthlyClose(client, compare = false, cadence = "MONTHLY"))
            val item = dto.checklist.single { it.id == "datev_exported" }
            assertEquals(MonthlyCloseDto.ChecklistStatus.PASSED, item.status)
            assertEquals(null, item.detailCode)
            assertEquals(listOf("ap(2026-06-01, 2026-06-30, excludeKassenbuchLinked=true) -> 0"), rig.calls.filter { it.startsWith("ap(") })
        }

    @Test
    fun `AP auto-export on with creditor invoices - a MANUAL AP log does not cover the period`() =
        scenario(Rig(DatevAutoCadence.MONTHLY, forcedApInvoices = 3)) { rig, client, _ ->
            val dto = Json.decodeFromString<MonthlyCloseDto.MonthlyCloseResponseDto>(monthlyClose(client, compare = false, cadence = "MONTHLY"))
            val item = dto.checklist.single { it.id == "datev_exported" }
            assertEquals(MonthlyCloseDto.ChecklistStatus.FAILED, item.status)
            assertEquals("Missing full-period export for: AP_LEDGER", item.detail)
            assertEquals(listOf("AP_LEDGER"), item.detailArgs)
            // The display section still lists the MANUAL AP row and badges the period EXPORTED.
            assertEquals(MonthlyCloseDto.ExportBadgeStatus.EXPORTED, dto.exportStatus.badgeStatus)
            assertEquals(listOf("ap(2026-06-01, 2026-06-30, excludeKassenbuchLinked=true) -> 3"), rig.calls.filter { it.startsWith("ap(") })
        }

    // ── rig ──────────────────────────────────────────────────────────────────────────────────────

    private suspend fun monthlyClose(
        client: HttpClient,
        compare: Boolean,
        cadence: String,
    ): String {
        val token = TestAuthHelper.loginAs(client, "manager")
        val response =
            client.get(
                "/api/v1/monthly-close?periodStart=2026-06-01T00:00:00&periodEnd=2026-07-01T00:00:00" +
                    "&comparePreviousPeriod=$compare&cadence=$cadence",
            ) { bearerAuth(token) }
        val text = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, text)
        return text
    }

    private fun normalise(body: String): String = pretty.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(body))

    private fun scenario(
        rig: Rig,
        check: suspend (Rig, HttpClient, String) -> Unit,
    ) {
        var managerId = ""
        testApp(setupData = { managerId = seed().toString() }, overrides = listOf(recorders(rig))) { client -> check(rig, client, managerId) }
    }

    private fun recorders(rig: Rig): Module {
        val config =
            object : IShopConfigurationManager by ShopConfigurationManager {
                override suspend fun getDatevApLedgerCadence(): DatevAutoCadence = rig.apCadence
            }
        return module {
            single<IShopConfigurationManager> { config }
            single<StatisticsService> {
                RecordingStatistics(StatisticsServiceImpl(get(), get()), rig)
            }
            single<DatevExportLogService> {
                RecordingExportLog(DatevExportLogServiceImpl(get()), rig)
            }
            single<DatevApExportService> {
                RecordingAp(DatevApExportServiceImpl(get(), get(), get(), get(), get()), rig)
            }
        }
    }

    private class RecordingStatistics(
        private val real: StatisticsService,
        private val rig: Rig,
    ) : StatisticsService by real {
        override suspend fun getFinancialOverview(
            startDate: LocalDateTime,
            endDate: LocalDateTime,
            employeeId: String,
            filterEmployeeId: String?,
            seesEveryone: Boolean,
            trainingOnly: Boolean,
        ): StatisticsDto.FinancialOverviewResponseDto {
            rig.calls += "overview($startDate, $endDate, $employeeId, filter=$filterEmployeeId, seesEveryone=$seesEveryone, training=$trainingOnly)"
            return real.getFinancialOverview(startDate, endDate, employeeId, filterEmployeeId, seesEveryone, trainingOnly)
        }
    }

    private class RecordingExportLog(
        private val real: DatevExportLogService,
        private val rig: Rig,
    ) : DatevExportLogService by real {
        override suspend fun findOverlapping(
            periodStart: LocalDateTime,
            periodEnd: LocalDateTime,
            exportType: DatevExportType?,
            sourceFilter: DatevExportSource?,
        ): List<DatevExportLogDto> {
            rig.calls += "logs($periodStart, $periodEnd, type=$exportType, source=$sourceFilter)"
            return real.findOverlapping(periodStart, periodEnd, exportType, sourceFilter)
        }
    }

    private class RecordingAp(
        private val real: DatevApExportService,
        private val rig: Rig,
    ) : DatevApExportService by real {
        override suspend fun getApExportStatistics(
            startDate: String,
            endDate: String,
            excludeKassenbuchLinked: Boolean,
        ): DatevApExportDto.ApExportStatisticsDto {
            val stats = real.getApExportStatistics(startDate, endDate, excludeKassenbuchLinked)
            val answer = rig.forcedApInvoices?.let { stats.copy(totalInvoices = it) } ?: stats
            rig.calls += "ap($startDate, $endDate, excludeKassenbuchLinked=$excludeKassenbuchLinked) -> ${answer.totalInvoices}"
            return answer
        }
    }

    // ── seed ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * June 2026: a BAR sale at 19 % (11.90) on the 10th and a KARTE sale at 7 % (21.40) on the 12th, by a
     * cashier (the close is shop-wide); May: a BAR sale at 19 % (23.80) on the 20th. One June Z-Bon with
     * zero stored totals (so the VAT check fails with the bills' VAT). DATEV log: KASSENBUCH AUTO for
     * June, AP_LEDGER MANUAL for June, KASSENBUCH AUTO for May (touches the period start only).
     * Returns the manager's id.
     */
    private fun seed(): UUID {
        val shop = TestData.createShopInfo()
        val manager = TestData.createManager()
        val vats = TestData.createDefaultVatRates()
        val full = vats.first { it.vatRate == BigDecimal("0.1900") }
        val reduced = vats.first { it.vatRate == BigDecimal("0.0700") }
        val bar = TestData.createPaymentType(name = "BAR", isCash = true)
        val karte = TestData.createPaymentType(name = "KARTE", isCash = false)
        val session = TestData.createTableWithSession("MCC").session
        transaction {
            sale(session, "MCC-1", LocalDateTime(2026, 6, 10, 12, 0, 0), bar, full, "11.9000", "10.0000", "1.9000")
            sale(session, "MCC-2", LocalDateTime(2026, 6, 12, 18, 30, 0), karte, reduced, "21.4000", "20.0000", "1.4000")
            sale(session, "MCC-0", LocalDateTime(2026, 5, 20, 9, 15, 0), bar, full, "23.8000", "20.0000", "3.8000")
        }
        zBon(shop, manager, LocalDateTime(2026, 6, 1, 0, 0, 0), LocalDateTime(2026, 6, 30, 23, 0, 0))
        exportLog(DatevExportType.KASSENBUCH, LocalDateTime(2026, 6, 1, 0, 0, 0), LocalDateTime(2026, 7, 1, 0, 0, 0), "AUTO", 1)
        exportLog(DatevExportType.AP_LEDGER, LocalDateTime(2026, 6, 1, 0, 0, 0), LocalDateTime(2026, 7, 1, 0, 0, 0), "MANUAL", 2)
        exportLog(DatevExportType.KASSENBUCH, LocalDateTime(2026, 5, 1, 0, 0, 0), LocalDateTime(2026, 6, 1, 0, 0, 0), "AUTO", 3)
        return manager.id.value
    }

    private fun sale(
        session: TableSession,
        number: String,
        at: LocalDateTime,
        paymentType: PaymentType,
        vat: Vat,
        gross: String,
        net: String,
        tax: String,
    ) {
        val bill =
            Bill.new {
                this.employeeId = session.employeeId
                this.tableSession = session
                this.billNumber = number
                this.billAmount = BigDecimal(gross)
                this.paidAmount = BigDecimal(gross)
                this.startTime = at
                this.endTime = at
                this.isTraining = false
            }
        PaymentTable.insert {
            it[billId] = bill.id
            it[paymentTypeId] = paymentType.id
            it[amount] = BigDecimal(gross)
            it[paymentStatus] = PaymentStatus.COMPLETED
            it[paymentTime] = at
            it[currency] = "EUR"
        }
        BillVatDetailTable.insert {
            it[billId] = bill.id
            it[vatId] = vat.id
            it[netAmount] = BigDecimal(net)
            it[vatAmount] = BigDecimal(tax)
            it[totalAmount] = BigDecimal(gross)
        }
    }

    private fun zBon(
        shop: ShopInfo,
        employee: Employee,
        startTime: LocalDateTime,
        endTime: LocalDateTime,
    ) = transaction {
        ZBon.new {
            this.shopInfo = shop
            this.employee = employee
            this.startTime = startTime
            this.endTime = endTime
            this.cancelledAmount = BigDecimal.ZERO
            this.totalPosRevenue = BigDecimal.ZERO
            this.adminTip = BigDecimal.ZERO
            this.openingBalance = BigDecimal.ZERO
            this.expenses = BigDecimal.ZERO
            this.cashRevenue = BigDecimal.ZERO
            this.closingBalance = BigDecimal.ZERO
            this.createdAt = currentTimeStamp()
            this.zbonNummer = 1L
        }
    }

    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    private fun exportLog(
        type: DatevExportType,
        start: LocalDateTime,
        end: LocalDateTime,
        source: String,
        day: Int,
    ) = transaction {
        DatevExportLogTable.insert {
            it[exportType] = type.name
            it[periodStart] = start
            it[periodEnd] = end
            it[exportedAt] = LocalDateTime(2026, 7, day, 4, 0, 0)
            it[exportedByUserId] = kotlin.uuid.Uuid.parse(exporter.toString())
            it[fileHash] = "hash-${type.name}-$day"
            it[fileName] = "${type.name.lowercase()}-$day.xml"
            it[rowCount] = 10 * day
            it[entryCount] = day
            it[periodFolder] = "2026-0${start.month.ordinal + 1}"
            it[exportSource] = source
        }
    }

    private companion object {
        const val EXPECTED_FULL = """
{
    "periodStart": "2026-06-01T00:00",
    "periodEnd": "2026-07-01T00:00",
    "kpis": [
        {
            "key": "grossRevenue",
            "value": "33.30",
            "dailySeries": [
                "11.90",
                "21.40"
            ],
            "previousValue": "23.80",
            "deltaPercent": "+39.92"
        },
        {
            "key": "netRevenue",
            "value": "30.00",
            "dailySeries": [
                "10.00",
                "20.00"
            ],
            "previousValue": "20.00",
            "deltaPercent": "+50.00"
        },
        {
            "key": "vatTotal",
            "value": "3.30",
            "dailySeries": [
                "1.90",
                "1.40"
            ],
            "previousValue": "3.80",
            "deltaPercent": null
        },
        {
            "key": "billCount",
            "value": "2",
            "dailySeries": [
                "1",
                "1"
            ],
            "previousValue": "1",
            "deltaPercent": null
        },
        {
            "key": "refunds",
            "value": "0.00",
            "dailySeries": [],
            "previousValue": "0.00",
            "deltaPercent": null
        },
        {
            "key": "tips",
            "value": "0.00",
            "dailySeries": [],
            "previousValue": "0.00",
            "deltaPercent": null
        },
        {
            "key": "kassenbuchClosing",
            "value": "0.00",
            "dailySeries": [],
            "previousValue": null,
            "deltaPercent": null
        }
    ],
    "previousPeriodStart": "2026-05-02T00:00",
    "previousPeriodEnd": "2026-06-01T00:00",
    "vatBreakdown": {
        "Normal": {
            "vatRate": "19",
            "netAmount": "10.00",
            "vatAmount": "1.90",
            "totalAmount": "11.90",
            "transactionCount": 1
        },
        "Reduced": {
            "vatRate": "7",
            "netAmount": "20.00",
            "vatAmount": "1.40",
            "totalAmount": "21.40",
            "transactionCount": 1
        }
    },
    "paymentBreakdown": {
        "BAR": {
            "paymentMethod": "BAR",
            "paymentMethodEN": null,
            "paymentMethodCH": null,
            "totalAmount": "11.90",
            "transactionCount": 1,
            "revenuePercentage": "35.74",
            "averageTransactionValue": "11.90"
        },
        "KARTE": {
            "paymentMethod": "KARTE",
            "paymentMethodEN": null,
            "paymentMethodCH": null,
            "totalAmount": "21.40",
            "transactionCount": 1,
            "revenuePercentage": "64.26",
            "averageTransactionValue": "21.40"
        }
    },
    "zBonRollup": {
        "rows": [
            {
                "zBonId": 1,
                "zBonNumber": "1",
                "startTime": "2026-06-01T00:00",
                "endTime": "2026-06-30T23:00",
                "grossRevenue": "0.00",
                "netRevenue": "0.00",
                "vatTotal": "0.00",
                "tips": "0.00",
                "refunds": "0.00",
                "cancellations": "0.00",
                "paymentMethodTotals": [],
                "rebookStornos": "0.00",
                "revenueFromBills": "0.00",
                "storedRevenueDifference": "0.00"
            }
        ],
        "totals": {
            "zBonId": 0,
            "zBonNumber": "TOTAL",
            "startTime": "2026-06-01T00:00",
            "endTime": "2026-07-01T00:00",
            "grossRevenue": "0.00",
            "netRevenue": "0.00",
            "vatTotal": "0.00",
            "tips": "0.00",
            "refunds": "0.00",
            "cancellations": "0.00",
            "paymentMethodTotals": [],
            "rebookStornos": "0.00",
            "revenueFromBills": "0.00",
            "storedRevenueDifference": "0.00"
        },
        "chainGaps": []
    },
    "kassenbuchRollup": {
        "openingBalance": "0.00",
        "closingBalance": "0.00",
        "groups": []
    },
    "checklist": [
        {
            "id": "zbon_chain_integrity",
            "status": "PASSED",
            "detail": "",
            "affectedCount": 0,
            "jumpTo": "ZBON_ROLLUP",
            "detailCode": null,
            "detailArgs": []
        },
        {
            "id": "no_bills_outside_zbon",
            "status": "PASSED",
            "detail": "",
            "affectedCount": 0,
            "jumpTo": "DISCREPANCIES",
            "detailCode": null,
            "detailArgs": []
        },
        {
            "id": "vat_consistent",
            "status": "FAILED",
            "detail": "VAT delta = 3.30 (bills: 3.30, ZBon: 0.00). Note: per-rate cross-check skipped; rollup DTO carries only vatTotal aggregate",
            "affectedCount": 1,
            "jumpTo": "VAT",
            "detailCode": "vat_delta",
            "detailArgs": [
                "3.30",
                "3.30",
                "0.00"
            ]
        },
        {
            "id": "no_open_bills",
            "status": "FAILED",
            "detail": "2 open bill(s) not yet assigned to a Z-Bon",
            "affectedCount": 2,
            "jumpTo": "DISCREPANCIES",
            "detailCode": "open_bills",
            "detailArgs": [
                "2"
            ]
        },
        {
            "id": "all_tse_signed",
            "status": "FAILED",
            "detail": "2 bill(s) missing a valid TSE signature (KassenSichV)",
            "affectedCount": 2,
            "jumpTo": "DISCREPANCIES",
            "detailCode": "unsigned_bills",
            "detailArgs": [
                "2"
            ]
        },
        {
            "id": "no_simulated_signatures",
            "status": "PASSED",
            "detail": "",
            "affectedCount": 0,
            "jumpTo": "DISCREPANCIES",
            "detailCode": null,
            "detailArgs": []
        },
        {
            "id": "no_possible_double_sign",
            "status": "PASSED",
            "detail": "",
            "affectedCount": 0,
            "jumpTo": "DISCREPANCIES",
            "detailCode": null,
            "detailArgs": []
        },
        {
            "id": "tse_tanr_continuity",
            "status": "PASSED",
            "detail": "",
            "affectedCount": 0,
            "jumpTo": "DISCREPANCIES",
            "detailCode": null,
            "detailArgs": []
        },
        {
            "id": "all_cash_documented",
            "status": "PASSED",
            "detail": "",
            "affectedCount": 0,
            "jumpTo": "KASSENBUCH_ROLLUP",
            "detailCode": null,
            "detailArgs": []
        },
        {
            "id": "datev_exported",
            "status": "PASSED",
            "detail": "",
            "affectedCount": 0,
            "jumpTo": "DATEV_EXPORT",
            "detailCode": null,
            "detailArgs": []
        }
    ],
    "exportStatus": {
        "badgeStatus": "EXPORTED",
        "lastExportedAt": "2026-07-02T04:00",
        "log": [
            {
                "id": 2,
                "exportType": "AP_LEDGER",
                "periodStart": "2026-06-01T00:00",
                "periodEnd": "2026-07-01T00:00",
                "exportedAt": "2026-07-02T04:00",
                "exportedByUserId": "00000000-0000-0000-0000-00000000a701",
                "fileHash": "hash-AP_LEDGER-2",
                "fileName": "ap_ledger-2.xml",
                "rowCount": 20,
                "entryCount": 2,
                "periodFolder": "2026-06",
                "source": "MANUAL",
                "warnings": [],
                "unassignedContra": null
            },
            {
                "id": 1,
                "exportType": "KASSENBUCH",
                "periodStart": "2026-06-01T00:00",
                "periodEnd": "2026-07-01T00:00",
                "exportedAt": "2026-07-01T04:00",
                "exportedByUserId": "00000000-0000-0000-0000-00000000a701",
                "fileHash": "hash-KASSENBUCH-1",
                "fileName": "kassenbuch-1.xml",
                "rowCount": 10,
                "entryCount": 1,
                "periodFolder": "2026-06",
                "source": "AUTO",
                "warnings": [],
                "unassignedContra": null
            }
        ]
    },
    "currency": "EUR"
}
"""
    }
}
