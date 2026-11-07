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
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Running a definition that ticks an **aggregate** over a to-many relation.
 *
 * The property every test here turns on is the **row count**. A to-many relation joined instead of
 * aggregated does not fail: it returns a file that looks entirely plausible and has more rows than
 * there are records, every other column duplicated, with nothing in the file to say so. A correlated
 * scalar subquery cannot do that — it returns one value per root row whatever the related data looks
 * like — so the assertions here are on how many rows came out and on the exact cells, never on SQL.
 *
 * `LIST` is absent on purpose: it is `string_agg`, which H2 does not have, so it is proved on the
 * `@Tag("postgres")` leg instead. A test of it here would fail, correctly.
 */
@DisplayName("Custom export aggregate run")
class AggregateRunTest {
    private val service = CustomExportServiceImpl()

    /** Everything the `partner` subject's fields and its invoice aggregates require. */
    private val held = setOf(Capability.CREDITOR_READ)

    /** The invoices of a partner's creditor role: two hops, the second of them to-many. */
    private val invoicePath = "supplier:by:partner_id.invoice:by:supplier_id"

    private val countKey = "$invoicePath~count"
    private val sumKey = "$invoicePath~sum"
    private val firstKey = "$invoicePath~first"
    private val lastKey = "$invoicePath~last"

    /**
     * The invariant the whole aggregate design exists for.
     *
     * Three partners, one of them holding three invoices. Joined, that partner would come out three
     * times and the file would have five data rows; aggregated, it comes out once with a `3` in the
     * count column. Both halves are asserted — the row count *and* the value — because a subquery that
     * returned the wrong number would keep the row count perfectly.
     */
    @Test
    fun `an aggregate does not multiply rows`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                val shop = firstShop()
                val alpha = partner(shop, name = "Alpha GmbH")
                partner(shop, name = "Beta AG")
                partner(shop, name = "Gamma KG")
                val supplier = supplier(alpha)
                invoice(supplier, issuedOn = "2026-03-05T09:00", gross = "100.00")
                invoice(supplier, issuedOn = "2026-04-05T09:00", gross = "20.50")
                invoice(supplier, issuedOn = "2026-05-05T09:00", gross = "9.50")

                val saved = service.save(countRequest(), held, "admin")
                val result = service.run(saved.id, held)
                val lines = result.bytes.asCsvLines()

