package org.example.project.features.customexport

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.customexport.CustomExportDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.salesinvoice.RevenueOrigin
import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.features.customexport.data.service.CustomExportServiceImpl
import org.example.project.features.customexport.domain.catalogue.Cardinality
import org.example.project.features.customexport.domain.catalogue.ExportCatalogue
import org.example.project.features.customexport.domain.catalogue.ExportField
import org.example.project.features.debtor.database.tables.DebtorTable
import org.example.project.features.partner.database.tables.BusinessPartnerTable
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceTable
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Registering the sales-invoice tables gave the **partner** subject outgoing-invoice summaries with
 * no line written in `PartnerSubject`.
 *
 * That is the "register once, reachable everywhere" promise the whole graph design rests on, and it
 * is exactly the kind of property that stops being true quietly: a plain index turned unique, a
 * `MAX_DEPTH` change, a registry entry withdrawn, and a creditor list an operator built last quarter
 * comes back with a column missing or — worse — with the same figure on every row. So it is asserted
 * here rather than left to be noticed in someone's spreadsheet.
 *
 * The keys are **literals**, because they are what an `export_definition_field` row stores. Rebuilt
 * from the path key they would agree with whatever the walk emitted, a changed key included.
 */
@DisplayName("Partner exports gain outgoing-invoice summaries for free")
class PartnerOutgoingInvoiceAggregateTest {
    private val service = CustomExportServiceImpl()

    /** `business_partner` → `debtor` → `sales_invoice` crosses three tables and costs all three. */
    private val held = setOf(Capability.CREDITOR_READ, Capability.DEBTOR_READ, Capability.INVOICE_READ)

    private val salesInvoicePath = "debtor:by:partner_id.sales_invoice:by:debtor_id"
    private val countKey = "$salesInvoicePath~count"
    private val sumKey = "$salesInvoicePath~sum"

    @Test
    fun `the partner subject offers the outgoing-invoice aggregates`() {
        val subject = assertNotNull(ExportCatalogue.subject("partner"))
        val keys = subject.allOfferings.map { it.key }

        listOf(
            "$salesInvoicePath~count@period",
            "$salesInvoicePath~count",
            "$salesInvoicePath~sum@period",
            "$salesInvoicePath~sum",
            "$salesInvoicePath~first@period",
            "$salesInvoicePath~first",
            "$salesInvoicePath~last@period",
            "$salesInvoicePath~last",
        ).forEach { key ->
            assertTrue(key in keys, "the aggregate '$key' is not offered; offered: $keys")
        }
    }

    /**
     * And it is to-many, which is what confines it to aggregates.
     *
     * `sales_invoice.debtor_id` carries a plain index, so one customer has many invoices. Were that
     * index ever made unique the path would flip to to-one, its columns would start being offered,
     * and a joined export would emit a partner once per invoice with every other cell duplicated and
     * nothing in the file to say the row count is not a partner count. The assertion on
     * [ExportField] is by type, so it cannot be satisfied by a rename.
     */
    @Test
    fun `the path is to-many, so it contributes aggregates and never columns`() {
        val subject = assertNotNull(ExportCatalogue.subject("partner"))
        val path = assertNotNull(subject.aggregatePaths.firstOrNull { it.key == salesInvoicePath })

        assertEquals(Cardinality.MANY, path.cardinality)
        // The heading names the route, not just the far table — `business_partner` reaches
        // `sales_invoice` through the debtor role. See `SchemaGraph.groupLabelsFor`.
        assertEquals("Debitor-Rolle → Ausgangsrechnung", path.label)
        assertTrue(path.fields.isEmpty(), "a to-many path contributes no column: ${path.fields.map { it.key }}")
        assertTrue(subject.paths.none { it.key == salesInvoicePath }, "and it is never in the joinable list")
        assertTrue(
            subject.allFields.filterIsInstance<ExportField>().none { it.key.startsWith(salesInvoicePath) },
            "no ordinary column of sales_invoice may be ticked on a partner export",
        )
    }

