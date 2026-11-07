package org.example.project.features.customexport

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.customexport.CustomExportDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.core.domain.enums.order.ItemStatus
import org.example.project.core.domain.enums.product.ProductType
import org.example.project.core.domain.enums.salesinvoice.RevenueOrigin
import org.example.project.core.domain.enums.salesinvoice.SalesInvoiceStatus
import org.example.project.core.domain.enums.voucher.VoucherStatus
import org.example.project.features.customer.database.tables.CustomerTable
import org.example.project.features.customexport.data.service.CustomExportServiceImpl
import org.example.project.features.debtor.database.tables.DebtorTable
import org.example.project.features.order.database.tables.OrderItemTable
import org.example.project.features.partner.database.tables.BusinessPartnerTable
import org.example.project.features.product.database.tables.ProductCategoryTable
import org.example.project.features.product.database.tables.ProductTable
import org.example.project.features.salesinvoice.database.tables.SalesInvoiceTable
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.voucher.database.tables.VoucherTable
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals

/**
 * The three Stammdaten subjects, run end to end through the CSV writer.
 *
 * They were registered with catalogue-level proof only — the keys, the labels, the capabilities and
 * the cardinalities were asserted, and nothing ever produced a file. That is a real gap rather than a
 * tidiness one: this feature's whole promise is that the *file* is right, and a catalogue test
 * exercises neither `renderCell`, nor the decimal comma money path, nor the formula guard, nor the
 * row count that a to-many relation would silently multiply.
 *
 * So each test below seeds a couple of rows, saves a definition, runs it and asserts the **exact CSV
 * lines**. Rows are compared as a set wherever there is more than one, because the runner emits no
 * `ORDER BY` — the column order is the definition's and is asserted, the row order is the database's
 * and is not something to pin.
 */
@DisplayName("Stammdaten subjects, end to end")
class StammdatenRunTest {
    private val service = CustomExportServiceImpl()

    /**
     * Produkte: the article list with its Warengruppe, which is the point of registering the category
     * table rather than the `category_id` column — the file says "Speisen", not "17".
     *
     * `Preis` is the MONEY column: `product.price` is a `decimal(19,4)`, stored here as `23.8000`, and
     * it must reach the file as `23,80` — scale 2 and a decimal comma, or German Excel imports the
     * column as text that will not sum.
     */
    @Test
    fun `a Produkte export writes the article list with its category and a money column`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                val speisen = category("Speisen")
                val getraenke = category("Getränke")
                product(speisen, number = "ART-1", name = "Wiener Schnitzel", price = "23.8000")
                product(getraenke, number = "ART-2", name = "Apfelschorle", price = "4.5000")

                val saved = service.save(productRequest(), setOf(Capability.CATALOG_READ), "admin")
                val result = service.run(saved.id, setOf(Capability.CATALOG_READ))
                val lines = result.bytes.asCsvLines()