                assertEquals(
                    3,
                    result.rowCount,
                    "one row per partner: three invoices on one of them must not become three rows",
                )
                assertEquals(
                    4,
                    lines.size,
                    "heading plus three data rows, counted in the produced file rather than the DTO",
                )
                assertEquals(
                    setOf("Alpha GmbH;3", "Beta AG;0", "Gamma KG;0"),
                    lines.drop(1).toSet(),
                    "and the aggregate answers per partner, so the row that was not multiplied still " +
                        "carries the number the join would have produced rows for",
                )
            }
        }
    }

    /**
     * The correlation runs through the **middle** table, not only the last one.
     *
     * Two partners with a creditor role, one with invoices and one without, and a third with no role
     * at all. Each must count only its own invoices: a subquery correlated on the wrong hop would give
     * every partner the same total, which is a plausible-looking file and a wrong one.
     */
    @Test
    fun `a multi-hop aggregate correlates through the middle table`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                val shop = firstShop()
                val alpha = partner(shop, name = "Alpha GmbH")
                val beta = partner(shop, name = "Beta AG")
                partner(shop, name = "Gamma KG")
                val alphaSupplier = supplier(alpha)
                supplier(beta)
                invoice(alphaSupplier, issuedOn = "2026-03-05T09:00", gross = "100.00")
                invoice(alphaSupplier, issuedOn = "2026-04-05T09:00", gross = "20.50")

                val saved = service.save(countRequest(), held, "admin")
                val rows =
                    service
                        .run(saved.id, held)
                        .bytes
                        .asCsvLines()
                        .drop(1)

                assertEquals(
                    setOf("Alpha GmbH;2", "Beta AG;0", "Gamma KG;0"),
                    rows.toSet(),
                    "each partner counts its own invoices, reached through its own creditor role — and " +
                        "a creditor with no invoices reads 0, not a blank",
                )
            }
        }
    }

    /**
     * "No related rows" and "related rows totalling nothing" are different facts, and the file says so.
     *
     * COUNT is `0` because a count of nothing is zero. SUM, MIN and MAX are **blank**, because we
     * reduced nothing at all — a `0,00` in the sum column would tell an advisor the partner was
     * invoiced for nothing, which is a claim we are in no position to make. SQL gives this for free
     * (`count(*)` is 0, `sum(...)` is NULL); the test is here to keep a later coalesce from taking it
     * away.
     */
    @Test
    fun `an aggregate over no rows counts zero and sums to a blank cell`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                val shop = firstShop()
                val alpha = partner(shop, name = "Alpha GmbH")
                partner(shop, name = "Beta AG")
                val supplier = supplier(alpha)
                invoice(supplier, issuedOn = "2026-03-05T09:00", gross = "100.00")
                invoice(supplier, issuedOn = "2026-05-05T09:00", gross = "20.50")

                val saved = service.save(everyAggregateRequest(), held, "admin")
                val rows =
                    service
                        .run(saved.id, held)
                        .bytes
                        .asCsvLines()
                        .drop(1)

                assertEquals(
                    "Alpha GmbH;2;120,50;2026-03-05;2026-05-05",
                    rows.first { it.startsWith("Alpha GmbH") },
                    "the partner with invoices: a count, a decimal-comma total, and the first and last " +
                        "invoice dates as dates rather than as the driver's timestamp",
                )
                assertEquals(
                    "Beta AG;0;;;",
                    rows.first { it.startsWith("Beta AG") },
                    "the partner without: zero invoices, and three empty cells — a summed nothing is " +
                        "not a total of 0,00",
                )
            }
        }
    }

    /**
     * A stored aggregate resolves, so the definition is not reported broken.
     *
     * The trap this guards is narrow and expensive: the brokenness check reads its own list of what
     * the catalogue offers, and one reading only the *columns* would mark every saved aggregate as
     * unknown. The definition would run perfectly and the operator's list would tell them it is
     * broken — advice they would act on by deleting it.
     */
    @Test
    fun `a saved definition naming an aggregate is not reported broken`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                partner(firstShop(), name = "Alpha GmbH")

                val saved = service.save(everyAggregateRequest(), held, "admin")
                val listed = service.list().single { it.id == saved.id }

                assertFalse(saved.isBroken, "the definition just saved names only keys the catalogue offers")
                assertTrue(saved.unknownKeys.isEmpty(), "and so has no unknown keys: ${saved.unknownKeys}")
                assertFalse(listed.isBroken, "and it reads back the same way in the operator's list")
                assertEquals(
                    listOf("partner.name", countKey, sumKey, firstKey, lastKey),
                    listed.fields.map { it.fieldKey },
                    "with every aggregate key stored verbatim, in position order",
                )
            }
        }
    }

    /** Partner name plus the count of its invoices — the aggregate, not a column of them. */
    private fun countRequest() =
        CustomExportDto.SaveDefinitionRequestDto(
            name = "Rechnungen je Partner",
            subjectKey = "partner",
            fields =
                listOf(
                    CustomExportDto.DefinitionFieldDto("partner.name", 0, "Firma"),
                    CustomExportDto.DefinitionFieldDto(countKey, 1, "Anzahl"),
                ),
        )

    /** Every aggregate H2 can run, so COUNT's zero and the others' blanks appear in one file. */
    private fun everyAggregateRequest() =
        CustomExportDto.SaveDefinitionRequestDto(
            name = "Rechnungsuebersicht",
            subjectKey = "partner",
            fields =
                listOf(
                    CustomExportDto.DefinitionFieldDto("partner.name", 0, "Firma"),
                    CustomExportDto.DefinitionFieldDto(countKey, 1, "Anzahl"),
                    CustomExportDto.DefinitionFieldDto(sumKey, 2, "Summe"),
                    CustomExportDto.DefinitionFieldDto(firstKey, 3, "Erste"),
                    CustomExportDto.DefinitionFieldDto(lastKey, 4, "Letzte"),
                ),
        )

    /** The shop `CustomExportServiceImpl` files definitions under — the first one created. */
    private fun firstShop(): ShopInfo = transaction { ShopInfo.all().first() }

    /** Inserted through the table, not a DAO, so `createdAt` is ours rather than the clientDefault's. */
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
                    it[BusinessPartnerTable.createdAt] = LocalDateTime.parse("2026-03-01T10:00")
                }[BusinessPartnerTable.id]
        }

    /** The creditor role, which is the middle table every invoice aggregate correlates through. */
    private fun supplier(partner: EntityID<Long>): EntityID<Long> =
        transaction {
            SupplierTable
                .insert {
                    it[SupplierTable.partnerId] = partner
                    it[SupplierTable.creditorNo] = "70${partner.value}"
                }[SupplierTable.id]
        }

    /** A booked incoming invoice: the far end of the to-many hop, and the rows being aggregated. */
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
                it[InvoiceTable.createdAt] = LocalDateTime.parse("2026-03-01T10:00")
            }
        }
    }
}
