package org.example.project.features.kassenbuch

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.example.project.core.domain.dto.datev.DatevAccountMappingDto
import org.example.project.core.domain.enums.bill.TipRecipient
import org.example.project.core.domain.enums.datev.Kontenrahmen
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.core.utils.datev.DatevAccountMappingDefaults
import org.example.project.export.datevbuchungsstapel.model.BuchungsstapelRowInput
import org.example.project.features.bill.data.service.BillNumberTracker
import org.example.project.features.bill.domain.models.Bill
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.data.service.KassenbuchBuchungsstapelRowMapper
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TippedDayFixture
import org.example.project.testutils.TippedDayFixture.Sale
import org.example.project.testutils.testApp
import org.example.project.testutils.testKassenbuchDocuments
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import java.time.LocalDate as JavaLocalDate

/**
 * Where a day's employee tips (TrinkgeldAN) and their payout land in the DATEV Buchungsstapel.
 *
 * History: E2E-2026-09-15 N1 / plan 2026-09-16 P1.3a made a tipped day export at all — the tip line
 * keeps `ustSatz = "0"` — but it booked the tip on `income[STANDARD]["0"]`, 8100 (SKR03) / 4100
 * (SKR04), a REVENUE account, for money that is not turnover. F51 (2026-10) gives the line its own
 * kind, `EMPLOYEE_TIP`, resolved through `special[EMPLOYEE_TIP]` — 1590 / 1370, Durchlaufende Posten
 * — and the payout after the closing books the opposite leg on the same account, so it nets to zero.
 *
 * **The closing is created by the real service, not hand-seeded**, so the gate is coupled to what
 * `ZBonServiceImpl` actually writes. The pipeline (`getEntriesByDateRange` → `mapHeader`) is what
 * `DatevBuchungsstapelExportServiceImpl.generate` runs, minus the disk write and the export log. The
 * mapping is passed straight to the mapper: the `ShopConfigurationManager` cache is process-wide
 * and leaks between tests (the read-side backfill has its own test, DatevAccountMappingBackfillTest).
 */
@DisplayName("DATEV Buchungsstapel — employee tips book on Durchlaufende Posten, not revenue (F51)")
class DatevBuchungsstapelEmployeeTipTest {
    private val deviceId = "datev-tip-device"
    private val fixture = TippedDayFixture(deviceId, "DBT")

    private fun skr03(): DatevAccountMappingDto.MappingDto =
        DatevAccountMappingDefaults.seed(
            Kontenrahmen.SKR03,
            listOf(DatevAccountMappingDefaults.PaymentTypeSeed(fixture.cardPaymentTypeId, isVoucher = false)),
        )

    @AfterEach
    fun resetBillNumberTracker() = BillNumberTracker.clearAll()

    @Test
    fun `a cash tip books on 1590 (SKR03), not on revenue`() =
        testApp(setupData = { fixture.seed(listOf(Sale("anna", "3.00"))) }) { client ->
            assertFixtureIsAnEmployeeTip()
            fixture.close(client)

            transaction {
                val rows = exportPeriodRows(skr03())
                val tipRow = tipRow(rows, "TE:Trinkgeld (AN)")
                assertEquals(0, BigDecimal("3.00").compareTo(tipRow.umsatz), "the Trinkgeld row carries the full tip")
                assertEquals(1590, tipRow.gegenkonto, "SKR03 Durchlaufende Posten, not 8100 Erlöse")
                assertEquals(1000, tipRow.konto, "the cash side is the Kasse account")
                assertTrue(rows.none { it.gegenkonto == 8100 }, "nothing of a tipped day may book on the 0 % revenue account: $rows")
            }
        }

    @Test
    fun `a cash tip books on 1370 under SKR04`() =
        testApp(setupData = { fixture.seed(listOf(Sale("anna", "3.00"))) }) { client ->
            fixture.close(client)

            transaction {
                // The unmodified SKR04 seed: a cash-only closing's aggregate needs no payment-type
                // cell, and the seed's special slots are complete.
                val rows = exportPeriodRows(DatevAccountMappingDefaults.seed(Kontenrahmen.SKR04, emptyList()))
                val tipRow = tipRow(rows, "TE:Trinkgeld (AN)")
                assertEquals(1370, tipRow.gegenkonto)
                assertEquals(1600, tipRow.konto)
            }
        }

