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
import org.example.project.features.debtor.database.tables.DebtorTable
import org.example.project.features.partner.database.tables.BusinessPartnerTable
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceBookingTable
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Ausgangsrechnungen subject — the AR mirror of [IncomingInvoiceSubjectTest].
 *
 * Every key here is a **literal**. Rebuilding one from the path key and the separator the production
 * code uses would agree with whatever that code emitted, including a changed key that silently breaks
 * every stored export, and these strings are exactly what an `export_definition_field` row holds.
 */
@DisplayName("Sales invoice export subject")
class SalesInvoiceSubjectTest {
    private val service = CustomExportServiceImpl()

    /** Everything this subject's own fields require; the partner hops cost the two role reads too. */
    private val held = setOf(Capability.INVOICE_READ, Capability.CREDITOR_READ, Capability.DEBTOR_READ)

    /**
     * The customer's company name, two hops out — the mirror of the Lieferant field the AP subject
     * resolves through `supplier`. This is the field the subject exists to make reachable: from the
     * partner root the same table sits three hops away, past [
     * org.example.project.features.customexport.domain.catalogue.SchemaGraph.MAX_DEPTH].
     */
    private val customerName = "debtor:via:debtor_id.business_partner:via:partner_id.name"

    @Test
    fun `the subject is registered, ranges over the issue date, and is scoped to the shop`() {
        val subject = assertNotNull(ExportCatalogue.subject("sales_invoice"))

        assertEquals("Ausgangsrechnungen", subject.label)
        assertEquals(SalesInvoiceTable, subject.root)
        assertEquals(SalesInvoiceTable.issueDate, subject.dateColumn)
        assertEquals(SalesInvoiceTable.shopId, subject.shopColumn)
    }

    /**
     * No base predicate, and the reason is worth pinning rather than leaving to be rediscovered: a
     * cancelled invoice is still an invoice that existed and still holds a number out of the §14
     * sequence. Excluding it by default would be a silent omission — a shorter file looks like a
     * shorter file, not like a filter nobody asked for.
     */
    @Test
    fun `nothing is excluded unconditionally, and the three toggles are declared`() {
        val subject = assertNotNull(ExportCatalogue.subject("sales_invoice"))

        assertNull(subject.basePredicate, "a cancelled invoice is still an invoice that existed")
        assertEquals(
            listOf("sales_invoice.issued_only", "sales_invoice.exclude_cancelled", "sales_invoice.open_only"),
            subject.toggles.map { it.key },
        )
        assertEquals(listOf("Nur gestellte", "Ohne stornierte", "Nur offene"), subject.toggles.map { it.label })
        assertTrue(
            subject.alwaysJoined.isEmpty(),
            "every toggle reads a column of the root, so no path has to be pre-joined",
        )
    }

    /**
     * The subject's own columns carry no group, which is what puts them at the top of the picker
     * rather than under a heading repeating the subject's own name.
     */
    @Test
    fun `the root's own columns are ungrouped and the related ones are not`() {
        val subject = assertNotNull(ExportCatalogue.subject("sales_invoice"))
        val ungrouped = subject.allFields.filter { it.group == null }

        assertEquals(
            listOf(
                "sales_invoice.invoice_number",
                "sales_invoice.issue_date",
                "sales_invoice.service_date",
                "sales_invoice.due_date",
                "sales_invoice.buyer_name",
                "sales_invoice.buyer_vat_id",
                "sales_invoice.buyer_city",
                "sales_invoice.status",
                "sales_invoice.currency",
                "sales_invoice.tax_exclusive_total",
                "sales_invoice.tax_total",
                "sales_invoice.tax_inclusive_total",
                "sales_invoice.open_amount",
            ),
            ungrouped.map { it.key },
        )
        assertTrue(
            subject.allFields.filter { it.group != null }.isNotEmpty(),
            "and the related tables' columns do carry a heading, or the distinction proves nothing",
        )
    }