    /**
     * The invariant, through a real run: **one row per partner, whatever the invoices look like.**
     *
     * Three partners — one with three outgoing invoices, one with a debtor role and none, one with no
     * role at all. Joined, the first would come out three times and the file would carry five data
     * rows. Both halves are asserted, the row count *and* the per-partner figures, because a subquery
     * correlated on the wrong hop keeps the row count perfectly while giving every partner the same
     * total.
     */
    @Test
    fun `an outgoing-invoice aggregate does not multiply the partner rows`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                val shop = firstShop()
                val alpha = partner(shop, name = "Alpha GmbH")
                val beta = partner(shop, name = "Beta AG")
                partner(shop, name = "Gamma KG")
                val alphaDebtor = debtor(shop, alpha)
                debtor(shop, beta)
                salesInvoice(shop, alphaDebtor, number = "AR-1", gross = "100.00")
                salesInvoice(shop, alphaDebtor, number = "AR-2", gross = "20.50")
                salesInvoice(shop, alphaDebtor, number = "AR-3", gross = "9.50")

                val saved = service.save(countAndSumRequest(), held, "admin")
                val result = service.run(saved.id, held)
                val lines = result.bytes.asCsvLines()

                assertEquals(
                    3,
                    result.rowCount,
                    "one row per partner: three outgoing invoices on one of them must not become three rows",
                )
                assertEquals(4, lines.size, "heading plus three data rows, counted in the produced file")
                assertEquals(
                    setOf("Alpha GmbH;3;130,00", "Beta AG;0;", "Gamma KG;0;"),
                    lines.drop(1).toSet(),
                    "each partner counts its own invoices through its own debtor role — and a partner " +
                        "with none reads 0 with a blank sum, because a summed nothing is not a total of 0,00",
                )
            }
        }
    }

    /** Partner name, the count of its outgoing invoices, and their total. */
    private fun countAndSumRequest() =
        CustomExportDto.SaveDefinitionRequestDto(
            name = "Umsatz je Partner",
            subjectKey = "partner",
            fields =
                listOf(
                    CustomExportDto.DefinitionFieldDto("partner.name", 0, "Firma"),
                    CustomExportDto.DefinitionFieldDto(countKey, 1, "Anzahl"),
                    CustomExportDto.DefinitionFieldDto(sumKey, 2, "Umsatz"),
                ),
        )

    private fun firstShop(): ShopInfo = transaction { ShopInfo.all().first() }

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

    /** The customer role, which is the middle table every outgoing-invoice aggregate correlates through. */
    private fun debtor(
        shop: ShopInfo,
        partner: EntityID<Long>,
    ): EntityID<Long> =
        transaction {
            DebtorTable
                .insert {
                    it[DebtorTable.shopId] = shop.id
                    it[DebtorTable.partnerId] = partner
                }[DebtorTable.id]
        }

    private fun salesInvoice(
        shop: ShopInfo,
        debtor: EntityID<Long>,
        number: String,
        gross: String,
    ) {
        val stamp = LocalDateTime.parse("2026-03-01T10:00")
        transaction {
            SalesInvoiceTable.insert {
                it[SalesInvoiceTable.shopId] = shop.id
                it[SalesInvoiceTable.invoiceNumber] = number
                it[SalesInvoiceTable.status] = SalesInvoiceStatus.ISSUED
                it[SalesInvoiceTable.revenueOrigin] = RevenueOrigin.STANDALONE
                it[SalesInvoiceTable.debtorId] = debtor
                it[SalesInvoiceTable.buyerName] = "Kunde $number"
                it[SalesInvoiceTable.issueDate] = stamp
                it[SalesInvoiceTable.serviceDate] = stamp
                it[SalesInvoiceTable.taxInclusiveTotal] = BigDecimal(gross)
                it[SalesInvoiceTable.createdAt] = stamp
            }
        }
    }
}
