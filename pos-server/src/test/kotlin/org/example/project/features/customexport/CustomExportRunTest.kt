package org.example.project.features.customexport

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.customexport.CustomExportDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.export.csv.CsvValue
import org.example.project.export.csv.SpreadsheetCsvWriter
import org.example.project.features.customexport.data.service.CustomExportServiceImpl
import org.example.project.features.customexport.domain.catalogue.ExportFieldType
import org.example.project.features.customexport.domain.models.ExportDefinitionField
import org.example.project.features.partner.database.tables.BusinessPartnerTable
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Running a saved definition into a CSV.
 *
 * This is where every rule the earlier slices declared has to actually be applied: the operator's
 * headings and column order, the filters, the capability check against the **runner**, and the shop
 * constraint the operator never asked for and cannot switch off.
 *
 * Every assertion reads the produced bytes, not an intermediate structure — a rule that holds in a
 * list of cells and not in the file is not held.
 */
@DisplayName("Custom export run")
class CustomExportRunTest {
    private val service = CustomExportServiceImpl()

    /** Everything the `partner` subject's fields require. */
    private val held = setOf(Capability.CREDITOR_READ)

    @Test
    fun `the file carries the operator's headings, in position order, then one row per partner`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                val shop = firstShop()
                partner(shop, name = "Alpha GmbH", city = "Aachen", vatId = "DE111")
                partner(shop, name = "Beta AG", city = "Bonn", vatId = "DE222")

                val saved = service.save(threeColumnRequest(), held, "admin")
                val result = service.run(saved.id, held)