    /**
     * `sales_invoice_booking` is `uniqueIndex(salesInvoiceId)`, so the graph derives the relation as
     * to-one and its columns are offered as ordinary columns under their own heading. Had the index
     * been a plain one, the same table would silently have become an aggregate-only path — which is
     * the flip this assertion exists to notice.
     */
    @Test
    fun `the booking path is to-one and its columns are grouped under the booking heading`() {
        val subject = assertNotNull(ExportCatalogue.subject("sales_invoice"))
        val booking = assertNotNull(subject.paths.firstOrNull { it.target == SalesInvoiceBookingTable })

        assertEquals("sales_invoice_booking:by:sales_invoice_id", booking.key)
        assertEquals(Cardinality.ONE, booking.cardinality)
        assertEquals("Buchung (Ausgang)", booking.label)
        assertTrue(
            booking.fields.all { it.group == "Buchung (Ausgang)" },
            "a related table's columns sit under its own heading: ${booking.fields.map { it.group }}",
        )
        assertTrue("sales_invoice_booking:by:sales_invoice_id.revenue_account_no" in booking.fields.map { it.key })
    }

    /**
     * The interesting one: the customer's **company** name, resolved through the debtor role.
     *
     * `buyer_name` on the invoice is a snapshot of what we printed on that one document; the party's
     * name is master data one hop further out. Offering both is the point — but the two-hop one is
     * the field a partner-rooted export cannot reach and this subject can.
     */
    @Test
    fun `the two-hop debtor route offers the customer's company name`() {
        val subject = assertNotNull(ExportCatalogue.subject("sales_invoice"))

        assertTrue(
            customerName in subject.allFields.map { it.key },
            "offered: ${subject.allFields.map { it.key }}",
        )
        val field = subject.allFields.first { it.key == customerName }
        assertEquals("Name", field.label)
        // A two-hop heading names both hops, so the operator can see it is the *debtor's* partner
        // record rather than some other route to one.
        assertEquals("Debitor-Rolle → Geschäftspartner", field.group)
        assertEquals(
            setOf(Capability.INVOICE_READ, Capability.DEBTOR_READ, Capability.CREDITOR_READ),
            subject.capabilityRequirements()[customerName],
            "the path crosses three tables, so it costs all three capabilities — not the far one alone",
        )
    }

    /**
     * And through a real run, because a join condition that reads plausibly and resolves the wrong
     * column produces a file that looks right and is wrong.
     *
     * `buyer_name` deliberately disagrees with the party's name, exactly as the AP test's
     * `seller_name` does: if the join were declared over the snapshot column this is the only thing
     * that would notice.
     */
    @Test
    fun `the company name is resolved through the join, not read off the invoice`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                val shop = firstShop()
                val debtor = debtor(shop, partnerName = "Muster Handels GmbH")
                salesInvoice(shop, number = "AR-1001", debtor = debtor, buyerName = "Muster Handel (Filiale Nord)")
                // No debtor at all: a one-off guest gets a typed address, not a master-data record,
                // and the LEFT join has to keep that row with an empty company column rather than
                // dropping it.
                salesInvoice(shop, number = "AR-1002", debtor = null, buyerName = "Laufkundschaft")

                val rows = runDefinition(numberAndCustomerName())