                assertEquals(2, result.rowCount, "one row per article")
                assertEquals(3, lines.size, "heading plus two data rows, counted in the produced file")
                assertEquals("Artikelnummer;Bezeichnung;Preis;Warengruppe", lines.first())
                assertEquals(
                    setOf(
                        "ART-1;Wiener Schnitzel;23,80;Speisen",
                        "ART-2;Apfelschorle;4,50;Getränke",
                    ),
                    lines.drop(1).toSet(),
                    "the price is scale 2 with a decimal comma, and the category arrives by name " +
                        "through the join rather than as its id",
                )
            }
        }
    }

    /**
     * Kunden, with the one money figure the subject has: `Umsatz`, summed over the outgoing invoices
     * reached through the customer's debtor role.
     *
     * That relation is to-many — `debtor.customer_id` carries a plain index — so it is the row count
     * that matters here as much as the value. A customer with two invoices must stay **one** row; a
     * joined to-many would give a plausible-looking file with three data rows and the customer's name
     * repeated.
     *
     * The second customer proves the other half of the contract: `0` for a count of nothing, and a
     * **blank** for a sum of nothing, because "no invoices" and "invoices totalling nothing" are
     * different facts.
     */
    @Test
    fun `a Kunden export stays one row per customer and carries the summed revenue`() {
        val held = setOf(Capability.CUSTOMER_LIST, Capability.DEBTOR_READ, Capability.INVOICE_READ)
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                val shop = firstShop()
                val anna = customer(number = "K-1", first = "Anna", last = "Berger")
                customer(number = "K-2", first = "Bruno", last = "Claus")
                val partner = partner(shop, "Berger GmbH")
                val debtor = debtor(shop, partner, anna)
                salesInvoice(shop, debtor, number = "AR-1", gross = "100.00")
                salesInvoice(shop, debtor, number = "AR-2", gross = "20.50")

                val saved = service.save(customerRequest(), held, "admin")
                val result = service.run(saved.id, held)
                val lines = result.bytes.asCsvLines()

                assertEquals(2, result.rowCount, "two customers, and the two invoices must not add a row")
                assertEquals(3, lines.size)
                assertEquals("Kundennummer;Nachname;Anzahl;Umsatz", lines.first())
                assertEquals(
                    setOf("K-1;Berger;2;120,50", "K-2;Claus;0;"),
                    lines.drop(1).toSet(),
                    "the customer with invoices carries a decimal-comma total; the one without reads " +
                        "0 and an empty cell, never 0,00",
                )
            }
        }
    }

    /**
     * Gutscheine: the outstanding-liability list.
     *
     * `Restwert` is the MONEY column and is `decimal(10,2)`, so this is the second of the two decimal
     * precisions the catalogue declares MONEY over — both must render the same way. The spent
     * voucher's `Restwert` of `0.00` is asserted as `0,00` rather than as a blank: a voucher with
     * nothing left on it is a different fact from a voucher we know nothing about, and only a value
     * read off a column can say which.
     *
     * Seeding one costs an `order_item`, because `voucher.order_item_id` is NOT NULL — a voucher in
     * this schema is always something that was sold. The chain is table session → product → VAT, all
     * of which the fixtures already build.
     */
    @Test
    fun `a Gutscheine export writes the issued and remaining values`() {
        val held = setOf(Capability.VOUCHER_REPORT)
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                val item = orderItem()
                voucher(item, number = "GS-1", original = "50.00", remaining = "50.00", VoucherStatus.ACTIVE)
                voucher(item, number = "GS-2", original = "25.00", remaining = "0.00", VoucherStatus.ACTIVE)

                val saved = service.save(voucherRequest(), held, "admin")
                val result = service.run(saved.id, held)
                val lines = result.bytes.asCsvLines()

                assertEquals(2, result.rowCount)
                assertEquals(3, lines.size)
                assertEquals("Nummer;Status;Ausgabewert;Restwert;Ausgegeben am", lines.first())
                assertEquals(
                    setOf(
                        "GS-1;ACTIVE;50,00;50,00;2026-03-01",
                        "GS-2;ACTIVE;25,00;0,00;2026-03-01",
                    ),
                    lines.drop(1).toSet(),
                    "both money columns carry a decimal comma at scale 2, and the issue date is a " +
                        "date rather than the driver's timestamp",
                )
            }
        }
    }

    private fun productRequest() =
        CustomExportDto.SaveDefinitionRequestDto(
            name = "Artikelliste",
            subjectKey = "produkt",
            fields =
                listOf(
                    CustomExportDto.DefinitionFieldDto("produkt.product_number", 0, "Artikelnummer"),
                    CustomExportDto.DefinitionFieldDto("produkt.name", 1, "Bezeichnung"),
                    CustomExportDto.DefinitionFieldDto("produkt.price", 2, "Preis"),
                    CustomExportDto.DefinitionFieldDto("product_category:via:category_id.name", 3, "Warengruppe"),
                ),
        )

    private fun customerRequest() =
        CustomExportDto.SaveDefinitionRequestDto(
            name = "Kundenumsatz",
            subjectKey = "kunde",
            fields =
                listOf(
                    CustomExportDto.DefinitionFieldDto("kunde.customer_number", 0, "Kundennummer"),
                    CustomExportDto.DefinitionFieldDto("kunde.last_name", 1, "Nachname"),
                    CustomExportDto.DefinitionFieldDto("debtor:by:customer_id.sales_invoice:by:debtor_id~count", 2, "Anzahl"),
                    CustomExportDto.DefinitionFieldDto("debtor:by:customer_id.sales_invoice:by:debtor_id~sum", 3, "Umsatz"),
                ),
        )

    private fun voucherRequest() =
        CustomExportDto.SaveDefinitionRequestDto(
            name = "Gutscheinliste",
            subjectKey = "gutschein",
            fields =
                listOf(
                    CustomExportDto.DefinitionFieldDto("gutschein.voucher_number", 0, "Nummer"),
                    CustomExportDto.DefinitionFieldDto("gutschein.status", 1, "Status"),
                    CustomExportDto.DefinitionFieldDto("gutschein.original_value", 2, "Ausgabewert"),
                    CustomExportDto.DefinitionFieldDto("gutschein.remaining_value", 3, "Restwert"),
                    CustomExportDto.DefinitionFieldDto("gutschein.created_at", 4, "Ausgegeben am"),
                ),
        )

    private fun firstShop(): ShopInfo = transaction { ShopInfo.all().first() }

    private fun category(name: String): EntityID<Int> =
        transaction {
            ProductCategoryTable
                .insert {
                    it[ProductCategoryTable.name] = name
                    it[ProductCategoryTable.sortPosition] = BigDecimal("1.00000")
                    it[ProductCategoryTable.updatedAt] = STAMP
                }[ProductCategoryTable.id]
        }

    private fun product(
        category: EntityID<Int>,
        number: String,
        name: String,
        price: String,
    ) {
        transaction {
            ProductTable.insert {
                it[ProductTable.productNumber] = number
                it[ProductTable.name] = name
                it[ProductTable.price] = BigDecimal(price)
                it[ProductTable.categoryId] = category
                it[ProductTable.productType] = ProductType.FOOD
                it[ProductTable.updatedAt] = STAMP
            }
        }
    }

    private fun customer(
        number: String,
        first: String,
        last: String,
    ): EntityID<UUID> =
        transaction {
            CustomerTable
                .insert {
                    it[CustomerTable.customerNumber] = number
                    it[CustomerTable.firstName] = first
                    it[CustomerTable.lastName] = last
                    it[CustomerTable.createdAt] = STAMP
                    it[CustomerTable.updatedAt] = STAMP
                }[CustomerTable.id]
        }

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
                    it[BusinessPartnerTable.createdAt] = STAMP
                }[BusinessPartnerTable.id]
        }

    /** The debtor role is the middle table the customer's revenue aggregate correlates through. */
    private fun debtor(
        shop: ShopInfo,
        partner: EntityID<Long>,
        customer: EntityID<UUID>,
    ): EntityID<Long> =
        transaction {
            DebtorTable
                .insert {
                    it[DebtorTable.shopId] = shop.id
                    it[DebtorTable.partnerId] = partner
                    it[DebtorTable.customerId] = customer
                }[DebtorTable.id]
        }

    private fun salesInvoice(
        shop: ShopInfo,
        debtor: EntityID<Long>,
        number: String,
        gross: String,
    ) {
        transaction {
            SalesInvoiceTable.insert {
                it[SalesInvoiceTable.shopId] = shop.id
                it[SalesInvoiceTable.invoiceNumber] = number
                it[SalesInvoiceTable.status] = SalesInvoiceStatus.ISSUED
                it[SalesInvoiceTable.revenueOrigin] = RevenueOrigin.STANDALONE
                it[SalesInvoiceTable.debtorId] = debtor
                it[SalesInvoiceTable.buyerName] = "Kunde $number"
                it[SalesInvoiceTable.issueDate] = STAMP
                it[SalesInvoiceTable.serviceDate] = STAMP
                it[SalesInvoiceTable.taxInclusiveTotal] = BigDecimal(gross)
                it[SalesInvoiceTable.createdAt] = STAMP
            }
        }
    }

    /** `voucher.order_item_id` is NOT NULL, so a voucher needs a sold line to hang off. */
    private fun orderItem(): EntityID<Long> {
        val vat = TestData.createDefaultVatRates().first()
        val category = category("Gutscheine")
        val session = TestData.createTableWithSession(tableNumber = "GS1").session
        return transaction {
            val product =
                ProductTable
                    .insert {
                        it[ProductTable.productNumber] = "GS-PRD"
                        it[ProductTable.name] = "Gutschein"
                        it[ProductTable.price] = BigDecimal("50.0000")
                        it[ProductTable.categoryId] = category
                        it[ProductTable.productType] = ProductType.FOOD
                        it[ProductTable.updatedAt] = STAMP
                    }[ProductTable.id]
            OrderItemTable
                .insert {
                    it[OrderItemTable.sessionId] = session.id
                    it[OrderItemTable.productId] = product
                    it[OrderItemTable.unitPrice] = BigDecimal("50.0000")
                    it[OrderItemTable.itemStatus] = ItemStatus.PAID
                    it[OrderItemTable.vatId] = vat.id
                    it[OrderItemTable.takeAway] = false
                }[OrderItemTable.id]
        }
    }

    private fun voucher(
        orderItem: EntityID<Long>,
        number: String,
        original: String,
        remaining: String,
        status: VoucherStatus,
    ) {
        transaction {
            VoucherTable.insert {
                it[VoucherTable.orderItem] = orderItem
                it[VoucherTable.voucherNumber] = number
                it[VoucherTable.originalValue] = BigDecimal(original)
                it[VoucherTable.remainingValue] = BigDecimal(remaining)
                it[VoucherTable.status] = status
                it[VoucherTable.createdAt] = STAMP
            }
        }
    }

    private companion object {
        /** One fixed instant for every seeded row, so a date cell is an assertion rather than a guess. */
        val STAMP: LocalDateTime = LocalDateTime.parse("2026-03-01T10:00")
    }
}
