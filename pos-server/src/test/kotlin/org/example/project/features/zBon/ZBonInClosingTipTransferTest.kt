package org.example.project.features.zBon

import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto.BusinessCaseTypes
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.enums.datev.Kontenrahmen
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.error.PosErrorCode
import org.example.project.core.utils.datev.DatevAccountMappingDefaults
import org.example.project.export.datevbuchungsstapel.model.BuchungsstapelRowInput
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.database.tables.BillTable
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.kassenbuch.data.service.KassenbuchBuchungsstapelRowMapper
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.zBon.database.tables.EmployeeTipPayoutTable
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.testutils.TippedDayFixture
import org.example.project.testutils.TippedDayFixture.Sale
import org.example.project.testutils.testApp
import org.example.project.testutils.testJson
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F58 follow-up: the daily Geldtransit and the drawer count when tips are paid out IN the closing.
 *
 * Day: anna 3.00 cash + 2.00 card, bert 4.00 cash — 47.00 of cash takings, 9.00 of employee tips. The
 * drawer starts at a float of 0.00 (first closing). With a Tresor as transfer target:
 *  - all tips paid in the closing → the safe receives 47.00 − 9.00 = 38.00, the drawer ends at 0.00;
 *  - no payout → the safe receives 47.00 and the drawer ends at 0.00 (the tips stay owed).
 */
@DisplayName("Z-Bon: Geldtransit and drawer count with in-closing tip payouts (F58)")
class ZBonInClosingTipTransferTest {
    private val deviceId = "tip-transfer-device"
    private val fixture = TippedDayFixture(deviceId, "TTR")
    private val day = listOf(Sale("anna", "3.00"), Sale("anna", "2.00", byCard = true), Sale("bert", "4.00"))

    @AfterEach
    fun resetBillNumberTracker() = BillNumberTracker.clearAll()

    private fun everything() =
        listOf(
            ZBonDto.CreateTipPayoutRequestDto(fixture.waiters.getValue("anna").toString(), "5.00"),
            ZBonDto.CreateTipPayoutRequestDto(fixture.waiters.getValue("bert").toString(), "4.00"),
        )

    @Test
    fun `the safe receives the takings minus the in-closing payouts and the drawer ends at its float`() =
        withSafe { client ->
            val zBon = closeOk(client, ZBonDto.CreateZBonRequestDto(tipPayouts = everything()))
            transaction {
                assertDecimal("-38.00", transferOut().totalAmount, "47.00 takings - 9.00 tips handed to staff")
                assertDecimal("38.00", transferIn().totalAmount, "the Tresor leg mirrors it")
                val closing = ZBon.findById(zBon.id)!!
                assertDecimal("0", closing.closingBalance, "the drawer ends at its float")
                assertDecimal(closing.openingBalance.toPlainString(), closing.closingBalance)
                assertDecimal("47.00", closing.cashRevenue, "takings unchanged")
                assertDecimal("-9.00", closing.expenses, "the payouts")
            }
        }

    /** Negative control: without payouts the transfer rule is untouched. */
    @Test
    fun `negative control - without payouts the safe receives all takings`() =
        withSafe { client ->
            val zBon = closeOk(client, ZBonDto.CreateZBonRequestDto())
            transaction {
                assertDecimal("-47.00", transferOut().totalAmount)
                assertDecimal("0", ZBon.findById(zBon.id)!!.closingBalance)
            }
        }

    /** A payout AFTER the closing is a withdrawal of the next period: this closing's transfer is unaffected. */
    @Test
    fun `a payout after the closing leaves that closing's transfer untouched`() =
        withSafe { client ->
            val zBon = closeOk(client, ZBonDto.CreateZBonRequestDto())
            // F59: the closing moved all 47.00 to the safe, so the drawer is at its 0.00 float; the
            // Kassenminus guard refuses a payout it cannot cover. Put 4.00 in first.
            fixture.fund(null, "4.00")
            assertEquals(HttpStatusCode.Created, fixture.payOut(client, zBon.id, fixture.waiters.getValue("bert").toString(), "4.00").status)
            transaction { assertDecimal("-47.00", transferOut().totalAmount) }
        }

    @Test
    fun `the Geldtransit exports as Geldtransit in DSFinV-K and on the same DATEV accounts, only the amount changes`() =
        withSafe { client ->
            val zBon = closeOk(client, ZBonDto.CreateZBonRequestDto(tipPayouts = everything()))
            val withPayouts = transaction { exportLeg(zBon.id) }
            assertDecimal("-38.00", withPayouts.first.toPlainString(), "DSFinV-K Geldtransit STK_BR")
            assertDecimal("-38.00", withPayouts.second.umsatz.toPlainString(), "DATEV amount")
            assertEquals(REFERENCE_DATEV_ACCOUNTS, withPayouts.second.konto to withPayouts.second.gegenkonto)
        }

    /** Reference run for the accounts: the transfer without payouts books on exactly the same pair. */
    @Test
    fun `negative control - the Geldtransit without payouts books on the same DATEV accounts`() =
        withSafe { client ->
            val zBon = closeOk(client, ZBonDto.CreateZBonRequestDto())
            val leg = transaction { exportLeg(zBon.id) }
            assertDecimal("-47.00", leg.first.toPlainString())
            assertEquals(REFERENCE_DATEV_ACCOUNTS, leg.second.konto to leg.second.gegenkonto)
        }

