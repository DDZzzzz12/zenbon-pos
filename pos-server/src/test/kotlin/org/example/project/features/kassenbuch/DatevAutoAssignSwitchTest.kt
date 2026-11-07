package org.example.project.features.kassenbuch

import org.example.project.core.domain.dto.datev.CellRef
import org.example.project.core.domain.dto.datev.DatevAccountMappingDto.AccountCell
import org.example.project.core.domain.dto.kassenbuch.KassenbuchDto
import org.example.project.core.domain.enums.datev.DatevMappingSide
import org.example.project.core.domain.enums.datev.KassenbuchSpecialBooking
import org.example.project.core.domain.enums.datev.Kontenrahmen
import org.example.project.core.domain.enums.kassenbuch.KassenbuchEntryType
import org.example.project.core.domain.enums.kassenbuch.KassenbuchLineKind
import org.example.project.core.error.PosErrorCode
import org.example.project.core.utils.datev.DatevAccountMappingDefaults
import org.example.project.export.datevbuchungsstapel.DatevBuchungsstapelExportBuilder
import org.example.project.export.datevbuchungsstapel.model.BuchungsstapelFileConfig
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.kassenbuch.data.repository.KassenbuchRepositoryImpl
import org.example.project.features.kassenbuch.data.service.DatevBuchungsstapelMappingGate
import org.example.project.features.kassenbuch.data.service.DatevXmlExportServiceImpl
import org.example.project.features.kassenbuch.data.service.KassenbuchAccountResolver
import org.example.project.features.kassenbuch.data.service.KassenbuchAccountResolver.Resolution
import org.example.project.features.kassenbuch.data.service.KassenbuchBuchungsstapelRowMapper
import org.example.project.features.kassenbuch.data.service.KassenbuchUnassignedContra
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
import java.lang.reflect.Proxy
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * „Konto automatisch zuordnen": a mapping cell with the switch OFF exports its lines with an EMPTY
 * contra account (Buchungsstapel Gegenkonto + BU, XML accountNo + buCode), reports them, and may be
 * left empty; a cell with the switch ON still must carry an account. An all-ON mapping exports
 * byte-identically to before the switch existed.
 */
class DatevAutoAssignSwitchTest {
    private lateinit var repository: KassenbuchRepositoryImpl
    private lateinit var shop: ShopInfo
    private lateinit var employee: Employee
    private val seed = DatevAccountMappingDefaults.seed(Kontenrahmen.SKR03, emptyList())

    @BeforeEach
    fun setup() {
        TestDatabase.setup()
        repository = KassenbuchRepositoryImpl(testKassenbuchDocuments())
        shop = TestData.createShopInfo()
        employee = TestData.createAdmin()
    }

    @AfterEach
    fun teardown() = TestDatabase.teardown()

    /** [seed] with income STANDARD 7 % switched off — empty, as the operator may leave it. */
    private val sevenOff =
        seed.copy(
            income =
                seed.income + (
                    KassenbuchLineKind.STANDARD to
                        seed.income.getValue(KassenbuchLineKind.STANDARD) + ("7" to AccountCell(konto = null, autoAssign = false))
                ),
        )

    private fun sale(gegenkonto: String? = null): KassenbuchHeader =
        repository.createEntry(
            shop = shop,
            employee = employee,
            headerData =
                KassenbuchDto.KassenbuchData(
                    entryType = KassenbuchEntryType.CASH_SALE,
                    belegNr = "2026-07-0300",
                    vorzBetrag = "30.0",
                    belegDatum = "08.07.2026",
                    belegtext = "Barverkauf",
                    gegenkonto = gegenkonto,
                    kost1 = "K1",
                ),
            lineItemsData =
                listOf(
                    KassenbuchDto.LineItemData(vorzBetrag = "20.0", ustSatz = "19", belegtext = "Speisen 19"),
                    KassenbuchDto.LineItemData(vorzBetrag = "10.0", ustSatz = "7", belegtext = "Speisen 7"),
                ),
        )

