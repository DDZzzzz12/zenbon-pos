package org.example.project.features.dsfinvk

import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto.BusinessCaseTypes
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.kassenbuch.database.tables.KassenbuchHeaderTable
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.testutils.TippedDayFixture
import org.example.project.testutils.TippedDayFixture.Sale
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DSFinV-K invariant for tips paid out INSIDE the closing (F58), on a closing built by the real
 * services: **per closing, Σ businesscases `TrinkgeldAN` = employee tips received − tips paid out in
 * that closing**, at UST_SCHLUESSEL 5 only.
 *
 * The bills carry the tips in (`TrinkgeldAN` +), the in-closing payouts are Kassenbuch entries swept
 * into the same Z_NR and carry them out (`TrinkgeldAN` −), so the Kassenabschluss shows what the
 * closing still owes its staff. The rows are the two producers `generateExport` concatenates into
 * businesscases.csv — `generateBusinessCases` (bills) + `aggregateKassenbuchBusinessCases` (the
 * exportable Kassenbuch entries) — over that closing.
 *
 * A sibling of [DsfinvkComplianceGateTest]: that gate's closing harness stubs the PDF service an
 * Eigenbeleg needs, so the invariant runs here, through `POST /api/z-bon`.
 */
@DisplayName("DSFinV-K: Σ TrinkgeldAN per closing = tips received − paid out in the closing (F58)")
class DsfinvkTipPayoutInClosingTest {
    private val deviceId = "dsfinvk-tip-closing"
    private val fixture = TippedDayFixture(deviceId, "DTC")
    private val service =
        DsfinvkExportServiceImpl(
            json = Json { ignoreUnknownKeys = true },
            configRepository = ConfigurationRepository(),
            invoices = SalesInvoiceBillInvoiceLink(),
        )

    /** anna 3.00 cash + 2.00 card, bert 4.00 cash: 9.00 of employee tips. */
    private val day = listOf(Sale("anna", "3.00"), Sale("anna", "2.00", byCard = true), Sale("bert", "4.00"))

    @AfterEach
    fun resetBillNumberTracker() = BillNumberTracker.clearAll()

    @Test
    fun `everything paid out in the closing - TrinkgeldAN nets to zero in that closing`() =
        testApp(setupData = { fixture.seed(day) }) { client ->
            val zBon = close(client, mapOf("anna" to "5.00", "bert" to "4.00"))
            val rows = businessCases(zBon.id)
            assertTrinkgeldAn("0", rows)

            transaction {
                val payouts =
                    KassenbuchHeader
                        .find { KassenbuchHeaderTable.zBonId eq zBon.id }
                        .filter { !it.isZbonAggregate }
                val exported = service.generateKassenbuchLineItems(service.filterExportableKassenbuchEntries(payouts), listOf(ZBon.findById(zBon.id)!!), "K1")
                assertEquals(2, exported.size, "both payouts are Einzelaufzeichnungen of this closing")
                assertTrue(exported.all { it.gvTyp == BusinessCaseTypes.TRINKGELD_AN }, "$exported")
            }
        }

    /** Negative control: a partial payout leaves exactly the unpaid tips — the invariant is not vacuous. */
    @Test
    fun `negative control - a partial payout leaves the unpaid tips in TrinkgeldAN`() =
        testApp(setupData = { fixture.seed(day) }) { client ->
            val zBon = close(client, mapOf("anna" to "2.00"))
            assertTrinkgeldAn("7.00", businessCases(zBon.id))
        }

    /** And without any payout the closing shows the full 9.00 received — the pre-F58 picture. */
    @Test
    fun `negative control - no payout leaves every tip in TrinkgeldAN`() =
        testApp(setupData = { fixture.seed(day) }) { client ->
            val zBon = close(client, emptyMap())
            assertTrinkgeldAn("9.00", businessCases(zBon.id))
        }

    private suspend fun close(
        client: HttpClient,
        payouts: Map<String, String>,
    ): ZBonDto.ZBonResponseDto {
        val request =
            ZBonDto.CreateZBonRequestDto(
                tipPayouts =
                    payouts.map { (waiter, amount) ->
                        ZBonDto.CreateTipPayoutRequestDto(employeeId = fixture.waiters.getValue(waiter).toString(), amount = amount)
                    },
            )
        val response = fixture.closeWith(client, request)
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return testJson.decodeFromString(ZBonDto.ZBonResponseDto.serializer(), response.bodyAsText())
    }

    private fun businessCases(zBonId: Long): List<DsfinvkExportDto.ZGvTypRowDto> =
        transaction {
            val zBon = ZBon.findById(zBonId)!!
            val entries = KassenbuchHeader.find { KassenbuchHeaderTable.zBonId eq zBonId }.toList()
            service.generateBusinessCases(listOf(zBon), "K1") +
                service.aggregateKassenbuchBusinessCases(service.filterExportableKassenbuchEntries(entries), listOf(zBon), "K1")
        }

    private fun assertTrinkgeldAn(
        expected: String,
        rows: List<DsfinvkExportDto.ZGvTypRowDto>,
    ) {
        val tipRows = rows.filter { it.gvTyp == BusinessCaseTypes.TRINKGELD_AN }
        assertTrue(tipRows.isNotEmpty(), "the closing carries TrinkgeldAN rows: $rows")
        assertTrue(tipRows.all { it.ustSchluessel == 5 }, "TrinkgeldAN is nicht steuerbar: $tipRows")
        val sum = tipRows.sumOf { BigDecimal(it.zUmsBrutto.replace(',', '.')) }
        assertEquals(0, BigDecimal(expected).compareTo(sum), "Σ TrinkgeldAN = received − paid out; rows $tipRows")
    }
}