    /**
     * A surplus, not a shortage: with a float of 0.00 a shortage leaves less in the drawer than the
     * takings the transfer moves (pre-existing rule, unrelated to tips). A surplus is revenue, so it
     * also travels to the safe, as a counted difference always did.
     */
    @Test
    fun `the drawer count inside the closing compares against the drawer after the payouts`() =
        withSafe { client ->
            val zBon = closeOk(client, ZBonDto.CreateZBonRequestDto(countedAmount = "39.50", tipPayouts = everything()))
            transaction {
                val surplus = KassenbuchHeader.all().single { it.entryType == KassenbuchEntryType.CASH_SURPLUS }
                assertDecimal("1.50", surplus.totalAmount, "38.00 expected after the payouts, 39.50 counted")
                assertTrue(surplus.isLocked, "booked inside, and swept by, this closing")
                assertEquals(zBon.id, surplus.zBon?.id?.value)
                val aggregate = KassenbuchHeader.all().single { it.isZbonAggregate }
                assertTrue(surplus.belegNr > aggregate.belegNr, "booked after the aggregate and the payouts")
                assertTrue(surplus.belegNr < transferOut().belegNr, "and before the Geldtransit")
                assertTrue(surplus.linkedDocumentId != null, "with its Eigenbeleg")
                assertDecimal("-39.50", transferOut().totalAmount, "takings + surplus - tips")
                assertDecimal("0", ZBon.findById(zBon.id)!!.closingBalance, "the drawer still ends at its float")
            }
        }

    /**
     * The race: the dialog previewed the tips, then a table opened before confirm. The closing is
     * refused under its own lock — and since count, payouts and closing share one transaction, no
     * count, no payout and no difference survives it.
     */
    @Test
    fun `a table opened between preview and confirm refuses the closing and writes nothing`() =
        testApp(setupData = { fixture.seed(day) }) { client ->
            assertEquals(HttpStatusCode.OK, fixture.openPeriodTips(client).status, "the dialog's preview")
            val documents =
                transaction {
                    org.example.project.features.document.database.tables.DocumentTable
                        .selectAll()
                        .count()
                }
            fixture.leaveTableOpen()

            val response = fixture.closeWith(client, ZBonDto.CreateZBonRequestDto(countedAmount = "1.00", tipPayouts = everything()))
            assertEquals(HttpStatusCode.Conflict, response.status, response.bodyAsText())
            assertTrue(response.bodyAsText().contains(PosErrorCode.ZBON_TABLES_OPEN.name), response.bodyAsText())
            transaction {
                assertEquals(0, ZBon.all().count(), "no closing")
                assertEquals(0, KassenbuchHeader.all().count(), "no count difference, no payout, no aggregate")
                assertEquals(0, EmployeeTipPayoutTable.selectAll().count(), "no payout link")
                assertEquals(
                    documents,
                    org.example.project.features.document.database.tables.DocumentTable
                        .selectAll()
                        .count(),
                    "no Eigenbeleg",
                )
                assertEquals(3, BillTable.selectAll().count { it[BillTable.zBonId] == null })
            }
        }

    // ============================================
    // Helpers
    // ============================================

    private fun withSafe(block: suspend (HttpClient) -> Unit) =
        testApp(setupData = {
            fixture.seed(day)
            fixture.routeTakingsToSafe()
        }) { client ->
            try {
                block(client)
            } finally {
                fixture.stopRoutingToSafe()
            }
        }

    private suspend fun closeOk(
        client: HttpClient,
        request: ZBonDto.CreateZBonRequestDto,
    ): ZBonDto.ZBonResponseDto {
        val response = fixture.closeWith(client, request)
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return testJson.decodeFromString(ZBonDto.ZBonResponseDto.serializer(), response.bodyAsText())
    }

    private fun transferOut(): KassenbuchHeader = KassenbuchHeader.all().single { it.entryType == KassenbuchEntryType.CASH_TRANSFER_OUT }

    private fun transferIn(): KassenbuchHeader = KassenbuchHeader.all().single { it.entryType == KassenbuchEntryType.CASH_TRANSFER_IN }

    /** The fiscal Geldtransit leg as DSFinV-K (lines.csv STK_BR, quantity 1) and DATEV (its one row) export it. */
    private fun exportLeg(zBonId: Long): Pair<BigDecimal, BuchungsstapelRowInput> {
        val service =
            DsfinvkExportServiceImpl(
                json = Json { ignoreUnknownKeys = true },
                configRepository = ConfigurationRepository(),
                invoices = SalesInvoiceBillInvoiceLink(),
            )
        val leg = transferOut()
        val line = service.generateKassenbuchLineItems(listOf(leg), listOf(ZBon.findById(zBonId)!!), "K1").single()
        assertEquals(BusinessCaseTypes.GELDTRANSIT, line.gvTyp, "$line")
        val mapping = DatevAccountMappingDefaults.seed(Kontenrahmen.SKR03, emptyList())
        val row = KassenbuchBuchungsstapelRowMapper.mapHeader(leg, mapping = mapping).single()
        return BigDecimal(line.stkBr.replace(',', '.')) to row
    }

    private fun assertDecimal(
        expected: String,
        actual: BigDecimal,
        message: String? = null,
    ) = assertEquals(0, BigDecimal(expected).compareTo(actual), "${message ?: ""} expected $expected, got $actual")

    private fun assertDecimal(
        expected: String,
        actual: String,
        message: String? = null,
    ) = assertDecimal(expected, BigDecimal(actual), message)

    private companion object {
        /** Kasse 1000 against Geldtransit 1360 (SKR03) — the Ladenkasse leg's accounts, with or without payouts. */
        val REFERENCE_DATEV_ACCOUNTS: Pair<Int?, Int?> = 1000 to 1360
    }
}