    private fun surplus(): KassenbuchHeader =
        repository.createEntry(
            shop = shop,
            employee = employee,
            headerData =
                KassenbuchDto.KassenbuchData(
                    entryType = KassenbuchEntryType.CASH_SURPLUS,
                    belegNr = "2026-07-0301",
                    vorzBetrag = "1.50",
                    belegDatum = "08.07.2026",
                    belegtext = "Kassendifferenz",
                ),
            lineItemsData = listOf(KassenbuchDto.LineItemData(vorzBetrag = "1.50", ustSatz = "0")),
        )

    private val cfg =
        BuchungsstapelFileConfig(
            consultantNumber = "29098",
            clientNumber = "55003",
            wirtschaftsjahrBeginn = LocalDate.of(2026, 1, 1),
            sachkontenlaenge = 4,
            festschreibung = false,
            startDate = LocalDate.of(2026, 7, 1),
            endDate = LocalDate.of(2026, 7, 31),
            bezeichnung = "Kassenbuch",
            skr = "03",
        )

    private fun dataLines(csv: ByteArray): List<String> = String(csv, Charsets.UTF_8).split("\r\n").drop(2).filter { it.isNotBlank() }

    private inline fun <reified T : Any> noopProxy(): T {
        @Suppress("UNCHECKED_CAST")
        return Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            throw NotImplementedError("Stub: ${T::class.simpleName}.${method.name} not expected here")
        } as T
    }

    @Test
    fun `a switched-off cell resolves without contra account and names itself`() {
        val r =
            KassenbuchAccountResolver.resolve(
                entryType = KassenbuchEntryType.CASH_SALE,
                headerGegenkonto = null,
                headerBu = "9",
                kind = KassenbuchLineKind.STANDARD,
                ustSatz = "7,00",
                paymentTypeId = null,
                mapping = sevenOff,
            )
        val expectedRef = CellRef(side = DatevMappingSide.INCOME, kind = KassenbuchLineKind.STANDARD, rate = "7")
        assertEquals(Resolution.Mapped(konto = 1000, gegenkonto = null, bu = null, unassignedCell = expectedRef), r)
    }

    @Test
    fun `an account typed on the entry still wins over a switched-off cell`() {
        val r =
            KassenbuchAccountResolver.resolve(
                entryType = KassenbuchEntryType.CASH_SALE,
                headerGegenkonto = 8338,
                headerBu = null,
                kind = KassenbuchLineKind.STANDARD,
                ustSatz = "7",
                paymentTypeId = null,
                mapping = sevenOff,
            )
        assertEquals(Resolution.Mapped(konto = 1000, gegenkonto = 8338, bu = null), r)
    }

    @Test
    fun `a switched-off cell keeps its account stored but the export ignores it`() {
        val kept = seed.copy(special = seed.special + (KassenbuchSpecialBooking.CASH_SURPLUS to AccountCell(8603, "3", autoAssign = false)))
        val r =
            KassenbuchAccountResolver.resolve(KassenbuchEntryType.CASH_SURPLUS, null, null, KassenbuchLineKind.STANDARD, "0", null, kept)
        assertEquals(
            Resolution.Mapped(1000, null, null, CellRef(special = KassenbuchSpecialBooking.CASH_SURPLUS)),
            r,
        )
    }

    @Test
    fun `an ON cell without an account resolves to Legacy so the export refuses the line`() {
        val broken = seed.copy(special = seed.special + (KassenbuchSpecialBooking.CASH_SURPLUS to AccountCell(konto = null)))
        val r =
            KassenbuchAccountResolver.resolve(KassenbuchEntryType.CASH_SURPLUS, null, null, KassenbuchLineKind.STANDARD, "0", null, broken)
        assertSame(Resolution.Legacy, r)
    }

    @Test
    fun `Buchungsstapel writes the switched-off line with an empty Gegenkonto and BU and everything else intact`() {
        transaction {
            val header = sale()
            val rows = KassenbuchBuchungsstapelRowMapper.mapHeader(header, mapping = sevenOff)
            assertEquals(listOf(8400, null), rows.map { it.gegenkonto })

            val lines = dataLines(DatevBuchungsstapelExportBuilder.build(cfg, rows))
            assertEquals(2, lines.size)
            val mapped = lines[0].split(";")
            val open = lines[1].split(";")
            assertEquals("8400", mapped[7])
            assertEquals("1000", open[6], "Konto stays the cash account")
            assertEquals("", open[7], "Gegenkonto is truly empty — not 0, not quoted")
            assertEquals("\"\"", open[8], "BU-Schlüssel empty exactly as for an ON cell without BU")
            assertEquals("10,00", open[0])
            assertEquals("\"S\"", open[1], "Soll/Haben unchanged")
            // Belegdatum, Belegfeld, KOST1 … every other field equals the mapped line's, which
            // differs only in amount (0), Gegenkonto (7) and Buchungstext (13).
            val differing = setOf(0, 7, 13)
            assertEquals(mapped.filterIndexed { i, _ -> i !in differing }, open.filterIndexed { i, _ -> i !in differing })
            assertEquals("\"K1\"", open[36])
        }
    }

    @Test
    fun `an all-ON mapping exports byte-identically and reports nothing`() {
        transaction {
            val header = sale()
            val rows = KassenbuchBuchungsstapelRowMapper.mapHeader(header, mapping = seed)
            assertEquals(listOf(8400, 8300), rows.map { it.gegenkonto })
            val lines = dataLines(DatevBuchungsstapelExportBuilder.build(cfg, rows))
            assertTrue(lines.all { it.split(";")[7].isNotEmpty() })
            assertNull(KassenbuchUnassignedContra.collect(listOf(header), seed))
            // A stored mapping that names the flag explicitly ON is the same mapping.
            val explicitOn = seed.copy(special = seed.special.mapValues { (_, c) -> c.copy(autoAssign = true) })
            assertEquals(seed, explicitOn)
        }
    }

    @Test
    fun `the export counts the lines it left without a contra account, per cell`() {
        transaction {
            val headers = listOf(sale(), sale(), surplus())
            val mapping =
                sevenOff.copy(special = sevenOff.special + (KassenbuchSpecialBooking.CASH_SURPLUS to AccountCell(null, autoAssign = false)))
            val summary = assertNotNull(KassenbuchUnassignedContra.collect(headers, mapping))
            assertEquals(3, summary.lineCount)
            assertEquals(
                mapOf(
                    CellRef(side = DatevMappingSide.INCOME, kind = KassenbuchLineKind.STANDARD, rate = "7") to 2,
                    CellRef(special = KassenbuchSpecialBooking.CASH_SURPLUS) to 1,
                ),
                summary.bookings.associate { it.cell to it.lineCount },
            )
        }
    }

    @Test
    fun `XML omits accountNo and buCode for a switched-off line`() {
        val service =
            DatevXmlExportServiceImpl(
                kassenbuchRepository = repository,
                shopInfoRepository = noopProxy(),
                zBonService = noopProxy(),
                configManager = noopProxy(),
                exportLogService = noopProxy(),
                documentRepository = noopProxy(),
            )
        transaction {
            val entries = service.mapHeaderToEntries(sale(), sevenOff)
            assertEquals("8400", entries[0].accountNo)
            assertNull(entries[1].accountNo)
            assertNull(entries[1].buCode)
            assertEquals("10.00", entries[1].amount, "everything but the account stays")
        }
    }

    @Test
    fun `the gate passes switched-off empty cells and refuses an empty ON cell`() {
        assertSame(sevenOff, DatevBuchungsstapelMappingGate.requireConfigured(sevenOff))

        val emptyOn =
            seed.copy(paymentTypes = mapOf(3L to AccountCell(konto = null)))
        val e = assertFailsWith<PosException> { DatevBuchungsstapelMappingGate.requireConfigured(emptyOn) }
        assertEquals(PosErrorCode.DATEV_ACCOUNT_MAPPING_REQUIRED, e.code)
        assertTrue(e.message!!.contains("paymentTypes.3"), e.message)
        Unit
    }
}