    /**
     * A card tip: the card payment carries the tip, so the NON_CASH_PAYMENT line nets out 23.00 while
     * revenue is 20.00 — the drawer is unchanged, and the 3.00 tip is held on 1590 until paid out in
     * cash from the drawer.
     */
    @Test
    fun `a card tip books on 1590 too, and the day nets to an unchanged drawer`() =
        testApp(setupData = { fixture.seed(listOf(Sale("anna", "3.00", byCard = true))) }) { client ->
            fixture.close(client)

            transaction {
                val rows = exportPeriodRows(skr03())
                val tipRow = tipRow(rows, "TE:Trinkgeld (AN)")
                assertEquals(0, BigDecimal("3.00").compareTo(tipRow.umsatz))
                assertEquals(1590, tipRow.gegenkonto)
                val cardRow = rows.single { it.umsatz.signum() < 0 }
                assertEquals(0, BigDecimal("-23.00").compareTo(cardRow.umsatz), "the card leg carries bill + tip")
                assertEquals(1360, cardRow.gegenkonto, "the card tender's Geldtransit cell")
                assertEquals(0, BigDecimal.ZERO.compareTo(rows.sumOf { it.umsatz }), "an all-card day moves no cash: $rows")
            }
        }

    /** The payout books the opposite leg on the same account: after it, 1590 holds nothing for that tip. */
    @Test
    fun `the tip payout books Kasse against 1590 and clears it`() =
        testApp(setupData = { fixture.seed(listOf(Sale("anna", "3.00", byCard = true))) }) { client ->
            val zBon = fixture.close(client)
            // F59: a card-only day leaves nothing in the drawer, and the Kassenminus guard refuses a
            // payout it cannot cover. Put 3.00 in first — a CASH_DEPOSIT, which books on its own
            // special account, never on 1590, so the 1590 assertions below are unaffected.
            fixture.fund(null, "3.00")
            val response = fixture.payOut(client, zBon.id, fixture.waiters.getValue("anna").toString(), "3.00")
            assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())

