package org.example.project.features.dsfinvk

import kotlinx.serialization.json.Json
import org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto.BusinessCaseTypes
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.features.dsfinvk.data.service.DsfinvkExportServiceImpl
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.salesinvoice.data.service.SalesInvoiceBillInvoiceLink
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.zBon.domain.models.ZBon
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testKassenbuchDocuments
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * How an employee-tip payout (F51) leaves the register in the DSFinV-K export.
 *
 * DSFinV-K 2.4, Anhang C p. 52 (`docs/dsfinv_k_v_2_4/.../20231215_DSFinV_K_2_4.pdf`): „Im
 * Geschäftsvorfalltyp ‚TrinkgeldAN' werden alle kassenwirksamen Ein- und Auszahlungen von
 * Trinkgeldern an den Arbeitnehmer erfasst … Es ist möglich, mit diesem Geschäftsvorfalltyp sowohl
 * die Ein- als auch die Auszahlung an den Arbeitnehmer darzustellen." The payout is therefore
 * `TrinkgeldAN` with a NEGATIVE amount, which `KassenbuchTaxClassifier` pins to USt-Schlüssel 5
 * (nicht steuerbar) — never the `Auszahlung` its `CASH_WITHDRAWAL` header would otherwise give it,
 * which is rate-governed and would claim a VAT container for money that was never turnover.
 *
 * Every Kassenbuch producer is asserted on its own: bonpos (lines.csv), bonpos_ust (lines_vat.csv),
 * bonkopf_ust (transactions_vat.csv) and the Kassenabschluss businesscases.csv.
 */
