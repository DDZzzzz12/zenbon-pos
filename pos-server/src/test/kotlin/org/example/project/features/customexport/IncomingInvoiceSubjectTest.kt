package org.example.project.features.customexport

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.customexport.CustomExportDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.features.customexport.data.service.CustomExportServiceImpl
import org.example.project.features.customexport.domain.catalogue.ExportCatalogue
import org.example.project.features.document.database.tables.InvoiceBookingTable
import org.example.project.features.document.database.tables.InvoiceTable
import org.example.project.features.supplier.data.repository.SupplierRepositoryImpl
import org.example.project.features.supplier.domain.models.Supplier
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The Eingangsrechnungen subject, end to end through a saved definition.
 *
 * It is the first subject that is not one table, so it is the first that can prove two things
 * `partner` could not:
 *
 * - **the join**, which the operator never chooses and therefore never sees. A join condition that
 *   reads plausibly and resolves the wrong column produces a file that looks right and is wrong, so
 *   the assertion has to be on the rendered bytes rather than on the declaration.
 * - **`MONEY`**, whose rule — a negative amount reaches Excel unguarded, or the column will not sum —
 *   has until now only been asserted against the rendering function directly.
 */
@DisplayName("Incoming invoice export subject")
class IncomingInvoiceSubjectTest {
    private val service = CustomExportServiceImpl()
    private val suppliers = SupplierRepositoryImpl()

    /** Everything the `invoice` subject's fields require. */
    private val held = setOf(Capability.CREDITOR_READ)

    @Test
    fun `the creditor's company name is resolved through the join, not read off the invoice`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                val metro = supplier(name = "Metro AG", vatId = "DE123456789")
                // `seller_name` on the invoice deliberately disagrees with the party's name: that
                // column is what the supplier asserted on this one document, and the export must
                // carry the company we resolved it to. If the join were declared over the snapshot
                // column instead, this test is the only thing that would notice.
                invoice(number = "R-1001", supplier = metro, sellerName = "METRO Cash & Carry")

                val rows = runDefinition(numberAndSellerName())

