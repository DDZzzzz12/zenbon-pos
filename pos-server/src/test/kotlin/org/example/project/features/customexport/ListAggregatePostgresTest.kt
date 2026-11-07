package org.example.project.features.customexport

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.customexport.CustomExportDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.database.DatabaseFactory
import org.example.project.features.customexport.data.service.CustomExportServiceImpl
import org.example.project.features.customexport.database.tables.ExportDefinitionTable
import org.example.project.features.partner.database.tables.BusinessPartnerTable
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.supplier.database.tables.SupplierBankAccountTable
import org.example.project.infrastructure.plugins.initDB
import org.example.project.testutils.PostgresTestDatabase
import org.example.project.testutils.TestData
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals

/**
 * The `LIST` aggregate — a partner's IBANs comma-joined into one cell — on the engine that runs it.
 *
 * This class exists on the `@Tag("postgres")` leg and nowhere else, and that is not a preference.
 * `LIST` is emitted as `string_agg(value, ', ' ORDER BY order)`; H2 has no `string_agg` at all, and
 * the `ORDER BY`-inside-the-call form is Postgres syntax even where a `string_agg` exists. A copy of
 * these tests under the default task would not differ subtly — it would fail outright. So the four
 * standard reductions are proved on H2 in `AggregateRunTest` and this one is proved here, which is
 * also the only place the ordering guarantee *can* be proved: H2 is free to return an index scan
 * that happens to be sorted and would pass an ordering assertion the SQL never asked for.
 *
 * The `partner → supplier_bank_account` path is unscoped — `SupplierBankAccountTable` declares no
 * period column, because an IBAN is not an event — so the scoped LIST path is unreachable from
 * today's catalogue and is deliberately not exercised here. No table with a `periodColumn` declares
 * a LIST yet; the first that does brings the scoped case with it.
 */
@Tag("postgres")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Custom export LIST aggregate — real PostgreSQL (zonky)")
class ListAggregatePostgresTest {
    private lateinit var pg: PostgresTestDatabase

    private val service = CustomExportServiceImpl()

    /** What the partner subject's own fields and the bank-account aggregate both require. */
    private val held = setOf(Capability.CREDITOR_READ)

    /** The to-many hop from the company to the accounts it has been seen to use. */
    private val ibanListKey = "supplier_bank_account:by:partner_id~list"

    @BeforeAll
    fun start() {
        pg = PostgresTestDatabase.start()
        initDB(pg.hikariConfig(), runMigrations = true)
        transaction { TestData.createShopInfo() }
    }

    @AfterAll
    fun stop() {
        if (::pg.isInitialized) {
            DatabaseFactory.close()
            pg.close()
        }
    }

    /**
     * One embedded PostgreSQL serves the whole class, so each test clears what the last one seeded.
     *
     * The shop survives: definitions are filed under it, and re-creating it per test would leave
     * `ShopInfo.all().first()` picking between shops.
     */
    @BeforeEach
    fun clearSeededRows() {
        transaction {
            ExportDefinitionTable.deleteAll()
            SupplierBankAccountTable.deleteAll()
            BusinessPartnerTable.deleteAll()
        }
    }

    /**
     * Every value reaches the cell, and the partner still occupies exactly one row.
     *
     * Both halves matter and neither implies the other. A join over this to-many relation would give
     * three rows with one IBAN each — a plausible-looking file with the company repeated and nothing
     * in it to say so — while an aggregate that dropped values would keep the row count perfectly and
     * still be wrong. So the row count is asserted against the number of partners, and the cell is
     * asserted whole rather than by `contains`.
     */
    @Test
    fun `three accounts become one cell on one row`() {
        val alpha = partner("Alpha GmbH")
        partner("Beta AG")
        bankAccount(alpha, "DE22500105170000000022")
        bankAccount(alpha, "DE11500105170000000011")
        bankAccount(alpha, "DE33500105170000000033")

        val result = runList()
        val rows = result.bytes.asCsvLines().drop(1)

        assertEquals(
            2,
            result.rowCount,
            "one row per partner: three IBANs on one of them must not become three rows",
        )
        assertEquals(2, rows.size, "and the produced file agrees with the DTO's count")
        assertEquals(
            "Alpha GmbH;DE11500105170000000011, DE22500105170000000022, DE33500105170000000033",
            rows.first { it.startsWith("Alpha GmbH") },
            "all three accounts, comma-joined into the one cell the partner's row has for them",
        )
    }