                assertEquals(
                    listOf("AR-1001;Muster Handels GmbH", "AR-1002;"),
                    rows.sorted(),
                    "the company comes from business_partner.name through the debtor role, and the " +
                        "invoice without a debtor is present with a blank cell, never absent",
                )
            }
        }
    }

    /** The toggles filter on the one column they name, and are applied rather than merely stored. */
    @Test
    fun `the status toggles narrow the file, and nothing is dropped without one`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                val shop = firstShop()
                salesInvoice(shop, number = "ENTWURF", status = SalesInvoiceStatus.DRAFT)
                salesInvoice(shop, number = "GESTELLT", status = SalesInvoiceStatus.ISSUED)
                salesInvoice(shop, number = "STORNIERT", status = SalesInvoiceStatus.CANCELLED)

                assertEquals(
                    listOf("ENTWURF;", "GESTELLT;", "STORNIERT;"),
                    runDefinition(numberAndCustomerName()).sorted(),
                    "with no toggle every invoice is exported, the cancellation included",
                )
                assertEquals(
                    listOf("GESTELLT;"),
                    runDefinition(
                        numberAndCustomerName().copy(enabledToggles = listOf("sales_invoice.issued_only")),
                    ),
                )
                assertEquals(
                    listOf("ENTWURF;", "GESTELLT;"),
                    runDefinition(
                        numberAndCustomerName().copy(enabledToggles = listOf("sales_invoice.exclude_cancelled")),
                    ).sorted(),
                )
            }
        }
    }

    /**
     * "Nur offene" through a real run, because the whole point of the toggle is that it agrees with
     * the Rechnungsübersicht — and only an executed query can show that it does.
     *
     * The fixture is the cascade: a residual the writer computed, an invoice settled to zero, and
     * one the writer has never touched whose gross is what is owed. That last row is why the toggle
     * could not simply read `open_amount`: it is NULL there, and a naive filter would file the
     * customer who owes €119 as owing nothing while the export looked perfectly healthy.
     */
    @Test
    fun `the Nur offene toggle keeps the open invoices and drops the settled and the drafts`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                val shop = firstShop()
                salesInvoice(shop, number = "OFFEN-TEIL", openAmount = "40.00")
                salesInvoice(shop, number = "OFFEN-UNBERUEHRT", openAmount = null)
                salesInvoice(shop, number = "BEZAHLT", openAmount = "0.00")
                salesInvoice(shop, number = "ENTWURF", status = SalesInvoiceStatus.DRAFT, openAmount = "119.00")
                salesInvoice(shop, number = "STORNIERT", status = SalesInvoiceStatus.CANCELLED, openAmount = "119.00")

                assertEquals(
                    listOf("BEZAHLT;", "ENTWURF;", "OFFEN-TEIL;", "OFFEN-UNBERUEHRT;", "STORNIERT;"),
                    runDefinition(numberAndCustomerName()).sorted(),
                    "without the toggle nothing is filtered, or the toggle proves nothing",
                )
                assertEquals(
                    listOf("OFFEN-TEIL;", "OFFEN-UNBERUEHRT;"),
                    runDefinition(
                        numberAndCustomerName().copy(enabledToggles = listOf("sales_invoice.open_only")),
                    ).sorted(),
                    "a settled invoice is closed, a draft is not open but a draft, and an untouched " +
                        "residual falls back to the gross",
                )
            }
        }
    }

    private fun numberAndCustomerName() =
        CustomExportDto.SaveDefinitionRequestDto(
            name = "Ausgangsrechnungen",
            subjectKey = "sales_invoice",
            fields =
                listOf(
                    CustomExportDto.DefinitionFieldDto("sales_invoice.invoice_number", 0, "Nr"),
                    CustomExportDto.DefinitionFieldDto(customerName, 1, "Kunde"),
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

    private fun firstShop(): ShopInfo = transaction { ShopInfo.all().first() }

    /** A company with a debtor role — the middle table the two-hop name is resolved through. */
    private fun debtor(
        shop: ShopInfo,
        partnerName: String,
    ): EntityID<Long> =
        transaction {
            val partner =
                BusinessPartnerTable
                    .insert {
                        it[BusinessPartnerTable.shopId] = shop.id
                        it[BusinessPartnerTable.name] = partnerName
                        it[BusinessPartnerTable.isActive] = true
                        it[BusinessPartnerTable.createdAt] = LocalDateTime.parse("2026-03-01T10:00")
                    }[BusinessPartnerTable.id]
            DebtorTable
                .insert {
                    it[DebtorTable.shopId] = shop.id
                    it[DebtorTable.partnerId] = partner
                }[DebtorTable.id]
        }

    /** Inserted through the table so every date is ours rather than the clientDefault's. */
    private fun salesInvoice(
        shop: ShopInfo,
        number: String,
        debtor: EntityID<Long>? = null,
        buyerName: String = "Laufkundschaft",
        status: SalesInvoiceStatus = SalesInvoiceStatus.ISSUED,
        openAmount: String? = null,
    ) {
        val stamp = LocalDateTime.parse("2026-03-01T10:00")
        transaction {
            SalesInvoiceTable.insert {
                it[SalesInvoiceTable.shopId] = shop.id
                it[SalesInvoiceTable.invoiceNumber] = number
                it[SalesInvoiceTable.status] = status
                it[SalesInvoiceTable.revenueOrigin] = RevenueOrigin.STANDALONE
                it[SalesInvoiceTable.debtorId] = debtor
                it[SalesInvoiceTable.buyerName] = buyerName
                it[SalesInvoiceTable.issueDate] = stamp
                it[SalesInvoiceTable.serviceDate] = stamp
                it[SalesInvoiceTable.taxInclusiveTotal] = BigDecimal("119.00")
                // Left NULL by default, which is the state of every invoice the allocation writer
                // has not touched — and the one a bare `open_amount > 0` filter would drop.
                it[SalesInvoiceTable.openAmount] = openAmount?.let(::BigDecimal)
                it[SalesInvoiceTable.createdAt] = stamp
            }
        }
    }
}
