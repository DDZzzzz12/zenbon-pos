package org.example.project.features.customexport

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.customexport.CustomExportDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.features.customexport.data.service.CustomExportServiceImpl
import org.example.project.features.document.database.tables.InvoiceTable
import org.example.project.features.partner.database.tables.BusinessPartnerTable
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.supplier.database.tables.SupplierTable
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The two aggregate twins, and the cost cap on how many of them one run may carry.
 *
 * The catalogue offers every aggregate over a table with a period column **twice** — `(Zeitraum)` and
 * `(gesamt)` — because either alone misleads. Until the bounds were threaded into the subquery the
 * two produced identical SQL, which is the worse of the two failures the twin rule exists to prevent:
 * a column labelled `(Zeitraum)` carrying a lifetime figure, with nothing in the file to say so. Every
 * test here therefore ticks **both** twins in one definition and asserts the two cells differ, not
 * merely that each is plausible.
 *
 * The Zeitraum is chosen so it also constrains the *outer* query — the partner subject's date column
 * is `created_at` — which is why the partners below are created inside it. That the two filters are
 * different predicates on different tables is the point: the invoices moving in and out of the range
 * must not take the partner row with them.
 *
 * `LIST` is absent for the same reason it is absent from [AggregateRunTest]: it is `string_agg`, which
 * H2 does not have.
 */
@DisplayName("Custom export period-scoped aggregates")
class AggregatePeriodTest {
    private val service = CustomExportServiceImpl()

    private val held = setOf(Capability.CREDITOR_READ)

    private val invoicePath = "supplier:by:partner_id.invoice:by:supplier_id"

    private val countKey = "$invoicePath~count"
    private val sumKey = "$invoicePath~sum"
    private val firstKey = "$invoicePath~first"
    private val lastKey = "$invoicePath~last"

    private val scopedCountKey = "$countKey@period"
    private val scopedSumKey = "$sumKey@period"
    private val scopedFirstKey = "$firstKey@period"
    private val scopedLastKey = "$lastKey@period"

    /** April 2026, bounded to the minute so the boundary invoices below sit exactly on both ends. */
    private val from = "2026-04-01T00:00"
    private val to = "2026-04-30T23:59"

    /**
     * The twins differ, and each is exactly right.
     *
     * Four invoices, two of them inside April and each sitting exactly on one boundary — the bounds
     * are inclusive at both ends, matching the outer query, so both count. The scoped column reads 2
     * and the unscoped 4; asserting they are unequal is what would have caught the state this task
     * started from, where both columns carried the same number.
     */
    @Test
    fun `the count twins differ, the scoped one bounded to the Zeitraum`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                alphaWithFourInvoices()

                val saved = service.save(countTwinRequest(from, to), held, "admin")
                val row =
                    service
                        .run(saved.id, held)
                        .bytes
                        .asCsvLines()
                        .drop(1)
                        .single()

