package org.example.project.features.kassenbuch

import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.datev.Kontenrahmen
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.core.error.PosErrorCode
import org.example.project.core.utils.datev.DatevAccountMappingDefaults
import org.example.project.export.datevbuchungsstapel.DatevBuchungsstapelCsvWriter.Cell
import org.example.project.export.datevbuchungsstapel.DatevBuchungsstapelRowGenerator
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.data.service.DatevBuchungsstapelMappingGate
import org.example.project.features.kassenbuch.data.service.KassenbuchBuchungsstapelRowMapper
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testKassenbuchDocuments
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KassenbuchBuchungsstapelMappingTest {
    private lateinit var repository: KassenbuchRepositoryImpl
    private lateinit var shop: ShopInfo
    private lateinit var employee: Employee
    private val mapping = DatevAccountMappingDefaults.seed(Kontenrahmen.SKR03, emptyList())

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        repository = KassenbuchRepositoryImpl(testKassenbuchDocuments())
        shop = TestData.createShopInfo()
        employee = TestData.createAdmin()
    }

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    /** docs/datev/metro.pdf: goods 19/7 % positive, Warengutschein 19/7 % negative, cash 30,56. */
    private fun metroHeader(): KassenbuchHeader =
        repository.createEntry(
            shop = shop,
            employee = employee,
            headerData =
                KassenbuchDto.KassenbuchData(
                    entryType = KassenbuchEntryType.CASH_EXPENSE,
                    belegNr = "2026-07-0100",
                    vorzBetrag = "-30.56",
                    belegDatum = "07.07.2026",
                    belegtext = "Metro Wareneinkauf",
                ),
            lineItemsData =
                listOf(
                    KassenbuchDto.LineItemData(vorzBetrag = "-23.78", ustSatz = "19", belegtext = "Waren 19%"),
                    KassenbuchDto.LineItemData(vorzBetrag = "-131.78", ustSatz = "7", belegtext = "Waren 7%"),
                    KassenbuchDto.LineItemData(vorzBetrag = "19.11", ustSatz = "19", belegtext = "Warengutschein 19%", kind = KassenbuchLineKind.GUTSCHEIN),
                    KassenbuchDto.LineItemData(vorzBetrag = "105.89", ustSatz = "7", belegtext = "Warengutschein 7%", kind = KassenbuchLineKind.GUTSCHEIN),
                ),
        )

    @Test
    fun `metro invoice exports 4 mapped rows with S-H flip and Kasse netting to cash total`() {
        transaction {
            val rows = KassenbuchBuchungsstapelRowMapper.mapHeader(metroHeader(), mapping = mapping)
            assertEquals(4, rows.size)
            rows.forEach {
                assertEquals(1000, it.konto, "Kasse is the Konto on every mapped line")
                assertEquals("2026-07-0100", it.belegNr, "split rows share Belegfeld 1")
            }
            assertEquals(3400, rows[0].gegenkonto) // Wareneingang 19 %
            assertEquals(3300, rows[1].gegenkonto) // Wareneingang 7 %
            assertEquals(3720, rows[2].gegenkonto) // Nachlässe 19 %
            assertEquals(3710, rows[3].gegenkonto) // Nachlässe 7 %

            // Kasse position nets to the cash paid: -30.56
            assertEquals(0, BigDecimal("-30.56").compareTo(rows.sumOf { it.umsatz }))

            // S/H: goods rows negative -> H, Gutschein rows positive -> S; Umsatz always positive
            val goods = DatevBuchungsstapelRowGenerator.generate(rows[0], sachkontenlaenge = 4)
            assertEquals(Cell.Amount(BigDecimal("23.78")), goods[0])
            assertEquals(Cell.Str("H"), goods[1])
            assertEquals(Cell.Int(1000), goods[6])
            assertEquals(Cell.Int(3400), goods[7])
            val gutschein = DatevBuchungsstapelRowGenerator.generate(rows[2], sachkontenlaenge = 4)
            assertEquals(Cell.Amount(BigDecimal("19.11")), gutschein[0])
            assertEquals(Cell.Str("S"), gutschein[1])
            assertEquals(Cell.Int(3720), gutschein[7])
        }
    }

    /**
     * T9-10: a Geldtransit Ladenkasse → Tresor must move money between two Sachkonten. Booking the
     * Tresor leg on the mapping's `kasseKonto` (1000) produced `1000 / 1360` out and `1000 / 1360` in,
     * so the transfer cancelled itself out and the Tresor (1010) never received anything.
     */
    @Test
    fun `a Geldtransit books each leg on its own cash book's DATEV account`() {
        transaction {
            val books = CashBookRepositoryImpl()
            val ladenkasse = books.fiscalBookFor(shop)
            val tresor = books.create(shop, "Tresor", isFiscal = false, datevAccount = "1010")
            val outLeg =
                transferLeg(KassenbuchEntryType.CASH_TRANSFER_OUT, "2026-09-0003", "-50.00", ladenkasse.id.value, tresor.id.value)
            val inLeg =
                transferLeg(KassenbuchEntryType.CASH_TRANSFER_IN, "2026-09-0001", "50.00", tresor.id.value, ladenkasse.id.value)

            val out = KassenbuchBuchungsstapelRowMapper.mapHeader(outLeg, mapping = mapping).single()
            val into = KassenbuchBuchungsstapelRowMapper.mapHeader(inLeg, mapping = mapping).single()

            assertEquals(1000, out.konto, "the fiscal Ladenkasse keeps the mapping's Kasse account")
            assertEquals(1360, out.gegenkonto)
            assertEquals(1010, into.konto, "the Tresor leg is booked on the Tresor's own account")
            assertEquals(1360, into.gegenkonto)
        }
    }

    /**
     * F60: every cash book numbers its entries on its own sequence, so the Ladenkasse and a Tresor
     * can both hold `2026-09-0001`. One Buchungsstapel carrying both must not show the same
     * Belegfeld 1 twice — the non-fiscal book's rows are prefixed, the fiscal book's stay plain, and
     * the stored belegNr is untouched.
     */
    @Test
    fun `two books with the same entry number export distinct Belegfeld 1 values`() {
        transaction {
            val books = CashBookRepositoryImpl()
            val ladenkasse = books.fiscalBookFor(shop)
            val tresor = books.create(shop, "Tresor", isFiscal = false, datevAccount = "1010")
            val outLeg =
                transferLeg(KassenbuchEntryType.CASH_TRANSFER_OUT, "2026-09-0001", "-50.00", ladenkasse.id.value, tresor.id.value)
            val inLeg =
                transferLeg(KassenbuchEntryType.CASH_TRANSFER_IN, "2026-09-0001", "50.00", tresor.id.value, ladenkasse.id.value)

            val fiscalRow = KassenbuchBuchungsstapelRowMapper.mapHeader(outLeg, mapping = mapping).single()
            val tresorRow = KassenbuchBuchungsstapelRowMapper.mapHeader(inLeg, mapping = mapping).single()

            assertEquals("2026-09-0001", fiscalRow.belegNr, "the fiscal Ladenkasse keeps its plain number")
            assertEquals("T${tresor.id.value}-2026-09-0001", tresorRow.belegNr)

            val fiscalCell = DatevBuchungsstapelRowGenerator.generate(fiscalRow, sachkontenlaenge = 4)[10]
            val tresorCell = DatevBuchungsstapelRowGenerator.generate(tresorRow, sachkontenlaenge = 4)[10]
            assertTrue(fiscalCell != tresorCell, "Belegfeld 1 must differ across books: $fiscalCell vs $tresorCell")
            assertEquals("2026-09-0001", inLeg.belegNr, "the stored Kassenbuch number is not rewritten")
        }
    }

    private fun transferLeg(
        type: KassenbuchEntryType,
        belegNr: String,
        amount: String,
        bookId: Long,
        counterpartyId: Long,
    ): KassenbuchHeader =
        repository.createEntry(
            shop = shop,
            employee = employee,
            headerData =
                KassenbuchDto.KassenbuchData(
                    entryType = type,
                    belegNr = belegNr,
                    vorzBetrag = amount,
                    belegDatum = "25.09.2026",
                    belegtext = "Geldtransit",
                    cashBookId = bookId,
                    counterpartyBookId = counterpartyId,
                    transferId = "t9-10",
                ),
            lineItemsData = listOf(KassenbuchDto.LineItemData(vorzBetrag = amount, belegtext = "Geldtransit")),
        )

    @Test
    fun `non-cash payment lines resolve via the payment type mapping`() {
        val pt = TestData.createPaymentType(name = "CARD", isCash = false)
        val cardMapping =
            DatevAccountMappingDefaults.seed(
                Kontenrahmen.SKR03,
                listOf(DatevAccountMappingDefaults.PaymentTypeSeed(id = pt.id.value, isVoucher = false)),
            )
        transaction {
            val header =
                repository.createEntry(
                    shop = shop,
                    employee = employee,
                    headerData =
                        KassenbuchDto.KassenbuchData(
                            entryType = KassenbuchEntryType.CASH_SALE,
                            belegNr = "2026-07-0101",
                            vorzBetrag = "80.0",
                            belegDatum = "08.07.2026",
                            belegtext = "ZBon Nr. 7",
                        ),
                    lineItemsData =
                        listOf(
                            KassenbuchDto.LineItemData(vorzBetrag = "100.0", ustSatz = "19"),
                            KassenbuchDto.LineItemData(
                                vorzBetrag = "-20.0",
                                belegtext = "TE:Unbare Zahlung: CARD",
                                kind = KassenbuchLineKind.NON_CASH_PAYMENT,
                                paymentTypeId = pt.id.value,
                            ),
                        ),
                )
            val rows = KassenbuchBuchungsstapelRowMapper.mapHeader(header, mapping = cardMapping)
            assertEquals(2, rows.size)

            val standardRow = rows.single { it.umsatz.compareTo(BigDecimal("100.00")) == 0 }
            assertEquals(8400, standardRow.gegenkonto) // income STANDARD 19

            val mappedCardRow = rows.single { it.umsatz.compareTo(BigDecimal("-20.00")) == 0 }
            assertEquals(1000, mappedCardRow.konto)
            assertEquals(1360, mappedCardRow.gegenkonto) // Geldtransit via paymentTypes map
        }
    }

    @Test
    fun `a non-cash line whose payment type is unmapped fails the export instead of guessing`() {
        // Previously this line fell back whole-line to the legacy CASH_SALE accounts (1000/8400),
        // i.e. an unmapped card tender was booked straight against revenue. That is the C4 defect:
        // cashless takings subtracted from turnover. There is no safe default for "which clearing
        // account does this tender settle to", so the export refuses and names the line.
        val pt = TestData.createPaymentType(name = "CARD", isCash = false)
        val cardMapping =
            DatevAccountMappingDefaults.seed(
                Kontenrahmen.SKR03,
                listOf(DatevAccountMappingDefaults.PaymentTypeSeed(id = pt.id.value, isVoucher = false)),
            )
        transaction {
            val header =
                repository.createEntry(
                    shop = shop,
                    employee = employee,
                    headerData =
                        KassenbuchDto.KassenbuchData(
                            entryType = KassenbuchEntryType.CASH_SALE,
                            belegNr = "2026-07-0102",
                            vorzBetrag = "90.0",
                            belegDatum = "08.07.2026",
                            belegtext = "ZBon Nr. 8",
                        ),
                    lineItemsData =
                        listOf(
                            KassenbuchDto.LineItemData(vorzBetrag = "100.0", ustSatz = "19"),
                            KassenbuchDto.LineItemData(
                                vorzBetrag = "-10.0",
                                belegtext = "TE:Unbare Zahlung: OTHER",
                                kind = KassenbuchLineKind.NON_CASH_PAYMENT,
                            ),
                        ),
                )
            val ex =
                assertFailsWith<PosException> {
                    KassenbuchBuchungsstapelRowMapper.mapHeader(header, mapping = cardMapping)
                }
            assertEquals(PosErrorCode.DATEV_ACCOUNT_MAPPING_REQUIRED, ex.code)
            assertTrue(ex.message!!.contains("2026-07-0102"), "the error must name the Beleg: ${ex.message}")
            assertNamesTheRealMappingScreen(ex)
        }
    }

    @Test
    fun `without a mapping the export is refused rather than falling back`() {
        // Was `byte-identical to legacy behavior`, asserting defaultKonto = 4000. Those per-entry-type
        // constants were a fixed SKR03/SKR04 mix AND put the contra account in Konto for five entry
        // types, which the exporter's cash-flow sign then inverted — a cash purchase raised the till
        // (32,94;"H";4000;1000;"Rewe Einkauf" in a real shipped export). The constants are gone.
        transaction {
            val ex =
                assertFailsWith<PosException> {
                    KassenbuchBuchungsstapelRowMapper.mapHeader(metroHeader(), mapping = null)
                }
            assertEquals(PosErrorCode.DATEV_ACCOUNT_MAPPING_REQUIRED, ex.code)
            assertNamesTheRealMappingScreen(ex)
        }
        assertNamesTheRealMappingScreen(assertFailsWith<PosException> { DatevBuchungsstapelMappingGate.requireConfigured(null) })
    }

    /**
     * T5-7: the refusal used to send the operator to "Settings > DATEV", a screen that does not
     * exist. The mapping is edited under Compliance > Data export in the web back office.
     */
    private fun assertNamesTheRealMappingScreen(ex: PosException) {
        val message = ex.message.orEmpty()
        assertTrue(message.contains("Compliance > Data export"), "the refusal must name where the mapping lives: $message")
        assertFalse(message.contains("Settings > DATEV"), "the refusal names a screen that does not exist: $message")
    }
}