@DisplayName("DSFinV-K: a tip payout exports as TrinkgeldAN, key 5, negative (F51)")
class DsfinvkEmployeeTipPayoutTest {
    private lateinit var repository: KassenbuchRepositoryImpl
    private lateinit var shop: ShopInfo
    private lateinit var employee: Employee
    private lateinit var zBon: ZBon
    private val service =
        DsfinvkExportServiceImpl(
            json = Json { ignoreUnknownKeys = true },
            configRepository = ConfigurationRepository(),
            invoices = SalesInvoiceBillInvoiceLink(),
        )

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        repository = KassenbuchRepositoryImpl(testKassenbuchDocuments())
        shop = TestData.createShopInfo()
        employee = TestData.createAdmin()
        zBon = TestData.createZBonFullDay(shop, employee)
    }

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    /** A 12.50 withdrawal as the payout books it: CASH_WITHDRAWAL, one line of [kind] at [rate]. */
    private fun withdrawal(
        kind: KassenbuchLineKind,
        rate: String?,
        belegNr: String,
    ): KassenbuchHeader =
        repository
            .createEntry(
                shop = shop,
                employee = employee,
                headerData =
                    KassenbuchDto.KassenbuchData(
                        entryType = KassenbuchEntryType.CASH_WITHDRAWAL,
                        belegNr = belegNr,
                        vorzBetrag = "-12.50",
                        belegDatum = "04.10.2026",
                        belegtext = "TA:Trinkgeld-Auszahlung Anna",
                    ),
                lineItemsData = listOf(KassenbuchDto.LineItemData(vorzBetrag = "-12.50", ustSatz = rate, kind = kind)),
            ).also { it.zBon = zBon }

    private fun dec(s: String) = BigDecimal(s.replace(',', '.'))

    @Test
    fun `the payout line is TrinkgeldAN in lines csv`() {
        transaction {
            val rows = service.generateKassenbuchLineItems(listOf(withdrawal(KassenbuchLineKind.EMPLOYEE_TIP, null, "2026-10-0001")), listOf(zBon), "K1")
            val row = rows.single()
            assertEquals(BusinessCaseTypes.TRINKGELD_AN, row.gvTyp)
            assertEquals("Trinkgeld (AN)", row.gvName, "one GV_NAME for TrinkgeldAN, the bill-side label (F54 #2)")
        }
    }

    @Test
    fun `businesscases books the payout at key 5 with no VAT, negative`() {
        transaction {
            val entry = withdrawal(KassenbuchLineKind.EMPLOYEE_TIP, null, "2026-10-0002")
            val row = service.aggregateKassenbuchBusinessCases(listOf(entry), listOf(zBon), "K1").single()
            assertEquals(BusinessCaseTypes.TRINKGELD_AN, row.gvTyp)
            assertEquals(5, row.ustSchluessel)
            assertEquals(0, BigDecimal("-12.50").compareTo(dec(row.zUmsBrutto)))
            assertEquals(0, BigDecimal.ZERO.compareTo(dec(row.zUst)))
        }
    }

    @Test
    fun `lines_vat and transactions_vat put the payout in key 5`() {
        transaction {
            val entry = withdrawal(KassenbuchLineKind.EMPLOYEE_TIP, null, "2026-10-0003")
            val lineVat = service.generateAvLineVats(emptyList(), emptyList(), listOf(entry), listOf(zBon), "K1").single()
            assertEquals(5, lineVat.ustSchluessel)

            val bonkopfUst = mutableListOf<org.example.project.core.domain.dto.dsfinvk.DsfinvkExportDto.BonkopfUstRowDto>()
            service.appendKassenbuchBonkopfUst(listOf(entry), listOf(zBon), "K1", bonkopfUst)
            val row = bonkopfUst.single()
            assertEquals(5, row.ustSchluessel)
        }
    }

    /** A declared rate cannot pull the payout into a VAT container: TrinkgeldAN discards it. */
    @Test
    fun `a declared rate on the payout line is discarded`() {
        transaction {
            val entry = withdrawal(KassenbuchLineKind.EMPLOYEE_TIP, "19", "2026-10-0004")
            val row = service.aggregateKassenbuchBusinessCases(listOf(entry), listOf(zBon), "K1").single()
            assertEquals(BusinessCaseTypes.TRINKGELD_AN, row.gvTyp)
            assertEquals(5, row.ustSchluessel)
        }
    }

    /** Negative control: the same withdrawal as a plain STANDARD 19 % line is an Auszahlung at key 1. */
    @Test
    fun `negative control - a STANDARD withdrawal stays Auszahlung at its declared rate`() {
        transaction {
            val entry = withdrawal(KassenbuchLineKind.STANDARD, "19", "2026-10-0005")
            val row = service.aggregateKassenbuchBusinessCases(listOf(entry), listOf(zBon), "K1").single()
            assertEquals(BusinessCaseTypes.AUSZAHLUNG, row.gvTyp)
            assertEquals(1, row.ustSchluessel)
        }
    }

    /**
     * The storno of a payout: CORRECTION_IN linked to the payout, EMPLOYEE_TIP +12.50. It stays
     * TrinkgeldAN with a POSITIVE amount at key 5, and nets the payout to zero in businesscases.
     */
    @Test
    fun `a payout storno is TrinkgeldAN positive at key 5 and nets the payout to zero`() {
        transaction {
            val payout = withdrawal(KassenbuchLineKind.EMPLOYEE_TIP, null, "2026-10-0006")
            val storno =
                repository
                    .createEntry(
                        shop = shop,
                        employee = employee,
                        headerData =
                            KassenbuchDto.KassenbuchData(
                                entryType = KassenbuchEntryType.CORRECTION_IN,
                                belegNr = "2026-10-0007",
                                vorzBetrag = "12.50",
                                belegDatum = "04.10.2026",
                                belegtext = "Storno zu 2026-10-0006",
                                correctsHeaderId = payout.id.value,
                                correctionRole = org.example.project.core.domain.enums.kassenbuch.CorrectionRole.REVERSAL,
                            ),
                        lineItemsData = listOf(KassenbuchDto.LineItemData(vorzBetrag = "12.50", ustSatz = null, kind = KassenbuchLineKind.EMPLOYEE_TIP)),
                    ).also { it.zBon = zBon }

            val line = service.generateKassenbuchLineItems(listOf(storno), listOf(zBon), "K1").single()
            assertEquals(BusinessCaseTypes.TRINKGELD_AN, line.gvTyp)

            val alone = service.aggregateKassenbuchBusinessCases(listOf(storno), listOf(zBon), "K1").single()
            assertEquals(5, alone.ustSchluessel)
            assertEquals(0, BigDecimal("12.50").compareTo(dec(alone.zUmsBrutto)), "the storno is positive")

            val pair = service.aggregateKassenbuchBusinessCases(listOf(payout, storno), listOf(zBon), "K1").single()
            assertEquals(BusinessCaseTypes.TRINKGELD_AN, pair.gvTyp)
            assertEquals(0, BigDecimal.ZERO.compareTo(dec(pair.zUmsBrutto)), "payout and storno net to zero")
        }
    }
}