                assertEquals(
                    "Alpha GmbH;2;4",
                    row,
                    "two invoices fall inside April — one on the 1st at 00:00 and one on the 30th at " +
                        "23:59, both bounds inclusive — while all four count over all time",
                )
                val cells = row.split(";")
                assertNotEquals(
                    cells[1],
                    cells[2],
                    "and the two columns must not agree: identical cells under different labels is " +
                        "the state where the '(Zeitraum)' heading is a claim the file does not keep",
                )
            }
        }
    }

    /**
     * The same separation on MONEY, where the rendering must survive the bound.
     *
     * A `SUM` inside a bounded subquery is still a `SUM` over `decimal(19,4)`, so it still reaches the
     * renderer as a `BigDecimal` and still comes out at scale 2 with a decimal comma. A bound that
     * changed the expression's carried type would show up here as a cell German Excel reads as text.
     */
    @Test
    fun `the sum twins differ, and both keep the money rendering`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                alphaWithFourInvoices()

                val saved = service.save(sumTwinRequest(from, to), held, "admin")
                val row =
                    service
                        .run(saved.id, held)
                        .bytes
                        .asCsvLines()
                        .drop(1)
                        .single()

                assertEquals(
                    "Alpha GmbH;30,00;137,00",
                    row,
                    "April's 20,50 and 9,50 against the lifetime 137,00 — each at scale 2 with a " +
                        "decimal comma, or the column will not sum in German Excel",
                )
            }
        }
    }

    /**
     * `Erste Rechnung (Zeitraum)` is the earliest invoice **inside** the range, not the earliest ever.
     *
     * MIN and MAX are where an unbounded twin is hardest to spot: both cells hold a real invoice date,
     * so nothing about the file looks wrong — it just answers a different question than its heading.
     */
    @Test
    fun `the first and last twins are bounded too`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                alphaWithFourInvoices()

                val saved = service.save(edgeTwinRequest(from, to), held, "admin")
                val row =
                    service
                        .run(saved.id, held)
                        .bytes
                        .asCsvLines()
                        .drop(1)
                        .single()

                assertEquals(
                    "Alpha GmbH;2026-04-01;2026-04-30;2026-03-05;2026-05-05",
                    row,
                    "April's first and last invoice, then the first and last of all time — and dates " +
                        "rather than the driver's timestamp, which the bound must not have disturbed",
                )
            }
        }
    }

    /**
     * With no Zeitraum set the twins agree, and that is correct rather than a regression.
     *
     * "All time" is what no bound means, so the scoped column has nothing to narrow and answers what
     * the unscoped one answers. The test exists so a later reading of `periodScoped` cannot start
     * inventing a bound — a default range of "this year", say — that the operator never asked for.
     */
    @Test
    fun `with no Zeitraum set the twins agree`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                alphaWithFourInvoices()

                val saved = service.save(countTwinRequest(dateFrom = null, dateTo = null), held, "admin")
                val row =
                    service
                        .run(saved.id, held)
                        .bytes
                        .asCsvLines()
                        .drop(1)
                        .single()

                assertEquals(
                    "Alpha GmbH;4;4",
                    row,
                    "an unbounded definition scopes nothing, so the '(Zeitraum)' column reports every " +
                        "invoice — the same figure its twin reports",
                )
            }
        }
    }

    /**
     * The aggregate cap, driven at construction.
     *
     * Every aggregate is one correlated subquery per exported row, so a definition's aggregate count
     * multiplies the query's real cost in a way its column count does not. The refusal names both
     * numbers, because "remove some" is only actionable if the operator knows how many to remove.
     */
    @Test
    fun `a definition past the aggregate cap is refused, naming the count`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                alphaWithFourInvoices()

                val capped = CustomExportServiceImpl(maxAggregateColumns = 1)
                val saved = service.save(countTwinRequest(from, to), held, "admin")

                val thrown = failureOf { capped.run(saved.id, held) }
                assertTrue(thrown is PosException, "expected a refusal, but got: $thrown")
                assertContains(
                    thrown.message.orEmpty(),
                    "2 aggregate columns",
                    message = "the refusal names how many aggregate columns the export selected",
                )
                assertContains(
                    thrown.message.orEmpty(),
                    "limit of 1",
                    message = "and the limit it exceeded, so the operator knows what to cut back to",
                )
            }
        }
    }

    /**
     * The cap is applied at **save**, not only at run.
     *
     * A definition stored past the cap is worse than a refused save: the operator finds out when they
     * press *Herunterladen*, and what they have saved is permanently un-runnable until somebody edits
     * it. The same refusal, the same message, from the same function — a second spelling is how a save
     * path and a run path end up disagreeing about one definition.
     */
    @Test
    fun `a definition past the aggregate cap is refused at save, with the run message`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                alphaWithFourInvoices()

                val capped = CustomExportServiceImpl(maxAggregateColumns = 1)

                val thrown = failureOf { capped.save(countTwinRequest(from, to), held, "admin") }
                assertTrue(thrown is PosException, "expected a refusal, but got: $thrown")
                assertContains(
                    thrown.message.orEmpty(),
                    "2 aggregate columns",
                    message = "the save refusal names how many aggregate columns were ticked",
                )
                assertContains(
                    thrown.message.orEmpty(),
                    "limit of 1",
                    message = "and the limit, in the same shape the run refusal uses",
                )
                assertTrue(
                    capped.list().none { it.name == "Rechnungen je Partner" },
                    "a refused save must store nothing — a half-written definition is the defect, not the fix",
                )
            }
        }
    }

    /**
     * Ordinary columns are **not** capped by the aggregate limit.
     *
     * They are read off a row the query was fetching anyway and cost nothing per row, so the cheap
     * kind of width must stay free. Seven plain columns under an aggregate cap of zero: if the guard
     * ever counted resolved columns instead of aggregate ones, this run would be refused.
     */
    @Test
    fun `ordinary columns are not capped`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                alphaWithFourInvoices()

                val capped = CustomExportServiceImpl(maxAggregateColumns = 0)
                val saved = service.save(sevenPlainColumnsRequest(), held, "admin")
                val lines = capped.run(saved.id, held).bytes.asCsvLines()

                assertEquals(2, lines.size, "heading plus the one partner row")
                assertEquals(
                    7,
                    lines.first().split(";").size,
                    "all seven ordinary columns survive an aggregate cap of zero",
                )
            }
        }
    }

    /** One partner, created inside April, with four invoices: two inside the range and two outside. */
    private fun alphaWithFourInvoices() {
        val alpha = partner(firstShop(), name = "Alpha GmbH")
        val supplier = supplier(alpha)
        invoice(supplier, issuedOn = "2026-03-05T09:00", gross = "100.00")
        invoice(supplier, issuedOn = "2026-04-01T00:00", gross = "20.50")
        invoice(supplier, issuedOn = "2026-04-30T23:59", gross = "9.50")
        invoice(supplier, issuedOn = "2026-05-05T09:00", gross = "7.00")
    }

    private fun countTwinRequest(
        dateFrom: String?,
        dateTo: String?,
    ) = twinRequest("Rechnungen je Partner", listOf(scopedCountKey to "Anzahl Zeitraum", countKey to "Anzahl gesamt"), dateFrom, dateTo)

    private fun sumTwinRequest(
        dateFrom: String?,
        dateTo: String?,
    ) = twinRequest("Rechnungssummen", listOf(scopedSumKey to "Summe Zeitraum", sumKey to "Summe gesamt"), dateFrom, dateTo)

    private fun edgeTwinRequest(
        dateFrom: String?,
        dateTo: String?,
    ) = twinRequest(
        "Erste und letzte Rechnung",
        listOf(
            scopedFirstKey to "Erste Zeitraum",
            scopedLastKey to "Letzte Zeitraum",
            firstKey to "Erste gesamt",
            lastKey to "Letzte gesamt",
        ),
        dateFrom,
        dateTo,
    )

    /** Partner name first, then the aggregate keys in the order given. */
    private fun twinRequest(
        name: String,
        aggregates: List<Pair<String, String>>,
        dateFrom: String?,
        dateTo: String?,
    ) = CustomExportDto.SaveDefinitionRequestDto(
        name = name,
        subjectKey = "partner",
        fields =
            listOf(CustomExportDto.DefinitionFieldDto("partner.name", 0, "Firma")) +
                aggregates.mapIndexed { index, (key, heading) ->
                    CustomExportDto.DefinitionFieldDto(key, index + 1, heading)
                },
        dateFrom = dateFrom,
        dateTo = dateTo,
    )

    /** More plain columns than the aggregate cap will be set to, and not one aggregate among them. */
    private fun sevenPlainColumnsRequest() =
        CustomExportDto.SaveDefinitionRequestDto(
            name = "Partnerstammdaten",
            subjectKey = "partner",
            fields =
                listOf(
                    "partner.name",
                    "partner.legal_name",
                    "partner.vat_id",
                    "partner.tax_number",
                    "partner.street",
                    "partner.zip",
                    "partner.city",
                ).mapIndexed { index, key -> CustomExportDto.DefinitionFieldDto(key, index, key) },
        )

    private fun firstShop(): ShopInfo = transaction { ShopInfo.all().first() }

    /** Created **inside** the Zeitraum: the outer date filter reads this column, not the invoice's. */
    private fun partner(
        shop: ShopInfo,
        name: String,
    ): EntityID<Long> =
        transaction {
            BusinessPartnerTable
                .insert {
                    it[BusinessPartnerTable.shopId] = shop.id
                    it[BusinessPartnerTable.name] = name
                    it[BusinessPartnerTable.isActive] = true
                    it[BusinessPartnerTable.createdAt] = LocalDateTime.parse("2026-04-02T10:00")
                }[BusinessPartnerTable.id]
        }

    private fun supplier(partner: EntityID<Long>): EntityID<Long> =
        transaction {
            SupplierTable
                .insert {
                    it[SupplierTable.partnerId] = partner
                    it[SupplierTable.creditorNo] = "70${partner.value}"
                }[SupplierTable.id]
        }

    private fun invoice(
        supplier: EntityID<Long>,
        issuedOn: String,
        gross: String,
    ) {
        transaction {
            InvoiceTable.insert {
                it[InvoiceTable.supplierId] = supplier
                it[InvoiceTable.issueDate] = LocalDateTime.parse(issuedOn)
                it[InvoiceTable.taxInclusiveTotal] = BigDecimal(gross)
                it[InvoiceTable.createdAt] = LocalDateTime.parse("2026-04-02T10:00")
            }
        }
    }

    /** `assertFailsWith` takes no suspending block, and a JUnit 5 test ending in one is silently skipped. */
    private suspend fun failureOf(block: suspend () -> Unit): Throwable? = runCatching { block() }.exceptionOrNull()
}