            transaction {
                val rows = exportPeriodRows(skr03())
                val payoutRow = tipRow(rows, "TA:Trinkgeld-Auszahlung")
                assertEquals(0, BigDecimal("-3.00").compareTo(payoutRow.umsatz), "the payout is cash OUT")
                assertEquals(1590, payoutRow.gegenkonto)
                assertEquals(1000, payoutRow.konto)
                assertEquals(
                    0,
                    BigDecimal.ZERO.compareTo(rows.filter { it.gegenkonto == 1590 }.sumOf { it.umsatz }),
                    "tip in and payout out net to zero on the employee-tip account: $rows",
                )
            }
        }

    /**
     * F58: tips paid out IN the closing book in the same day as the tips themselves, so a day whose
     * tips are all paid out leaves 1590 at zero — cash AND card tip (the drawer advances the card
     * part; it sits in 1360 until the card settlement).
     */
    @Test
    fun `tips paid out in the closing net 1590 to zero for the day`() =
        testApp(setupData = { fixture.seed(listOf(Sale("anna", "3.00"), Sale("anna", "2.00", byCard = true))) }) { client ->
            val anna = fixture.waiters.getValue("anna").toString()
            val response =
                fixture.closeWith(
                    client,
                    org.example.project.core.domain.dto.report.ZBonDto.CreateZBonRequestDto(
                        tipPayouts =
                            listOf(
                                org.example.project.core.domain.dto.report.ZBonDto
                                    .CreateTipPayoutRequestDto(employeeId = anna, amount = "5.00"),
                            ),
                    ),
                )
            assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())

            transaction {
                val rows = exportPeriodRows(skr03())
                assertEquals(0, BigDecimal("5.00").compareTo(tipRow(rows, "TE:Trinkgeld (AN)").umsatz), "cash + card tip credited")
                val payoutRow = tipRow(rows, "TA:Trinkgeld-Auszahlung")
                assertEquals(0, BigDecimal("-5.00").compareTo(payoutRow.umsatz), "the whole tip leaves the drawer")
                assertEquals(1590, payoutRow.gegenkonto)
                assertEquals(1000, payoutRow.konto)
                assertEquals(
                    0,
                    BigDecimal.ZERO.compareTo(rows.filter { it.gegenkonto == 1590 }.sumOf { it.umsatz }),
                    "everything paid out in the closing: 1590 nets to zero for the day: $rows",
                )
                assertEquals(
                    0,
                    BigDecimal("-22.00").compareTo(rows.filter { it.gegenkonto == 1360 }.sumOf { it.umsatz }),
                    "the card part (bill + tip) waits in 1360 for the settlement",
                )
            }
        }

    /** Negative control: a partial in-closing payout leaves exactly the unpaid tip on 1590. */
    @Test
    fun `negative control - a partial payout in the closing leaves the rest held on 1590`() =
        testApp(setupData = { fixture.seed(listOf(Sale("anna", "3.00"))) }) { client ->
            val anna = fixture.waiters.getValue("anna").toString()
            val response =
                fixture.closeWith(
                    client,
                    org.example.project.core.domain.dto.report.ZBonDto.CreateZBonRequestDto(
                        tipPayouts =
                            listOf(
                                org.example.project.core.domain.dto.report.ZBonDto
                                    .CreateTipPayoutRequestDto(employeeId = anna, amount = "1.00"),
                            ),
                    ),
                )
            assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())

            transaction {
                val rows = exportPeriodRows(skr03())
                assertEquals(0, BigDecimal("2.00").compareTo(rows.filter { it.gegenkonto == 1590 }.sumOf { it.umsatz }), "$rows")
            }
        }

    /** A storno of the payout books Kasse an 1590 again — the tip is held on the account until paid anew. */
    @Test
    fun `a payout storno books 1590 against Kasse, cash back in`() =
        testApp(setupData = { fixture.seed(listOf(Sale("anna", "3.00"))) }) { client ->
            val zBon = fixture.close(client)
            val payout =
                org.example.project.testutils.testJson.decodeFromString(
                    org.example.project.core.domain.dto.report.ZBonDto.TipPayoutResponseDto
                        .serializer(),
                    fixture.payOut(client, zBon.id, fixture.waiters.getValue("anna").toString(), "3.00").bodyAsText(),
                )
            assertEquals(HttpStatusCode.Created, fixture.reverse(client, zBon.id, payout.kassenbuchHeaderId!!).status)

            transaction {
                val rows = exportPeriodRows(skr03())
                val stornoRow = tipRow(rows, "Storno zu")
                assertEquals(0, BigDecimal("3.00").compareTo(stornoRow.umsatz), "the storno is cash IN")
                assertEquals(1590, stornoRow.gegenkonto)
                assertEquals(1000, stornoRow.konto)
                assertEquals(
                    0,
                    BigDecimal("3.00").compareTo(rows.filter { it.gegenkonto == 1590 }.sumOf { it.umsatz }),
                    "tip in + payout out + storno in leaves the tip held on 1590: $rows",
                )
            }
        }

    /** The pre-F51 invariant, kept: one tipped day must not cost the operator the period's export. */
    @Test
    fun `the tipped day's rows still sum to the takings actually booked`() =
        testApp(setupData = { fixture.seed(listOf(Sale("anna", "3.00"))) }) { client ->
            fixture.close(client)

            transaction {
                val rows = exportPeriodRows(skr03())
                assertEquals(
                    0,
                    BigDecimal("23.00").compareTo(rows.sumOf { it.umsatz }),
                    "20.00 turnover + 3.00 tip is what the drawer took and what must book; got ${rows.map { it.buchungstext to it.umsatz }}",
                )
            }
        }

    // ============================================
    // Helpers
    // ============================================

    private fun tipRow(
        rows: List<BuchungsstapelRowInput>,
        textPrefix: String,
    ): BuchungsstapelRowInput =
        assertNotNull(
            rows.singleOrNull { it.buchungstext.startsWith(textPrefix) },
            "expected exactly one '$textPrefix' row; got ${rows.map { it.buchungstext to it.umsatz }}",
        )

    /**
     * Runs the production Buchungsstapel pipeline over a window around today (the aggregate is
     * stamped with the current instant and `getEntriesByDateRange` filters on `createdAt`). A
     * [PosException] here is the defect this test exists to catch, so it is reported as such.
     */
    private fun exportPeriodRows(mapping: DatevAccountMappingDto.MappingDto): List<BuchungsstapelRowInput> {
        val shop = ShopInfo.all().first()
        val today = JavaLocalDate.now()
        val headers =
            KassenbuchRepositoryImpl(testKassenbuchDocuments()).getEntriesByDateRange(
                shop = shop,
                startDate = today.minusDays(1).toString(),
                endDate = today.plusDays(1).toString(),
            )
        val aggregate = headers.single { it.isZbonAggregate }
        assertTrue(
            aggregate.lineItems.any { it.kind == KassenbuchLineKind.EMPLOYEE_TIP },
            "fixture sanity: the closing must have written an EMPLOYEE_TIP line",
        )
        return try {
            headers.flatMap { KassenbuchBuchungsstapelRowMapper.mapHeader(it, mapping = mapping) }
        } catch (e: PosException) {
            fail("a fully configured shop must export a day with employee tips, but the export refused: ${e.message}")
        }
    }

    /** Only a TrinkgeldAN produces the line under test; an owner tip is folded into the item rates. */
    private fun assertFixtureIsAnEmployeeTip() {
        transaction {
            val bill = Bill.all().single()
            assertEquals(TipRecipient.EMPLOYEE, bill.tipRecipient, "fixture sanity: a MANAGER closer yields TrinkgeldAN")
            assertTrue(
                bill.vatDetails.none { it.vat.vatRate.compareTo(BigDecimal.ZERO) == 0 },
                "fixture sanity: after P1.1 the employee tip is NOT a 0 % VAT row",
            )
        }
    }
}
