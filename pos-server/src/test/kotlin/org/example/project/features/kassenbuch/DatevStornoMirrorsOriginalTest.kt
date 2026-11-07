package org.example.project.features.kassenbuch

import org.example.project.core.domain.dto.datev.DatevAccountMappingDto.AccountCell
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.datev.KassenbuchSpecialBooking
import org.example.project.core.domain.enums.datev.Kontenrahmen
import org.example.project.core.domain.enums.kassenbuch.CorrectionRole
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.core.utils.datev.DatevAccountMappingDefaults
import org.example.project.export.datevbuchungsstapel.DatevBuchungsstapelCsvWriter.Cell
import org.example.project.export.datevbuchungsstapel.DatevBuchungsstapelRowGenerator
import org.example.project.export.datevbuchungsstapel.model.BuchungsstapelRowInput
import org.example.project.export.dsfinvk.builder.KassenbuchDsfinvkMapping
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.CashBookRepositoryImpl
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.data.service.DatevXmlExportServiceImpl
import org.example.project.features.kassenbuch.data.service.KassenbuchBuchungsstapelRowMapper
import org.example.project.features.kassenbuch.domain.models.KassenbuchHeader
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.testutils.testKassenbuchDocuments
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * F64: a Storno of a Kassenbuch entry must reverse the ORIGINAL booking in DATEV — the same
 * Konto/Gegenkonto/BU, the opposite S/H — whatever the original's entry type. It used to resolve
 * through its own `CORRECTION_*` entry type, which sends every Storno to the generic income or
 * expense table: a Privateinlage of +20 in a Tresor exported as `1010 / 9000`, its Storno as
 * `1010 / 8100` (revenue).
 */
class DatevStornoMirrorsOriginalTest {
    private lateinit var repository: KassenbuchRepositoryImpl
    private lateinit var shop: ShopInfo
    private lateinit var employee: Employee
    private var belegSeq = 0

    private val seeded = DatevAccountMappingDefaults.seed(Kontenrahmen.SKR03, emptyList())