                val lines = result.bytes.asCsvLines()
                assertEquals(
                    // The operator's headings, not the catalogue labels ("Name", "USt-IdNr.", "Ort"),
                    // and in `position` order, not the order the request listed them in.
                    listOf("Firma;USt-IdNr.;Ort"),
                    lines.take(1),
                    "the heading row is what the operator typed, in the order they chose",
                )
                assertEquals(
                    setOf("Alpha GmbH;DE111;Aachen", "Beta AG;DE222;Bonn"),
                    lines.drop(1).toSet(),
                )
                assertEquals(2, result.rowCount)
                assertFalse(result.truncated)
            }
        }
    }

    @Test
    fun `the date range narrows the rows`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                val shop = firstShop()
                partner(shop, name = "Alt GmbH", createdAt = LocalDateTime.parse("2024-03-01T10:00"))
                partner(shop, name = "Neu GmbH", createdAt = LocalDateTime.parse("2026-03-01T10:00"))

                val saved =
                    service.save(
                        threeColumnRequest().copy(
                            dateFrom = "2026-01-01T00:00",
                            dateTo = "2026-12-31T23:59",
                        ),
                        held,
                        "admin",
                    )

                val rows =
                    service
                        .run(saved.id, held)
                        .bytes
                        .asCsvLines()
                        .drop(1)
                assertEquals(listOf("Neu GmbH;;"), rows, "only the partner inside the range is written")
            }
        }
    }

    @Test
    fun `rows come out ordered by the subject's date column, oldest first, ties by id`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                val shop = firstShop()
                // Inserted out of date order, so primary-key (insertion) order is NOT chronological:
                // without an ORDER BY the file follows the plan, which is what QA admin16 NOTE-4 saw.
                partner(shop, name = "Mitte GmbH", createdAt = LocalDateTime.parse("2025-06-01T10:00"))
                partner(shop, name = "Neu GmbH", createdAt = LocalDateTime.parse("2026-03-01T10:00"))
                partner(shop, name = "Alt GmbH", createdAt = LocalDateTime.parse("2024-03-01T10:00"))
                partner(shop, name = "Neu Zwei GmbH", createdAt = LocalDateTime.parse("2026-03-01T10:00"))

                val saved = service.save(threeColumnRequest(), held, "admin")
                val rows =
                    service
                        .run(saved.id, held)
                        .bytes
                        .asCsvLines()
                        .drop(1)

                assertEquals(
                    listOf("Alt GmbH;;", "Mitte GmbH;;", "Neu GmbH;;", "Neu Zwei GmbH;;"),
                    rows,
                    "rows follow created_at ascending; the two sharing a timestamp keep insertion (id) order",
                )
            }
        }
    }

    @Test
    fun `an enabled toggle narrows the rows`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                val shop = firstShop()
                partner(shop, name = "Aktiv GmbH", isActive = true)
                partner(shop, name = "Stillgelegt GmbH", isActive = false)

                val saved =
                    service.save(
                        threeColumnRequest().copy(enabledToggles = listOf("partner.active_only")),
                        held,
                        "admin",
                    )

                val rows =
                    service
                        .run(saved.id, held)
                        .bytes
                        .asCsvLines()
                        .drop(1)
                assertEquals(listOf("Aktiv GmbH;;"), rows, "'Nur aktive' is applied, not merely stored")
            }
        }
    }

    @Test
    fun `a runner who may not see a field is refused, and told which capability is missing`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                val shop = firstShop()
                partner(shop, name = "Alpha GmbH")

                // Saved by someone who held CREDITOR_READ; run by someone who holds nothing. That is
                // the normal shape of the problem — a definition is shared and outlives its author —
                // and the answer is a refusal, never a file with the gated column left blank.
                val saved = service.save(threeColumnRequest(), held, "admin")

                val thrown = failureOf { service.run(saved.id, emptySet()) }
                assertTrue(thrown is PosException, "expected a refusal, but got: $thrown")
                assertContains(
                    thrown.message.orEmpty(),
                    Capability.CREDITOR_READ.name,
                    message = "the refusal names the capability the runner is missing",
                )
            }
        }
    }

    @Test
    fun `a definition naming a field the catalogue lost is refused as broken, listing the key`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                val shop = firstShop()
                partner(shop, name = "Alpha GmbH")

                val saved = service.save(threeColumnRequest(), held, "admin")
                // The save path refuses an unknown key, so the only way to build a definition that
                // has gone stale is to write the stale key straight into the table — which is what a
                // catalogue change does to a definition saved last year.
                transaction {
                    ExportDefinitionField.all().first { it.position == 1 }.fieldKey = "partner.retired_column"
                }

                val thrown = failureOf { service.run(saved.id, held) }
                assertTrue(thrown is PosException, "expected a refusal, but got: $thrown")
                assertContains(
                    thrown.message.orEmpty(),
                    "partner.retired_column",
                    message = "the refusal names the key, so the operator knows which column to fix",
                )
            }
        }
    }

    @Test
    fun `another shop's partners are not in the file`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                val ours = firstShop()
                val theirs = TestData.createShopInfo(shopName = "Zweitfiliale", companyName = "Zweit GmbH")
                partner(ours, name = "Unsere GmbH")
                partner(theirs, name = "Fremde GmbH")

                val saved = service.save(threeColumnRequest(), held, "admin")
                val rows =
                    service
                        .run(saved.id, held)
                        .bytes
                        .asCsvLines()
                        .drop(1)

                assertEquals(
                    listOf("Unsere GmbH;;"),
                    rows,
                    "the shop constraint is applied, not merely declared on the subject: a partner " +
                        "belonging to another shop must never reach this file",
                )
            }
        }
    }

    @Test
    fun `more rows than the cap are truncated, and the operator is told`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                val shop = firstShop()
                repeat(5) { partner(shop, name = "Partner $it") }

                // The cap is a constructor parameter, so the truncation path is provable without
                // inserting a hundred thousand rows.
                val capped = CustomExportServiceImpl(rowCap = 2)
                val saved = service.save(threeColumnRequest(), held, "admin")
                val result = capped.run(saved.id, held)

                assertEquals(2, result.rowCount, "exactly the cap is written, never the extra probe row")
                assertEquals(
                    2,
                    result.bytes
                        .asCsvLines()
                        .drop(1)
                        .size,
                )
                assertTrue(result.truncated, "an operator not told the file is short reconciles against a short list")
            }
        }
    }

    @Test
    fun `a negative amount reaches the file unguarded, so the column still sums`() {
        // Asserted against the renderer and the writer directly: no catalogue subject declares a
        // MONEY field yet — the invoice subject in the next task is the first — and the rule is worth
        // proving before the first one arrives rather than after.
        val cell = service.renderCell(ExportFieldType.MONEY, BigDecimal("-12.5"))
        assertEquals(CsvValue.Numeric("-12,50"), cell, "scale 2, decimal comma, and Numeric rather than Text")

        val writer = SpreadsheetCsvWriter()
        writer.writeRow(listOf(cell))
        val line = writer.toByteArray().asCsvLines().single()
        assertEquals(
            "-12,50",
            line,
            "no leading apostrophe: a Text cell would come out '-12,50 and German Excel would " +
                "import the column as text that will not sum",
        )
    }

    @Test
    fun `the file name comes from the definition's name and never looks like a DATEV export`() {
        assertEquals("Kreditoren_Uebersicht.csv", service.fileNameFor("Kreditoren Übersicht"))
        assertEquals("Export.csv", service.fileNameFor("   "))
        assertEquals(
            "Export_EXTF_Kreditoren.csv",
            service.fileNameFor("EXTF Kreditoren"),
            "EXTF is DATEV's prefix; a custom extract must not be mistakable for a DATEV file",
        )
    }

    @Test
    fun `an unknown definition id is not found`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                val thrown = failureOf { service.run(9_999L, held) }
                assertTrue(thrown is PosException, "expected a refusal, but got: $thrown")
            }
        }
    }

    /** Name / VAT id / city, headings the operator chose, deliberately handed over out of order. */
    private fun threeColumnRequest() =
        CustomExportDto.SaveDefinitionRequestDto(
            name = "Kreditoren",
            subjectKey = "partner",
            fields =
                listOf(
                    CustomExportDto.DefinitionFieldDto("partner.city", 2, "Ort"),
                    CustomExportDto.DefinitionFieldDto("partner.name", 0, "Firma"),
                    CustomExportDto.DefinitionFieldDto("partner.vat_id", 1, "USt-IdNr."),
                ),
        )

    /** The shop `CustomExportServiceImpl` files definitions under — the first one created. */
    private fun firstShop(): ShopInfo = transaction { ShopInfo.all().first() }

    private fun partner(
        shop: ShopInfo,
        name: String,
        city: String? = null,
        vatId: String? = null,
        isActive: Boolean = true,
        createdAt: LocalDateTime = LocalDateTime.parse("2026-03-01T10:00"),
    ) {
        // Inserted through the table rather than a DAO: this test needs `createdAt` set explicitly,
        // and the column carries a clientDefault a DAO write would apply instead.
        transaction {
            BusinessPartnerTable.insert {
                it[BusinessPartnerTable.shopId] = shop.id
                it[BusinessPartnerTable.name] = name
                it[BusinessPartnerTable.city] = city
                it[BusinessPartnerTable.vatId] = vatId
                it[BusinessPartnerTable.isActive] = isActive
                it[BusinessPartnerTable.createdAt] = createdAt
            }
        }
    }

    /**
     * `assertFailsWith` cannot take a suspending block, and a test body ending in one is silently
     * skipped by JUnit 5 anyway. Catching the throwable ourselves avoids both.
     */
    private suspend fun failureOf(block: suspend () -> Unit): Throwable? = runCatching { block() }.exceptionOrNull()
}