                assertEquals(
                    listOf("R-1001;Metro AG"),
                    rows,
                    "the Lieferant column comes from business_partner.name, reached through supplier",
                )
            }
        }
    }

    @Test
    fun `a credit note reaches the file as a negative number, unguarded, so the column still sums`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                invoice(number = "G-7", supplier = supplier(name = "Metro AG"), gross = BigDecimal("-12.50"))

                val rows = runDefinition(numberAndGross())

                assertEquals(
                    listOf("G-7;-12,50"),
                    rows,
                    "scale 2 and a decimal comma, and — the part that matters — no leading apostrophe: " +
                        "a formula-guarded '-12,50 imports into German Excel as text that will not sum",
                )
            }
        }
    }

    @Test
    fun `'Nur offene' keeps what is still owed and drops what is settled or already paid`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                val metro = supplier(name = "Metro AG")
                invoice(number = "OFFEN", supplier = metro, gross = BigDecimal("119.00"), openAmount = BigDecimal("119.00"))
                // Settled to zero. The residual definition must stop at that stated zero rather than
                // coalescing past it to the gross, which would resurrect the invoice as fully unpaid.
                invoice(number = "BEZAHLT", supplier = metro, gross = BigDecimal("50.00"), openAmount = BigDecimal.ZERO)
                // Still carries a residual, but we have booked it as paid — the second half of the
                // definition, and the half that needs the invoice_booking join.
                invoice(
                    number = "GEBUCHT",
                    supplier = metro,
                    gross = BigDecimal("80.00"),
                    openAmount = BigDecimal("80.00"),
                    paidAt = LocalDateTime.parse("2026-04-01T09:00"),
                )

                val all = runDefinition(numberAndSellerName())
                assertEquals(3, all.size, "without the toggle every invoice is exported")

                val open = runDefinition(numberAndSellerName().copy(enabledToggles = listOf("invoice.unpaid_only")))
                assertEquals(listOf("OFFEN;Metro AG"), open, "'Nur offene' is applied, not merely stored")
            }
        }
    }

    @Test
    fun `an invoice with no creditor yet is still exported, with the creditor columns blank`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                invoice(number = "R-2002", supplier = supplier(name = "Metro AG"))
                // The join is a LEFT join precisely so this row survives: an unresolved invoice is
                // unresolved *work*, and it is the row an operator chasing a pile of scans most wants
                // to see. An inner join would drop it, and a shorter file looks like a shorter file.
                invoice(number = "UNZUGEORDNET", supplier = null)

                val rows = runDefinition(numberAndSellerName())

                assertEquals(
                    listOf("R-2002;Metro AG", "UNZUGEORDNET;"),
                    rows.sorted(),
                    "the unresolved invoice is present with an empty Lieferant, never absent",
                )
            }
        }
    }

    /**
     * The subject's declarations, asserted where a reader can find them.
     *
     * `shopColumn` is null and that is deliberate — nothing in the invoice chain carries a shop, so
     * there is no column to narrow on. Asserting the null rather than leaving it undeclared is what
     * makes a later "why is this export not shop-scoped?" land on the reasoning instead of on a
     * missing line.
     */
    @Test
    fun `the subject is registered, ranges over the issue date, and declares no shop column`() {
        val subject = assertNotNull(ExportCatalogue.subject("invoice"))

        assertEquals("Eingangsrechnungen", subject.label)
        assertEquals(InvoiceTable.issueDate.name, subject.dateColumn?.name)
        assertNull(subject.shopColumn, "no table in the invoice chain carries a shop — see the subject's KDoc")
    }

    /**
     * The path key the party's name is derived under.
     *
     * Spelled out rather than looked up: this is what a saved definition stores, so a test that read
     * it back off the catalogue would agree with any key the walk happened to produce, including a
     * changed one that silently breaks every stored export.
     */
    private val partnerName = "supplier:via:supplier_id.business_partner:via:partner_id.name"

    /** Rechnungsnummer, then the party name the join has to resolve. */
    private fun numberAndSellerName() =
        CustomExportDto.SaveDefinitionRequestDto(
            name = "Eingangsrechnungen",
            subjectKey = "invoice",
            fields =
                listOf(
                    CustomExportDto.DefinitionFieldDto("invoice.invoice_number", 0, "Nr"),
                    CustomExportDto.DefinitionFieldDto(partnerName, 1, "Lieferant"),
                ),
        )

    /** Rechnungsnummer, then the one MONEY field in the catalogue. */
    private fun numberAndGross() =
        numberAndSellerName().copy(
            fields =
                listOf(
                    CustomExportDto.DefinitionFieldDto("invoice.invoice_number", 0, "Nr"),
                    CustomExportDto.DefinitionFieldDto("invoice.tax_inclusive_total", 1, "Bruttobetrag"),
                ),
        )

    /** Save, run, and hand back the data rows — the heading row is [CustomExportRunTest]'s business. */
    private suspend fun runDefinition(request: CustomExportDto.SaveDefinitionRequestDto): List<String> {
        val saved = service.save(request, held, "admin")
        return service
            .run(saved.id, held)
            .bytes
            .asCsvLines()
            .drop(1)
    }

    private fun supplier(
        name: String,
        vatId: String? = null,
    ): Supplier = transaction { suppliers.create(name = name, vatId = vatId) }

    /**
     * Inserted through the table rather than a DAO: `created_at` and `issue_date` are set explicitly
     * so no test depends on when it ran, and `supplier_id` has to be settable to null, which is the
     * case the left join exists for.
     */
    private fun invoice(
        number: String,
        supplier: Supplier?,
        sellerName: String? = null,
        gross: BigDecimal? = null,
        openAmount: BigDecimal? = null,
        paidAt: LocalDateTime? = null,
    ) {
        val stamp = LocalDateTime.parse("2026-03-01T10:00")
        transaction {
            val invoiceId =
                InvoiceTable.insertAndGetId {
                    it[InvoiceTable.invoiceNumber] = number
                    it[InvoiceTable.supplierId] = supplier?.id
                    it[InvoiceTable.sellerName] = sellerName
                    it[InvoiceTable.taxInclusiveTotal] = gross
                    it[InvoiceTable.openAmount] = openAmount
                    it[InvoiceTable.issueDate] = stamp
                    it[InvoiceTable.createdAt] = stamp
                }
            // Only when we have actually booked it: an invoice with no booking row at all is unpaid,
            // which is exactly what the left join has to render as a NULL paid_at.
            if (paidAt != null) {
                InvoiceBookingTable.insert {
                    it[InvoiceBookingTable.invoiceId] = invoiceId
                    it[InvoiceBookingTable.paidAt] = paidAt
                }
            }
        }
    }
}