    /** The QA shop's configuration: Privateinlage booked against 9000. */
    private val mapping =
        seeded.copy(special = seeded.special + (KassenbuchSpecialBooking.PRIVATE_DEPOSIT to AccountCell(9000)))

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        repository = KassenbuchRepositoryImpl(testKassenbuchDocuments())
        shop = TestData.createShopInfo()
        employee = TestData.createAdmin()
        belegSeq = 0
    }

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    private data class Line(
        val amount: String,
        val rate: String? = null,
        val kind: KassenbuchLineKind = KassenbuchLineKind.STANDARD,
    )

    private fun entry(
        type: KassenbuchEntryType,
        lines: List<Line>,
        bookId: Long? = null,
        correctsHeaderId: Long? = null,
        correctionRole: CorrectionRole? = null,
        bu: String? = null,
    ): KassenbuchHeader {
        val total = lines.map { BigDecimal(it.amount) }.fold(BigDecimal.ZERO, BigDecimal::add)
        return repository.createEntry(
            shop = shop,
            employee = employee,
            headerData =
                KassenbuchDto.KassenbuchData(
                    entryType = type,
                    belegNr = "2026-10-%04d".format(++belegSeq),
                    vorzBetrag = total.toPlainString(),
                    belegDatum = "06.10.2026",
                    belegtext = type.displayName,
                    cashBookId = bookId,
                    correctsHeaderId = correctsHeaderId,
                    correctionRole = correctionRole,
                    bu = bu,
                ),
            lineItemsData =
                lines.map {
                    KassenbuchDto.LineItemData(vorzBetrag = it.amount, ustSatz = it.rate, belegtext = "x", kind = it.kind)
                },
        )
    }

    /** Books [original] and its Storno exactly as `createCorrection` does: opposite CORRECTION_*, every cash-flow sign inverted. */
    private fun storno(
        original: KassenbuchHeader,
        bookId: Long? = null,
    ): KassenbuchHeader =
        entry(
            type = if (original.entryType.isIncoming) KassenbuchEntryType.CORRECTION_OUT else KassenbuchEntryType.CORRECTION_IN,
            lines =
                original.lineItems.sortedBy { it.lineNumber }.map {
                    Line(it.vorzBetrag.negate().toPlainString(), it.ustSatz, it.kind)
                },
            bookId = bookId,
            correctsHeaderId = original.id.value,
            correctionRole = CorrectionRole.REVERSAL,
        )

    private fun rows(header: KassenbuchHeader) = KassenbuchBuchungsstapelRowMapper.mapHeader(header, mapping = mapping)

    private fun soll(row: BuchungsstapelRowInput) = DatevBuchungsstapelRowGenerator.generate(row, sachkontenlaenge = 4)[1]

    /** Asserts the Storno's rows are the original's rows with the opposite sign and S/H. */
    private fun assertMirrors(
        original: KassenbuchHeader,
        reversal: KassenbuchHeader,
    ) {
        val o = rows(original)
        val r = rows(reversal)
        assertEquals(o.size, r.size, "one Storno row per original row")
        o.zip(r).forEach { (orig, rev) ->
            val what = "${original.entryType} line ${orig.buchungstext}"
            assertEquals(orig.konto, rev.konto, "$what: Konto")
            assertEquals(orig.gegenkonto, rev.gegenkonto, "$what: Gegenkonto")
            assertEquals(orig.bu, rev.bu, "$what: BU")
            assertEquals(0, orig.umsatz.negate().compareTo(rev.umsatz), "$what: amount is the negation")
            assertNotEquals(soll(orig), soll(rev), "$what: S/H is flipped")
        }
    }

    @Test
    fun `observed case - Storno of a Tresor Privateinlage books 1010 against the Privateinlage account, not revenue`() {
        transaction {
            val tresor = CashBookRepositoryImpl().create(shop, "Tresor", isFiscal = false, datevAccount = "1010")
            val original = entry(KassenbuchEntryType.PRIVATE_DEPOSIT, listOf(Line("20.00", "0")), bookId = tresor.id.value)
            val reversal = storno(original, bookId = tresor.id.value)

            val o = rows(original).single()
            val r = rows(reversal).single()
            assertEquals(1010, o.konto)
            assertEquals(9000, o.gegenkonto)
            assertEquals(1010, r.konto)
            assertEquals(9000, r.gegenkonto, "the Storno reverses the Privateinlage, it is not a revenue reduction (was 8100)")
            assertEquals(0, BigDecimal("-20.00").compareTo(r.umsatz))
            assertEquals(Cell.Str("S"), soll(o))
            assertEquals(Cell.Str("H"), soll(r))
        }
    }

    @Test
    fun `Storno of a Privateinlage and a Privatentnahme mirrors the original`() {
        transaction {
            entry(KassenbuchEntryType.PRIVATE_DEPOSIT, listOf(Line("200.00"))).let { assertMirrors(it, storno(it)) }
            entry(KassenbuchEntryType.PRIVATE_WITHDRAWAL, listOf(Line("-50.00"))).let { assertMirrors(it, storno(it)) }
        }
    }

    @Test
    fun `Storno of an expense with VAT mirrors every split line`() {
        transaction {
            val original =
                entry(KassenbuchEntryType.CASH_EXPENSE, listOf(Line("-11.90", "19"), Line("-10.70", "7")))
            assertMirrors(original, storno(original))
        }
    }

    @Test
    fun `Storno of a Barentnahme, a Bareinlage and a Rueckerstattung mirrors the original`() {
        transaction {
            entry(KassenbuchEntryType.CASH_WITHDRAWAL, listOf(Line("-40.00"))).let { assertMirrors(it, storno(it)) }
            entry(KassenbuchEntryType.CASH_DEPOSIT, listOf(Line("40.00"))).let { assertMirrors(it, storno(it)) }
            // A Rückerstattung is a revenue reduction; its Storno used to land on the EXPENSE table.
            entry(KassenbuchEntryType.REFUND, listOf(Line("-9.50", "19"))).let { assertMirrors(it, storno(it)) }
        }
    }

    @Test
    fun `Storno of a Kassendifferenz mirrors the original`() {
        transaction {
            entry(KassenbuchEntryType.CASH_SHORTAGE, listOf(Line("-3.20"))).let { assertMirrors(it, storno(it)) }
            entry(KassenbuchEntryType.CASH_SURPLUS, listOf(Line("1.80"))).let { assertMirrors(it, storno(it)) }
        }
    }

    @Test
    fun `Storno of a cash employee-tip payout mirrors the payout`() {
        transaction {
            val payout =
                entry(KassenbuchEntryType.CASH_WITHDRAWAL, listOf(Line("-3.00", kind = KassenbuchLineKind.EMPLOYEE_TIP)))
            assertMirrors(payout, storno(payout))
        }
    }

    @Test
    fun `Storno of a Geldtransit leg mirrors the leg`() {
        transaction {
            val books = CashBookRepositoryImpl()
            val ladenkasse = books.fiscalBookFor(shop)
            val tresor = books.create(shop, "Tresor", isFiscal = false, datevAccount = "1010")
            val outLeg = entry(KassenbuchEntryType.CASH_TRANSFER_OUT, listOf(Line("-50.00")), bookId = ladenkasse.id.value)
            // reverseTransfer books the opposite TRANSFER type in the same book, linked as REVERSAL.
            val reversal =
                entry(
                    KassenbuchEntryType.CASH_TRANSFER_IN,
                    listOf(Line("50.00")),
                    bookId = ladenkasse.id.value,
                    correctsHeaderId = outLeg.id.value,
                    correctionRole = CorrectionRole.REVERSAL,
                )
            assertMirrors(outLeg, reversal)
            val inLeg = entry(KassenbuchEntryType.CASH_TRANSFER_IN, listOf(Line("50.00")), bookId = tresor.id.value)
            assertMirrors(inLeg, storno(inLeg, bookId = tresor.id.value))
        }
    }

    @Test
    fun `the original's own Gegenkonto and BU override carry over to its Storno`() {
        transaction {
            val original = entry(KassenbuchEntryType.CASH_EXPENSE, listOf(Line("-11.90", "19")), bu = "9")
            // createEntry does not persist a header Gegenkonto; legacy/imported rows carry one.
            original.gegenkonto = "4930"
            val reversal = storno(original)
            assertEquals(4930, rows(reversal).single().gegenkonto)
            assertMirrors(original, reversal)
        }
    }

    @Test
    fun `XML ledger export books the Storno on the original's account too`() {
        transaction {
            val xml = xmlService()
            val original = entry(KassenbuchEntryType.PRIVATE_DEPOSIT, listOf(Line("20.00", "0")))
            val reversal = storno(original)
            assertEquals("9000", xml.mapHeaderToEntries(original, mapping).single().accountNo)
            assertEquals("9000", xml.mapHeaderToEntries(reversal, mapping).single().accountNo)
        }
    }

    @Test
    fun `DSFinV-K already maps each Storno into its original's GV_TYP`() {
        val types =
            listOf(
                KassenbuchEntryType.PRIVATE_DEPOSIT,
                KassenbuchEntryType.PRIVATE_WITHDRAWAL,
                KassenbuchEntryType.CASH_EXPENSE,
                KassenbuchEntryType.CASH_WITHDRAWAL,
                KassenbuchEntryType.CASH_SHORTAGE,
                KassenbuchEntryType.CASH_SURPLUS,
                KassenbuchEntryType.REFUND,
            )
        types.forEach { type ->
            val reversalType = if (type.isIncoming) KassenbuchEntryType.CORRECTION_OUT else KassenbuchEntryType.CORRECTION_IN
            assertEquals(
                KassenbuchDsfinvkMapping.getGvTyp(type),
                KassenbuchDsfinvkMapping.getGvTyp(reversalType, type),
                "GV_TYP of a Storno of $type",
            )
        }
        assertEquals(
            KassenbuchDsfinvkMapping.getGvTyp(KassenbuchEntryType.CASH_WITHDRAWAL, KassenbuchLineKind.EMPLOYEE_TIP, BigDecimal("-3")),
            KassenbuchDsfinvkMapping.getGvTyp(
                KassenbuchEntryType.CORRECTION_IN,
                KassenbuchLineKind.EMPLOYEE_TIP,
                BigDecimal("3"),
                KassenbuchEntryType.CASH_WITHDRAWAL,
            ),
        )
    }

    /**
     * Negative control: only the `correctsHeader` link reroutes a correction. An unlinked (legacy)
     * CORRECTION_OUT still resolves through its own income-side table — the 8100 the QA pass saw —
     * and a linked RE_ENTRY keeps the accounts of its own, operator-chosen entry type.
     */
    @Test
    fun `negative control - unlinked corrections and re-entries keep their own accounts`() {
        transaction {
            val unlinked = entry(KassenbuchEntryType.CORRECTION_OUT, listOf(Line("-20.00", "0")))
            assertEquals(8100, rows(unlinked).single().gegenkonto)

            val original = entry(KassenbuchEntryType.PRIVATE_DEPOSIT, listOf(Line("20.00", "0")))
            val reEntry =
                entry(
                    KassenbuchEntryType.CASH_DEPOSIT,
                    listOf(Line("25.00", "0")),
                    correctsHeaderId = original.id.value,
                    correctionRole = CorrectionRole.RE_ENTRY,
                )
            assertEquals(mapping.special.getValue(KassenbuchSpecialBooking.CASH_DEPOSIT).konto, rows(reEntry).single().gegenkonto)
        }
    }

    private inline fun <reified T : Any> noopProxy(): T {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            throw NotImplementedError("Stub: ${T::class.simpleName}.${method.name} not expected here")
        } as T
    }

    private fun xmlService() =
        DatevXmlExportServiceImpl(
            kassenbuchRepository = repository,
            shopInfoRepository = noopProxy(),
            zBonService = noopProxy(),
            configManager = noopProxy(),
            exportLogService = noopProxy(),
            documentRepository = noopProxy(),
        )
}