    /**
     * The same definition over unchanged data produces the same cell, and a *sorted* one.
     *
     * The accounts are inserted in an order that is not their sorted order, which is what makes this
     * test bite: without the `ORDER BY` inside `string_agg`, PostgreSQL is free to hand back whatever
     * order the scan produced — in practice the insertion order — and the expected value below would
     * not match. The equality between the two runs is the weaker half; the exact sorted value is the
     * assertion that says the order is *ours* rather than whatever the plan happened to give.
     *
     * The consequence of losing it is not a crash but a diff: an advisor comparing this month's
     * export against last month's would see a changed cell where nothing changed.
     */
    @Test
    fun `the joined cell is sorted and identical across two runs`() {
        val alpha = partner("Alpha GmbH")
        bankAccount(alpha, "DE99500105170000000099")
        bankAccount(alpha, "DE44500105170000000044")
        bankAccount(alpha, "DE66500105170000000066")

        val definition = saveListDefinition()
        val first = cellsOf(definition).getValue("Alpha GmbH")
        val second = cellsOf(definition).getValue("Alpha GmbH")

        assertEquals(
            "DE44500105170000000044, DE66500105170000000066, DE99500105170000000099",
            first,
            "ordered by IBAN, not by the order the rows were inserted in",
        )
        assertEquals(first, second, "and the second run of the same definition writes the same cell")
    }

    /**
     * The joined text is operator data, so it goes through the CSV formula guard like any other TEXT.
     *
     * `LIST` is declared `TEXT` and its content is read straight back out of a column a human typed
     * into. A cell beginning `=` is executed by Excel on open; the guard prefixes an apostrophe so it
     * is imported as the text it is. The aggregate is a subquery rather than a column, which is
     * exactly the sort of value a guard is forgotten for.
     */
    @Test
    fun `a joined value beginning with an equals sign is formula-guarded`() {
        val alpha = partner("Alpha GmbH")
        bankAccount(alpha, "=DE89370400440532013000")

        assertEquals(
            "'=DE89370400440532013000",
            cellsOf(saveListDefinition()).getValue("Alpha GmbH"),
            "guarded with a leading apostrophe, so the recipient's spreadsheet imports it as text",
        )
    }

    /**
     * A partner with no accounts gets an empty cell.
     *
     * `string_agg` over no rows is NULL, and the renderer writes NULL as nothing at all. The two
     * wrong answers this rules out are the literal `null` — a JDBC value stringified on its way into
     * the file — and a bare separator pair left over from joining an empty list by hand.
     */
    @Test
    fun `a partner with no accounts gets a blank cell`() {
        val alpha = partner("Alpha GmbH")
        partner("Beta AG")
        bankAccount(alpha, "DE11500105170000000011")

        val rows = runList().bytes.asCsvLines().drop(1)

        assertEquals(
            "Beta AG;",
            rows.first { it.startsWith("Beta AG") },
            "the name, the delimiter, and nothing — not 'null', and not a stray ', '",
        )
        assertEquals(
            "Alpha GmbH;DE11500105170000000011",
            rows.first { it.startsWith("Alpha GmbH") },
            "while the partner that does have one is unaffected by its neighbour's blank",
        )
    }

    /** Partner name plus the comma-joined IBANs — two columns, one of them a subquery. */
    private fun listRequest() =
        CustomExportDto.SaveDefinitionRequestDto(
            name = "Bankverbindungen je Partner",
            subjectKey = "partner",
            fields =
                listOf(
                    CustomExportDto.DefinitionFieldDto("partner.name", 0, "Firma"),
                    CustomExportDto.DefinitionFieldDto(ibanListKey, 1, "IBANs"),
                ),
        )

    private fun saveListDefinition(): Long = runBlocking { service.save(listRequest(), held, "admin").id }

    private fun runList(): CustomExportDto.RunResultDto = runBlocking { service.run(saveListDefinition(), held) }

    /** The data rows of one run, as company name to its IBAN cell. */
    private fun cellsOf(definitionId: Long): Map<String, String> =
        runBlocking { service.run(definitionId, held) }
            .bytes
            .asCsvLines()
            .drop(1)
            .associate { line -> line.substringBefore(';') to line.substringAfter(';') }

    /** Inserted through the table rather than a DAO, so `createdAt` is ours. */
    private fun partner(name: String): EntityID<Long> =
        transaction {
            BusinessPartnerTable
                .insert {
                    it[BusinessPartnerTable.shopId] = ShopInfo.all().first().id
                    it[BusinessPartnerTable.name] = name
                    it[BusinessPartnerTable.isActive] = true
                    it[BusinessPartnerTable.createdAt] = LocalDateTime.parse("2026-03-01T10:00")
                }[BusinessPartnerTable.id]
        }

    /** One IBAN the company has been seen to use — a row of the to-many side being aggregated. */
    private fun bankAccount(
        partner: EntityID<Long>,
        iban: String,
    ) {
        transaction {
            SupplierBankAccountTable.insert {
                it[SupplierBankAccountTable.partnerId] = partner
                it[SupplierBankAccountTable.iban] = iban
            }
        }
    }
}
