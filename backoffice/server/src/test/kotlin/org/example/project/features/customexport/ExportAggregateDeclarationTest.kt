package org.example.project.features.customexport

import org.example.project.core.domain.enums.auth.Capability
import org.example.project.features.customexport.domain.catalogue.AggregateFunction
import org.example.project.features.customexport.domain.catalogue.ExportAggregate
import org.example.project.features.customexport.domain.catalogue.ExportFieldType
import org.example.project.features.customexport.domain.catalogue.ExportTable
import org.example.project.features.customexport.domain.catalogue.ExportTableRegistry
import org.example.project.features.document.database.tables.InvoiceTable
import org.example.project.features.supplier.database.tables.SupplierBankAccountTable
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The declaration-time guards on a to-many aggregate.
 *
 * They are checked in `init` and therefore fire at class initialisation — server start and every test
 * run — rather than on the export that finally reads the bad declaration. That is the whole point of
 * the guards, and it is why this class constructs deliberately broken aggregates instead of asserting
 * on behaviour further downstream: the failure is meant to happen here, before anything is stored.
 */
@DisplayName("Export aggregate declaration")
class ExportAggregateDeclarationTest {
    @Test
    fun `a COUNT declared with a column is refused`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                ExportAggregate(
                    key = "count",
                    function = AggregateFunction.COUNT,
                    column = SupplierBankAccountTable.iban,
                    label = "Anzahl",
                    type = ExportFieldType.NUMBER,
                )
            }
        // The message has to say *why*, because `count(x)` skipping NULLs is the trap: it compiles,
        // it runs, and it silently undercounts.
        assertTrue(failure.message.orEmpty().contains("counts related rows"), failure.message.orEmpty())
        assertTrue(failure.message.orEmpty().contains("NULL"), failure.message.orEmpty())
    }

    @Test
    fun `SUM, LIST, MIN and MAX are each refused without a column`() {
        listOf(AggregateFunction.SUM, AggregateFunction.LIST, AggregateFunction.MIN, AggregateFunction.MAX)
            .forEach { function ->
                val failure =
                    assertFailsWith<IllegalArgumentException>("$function must not be declarable without a column") {
                        ExportAggregate(
                            key = "agg",
                            function = function,
                            column = null,
                            label = "Egal",
                            type = ExportFieldType.TEXT,
                        )
                    }
                assertTrue(failure.message.orEmpty().contains("needs the column"), failure.message.orEmpty())
            }
    }

    /** Without an order the same definition renders its values differently on the next run. */
    @Test
    fun `a LIST with no explicit order defaults to the aggregated column`() {
        val aggregate =
            ExportAggregate(
                key = "list",
                function = AggregateFunction.LIST,
                column = SupplierBankAccountTable.iban,
                label = "IBANs",
                type = ExportFieldType.TEXT,
            )
        assertEquals(SupplierBankAccountTable.iban, aggregate.orderColumn)
    }

    /** An explicit order column wins over the default, and a non-LIST has no order at all. */
    @Test
    fun `an explicit order is kept and other functions carry none`() {
        val ordered =
            ExportAggregate(
                key = "list",
                function = AggregateFunction.LIST,
                column = SupplierBankAccountTable.iban,
                label = "IBANs",
                type = ExportFieldType.TEXT,
                orderColumn = SupplierBankAccountTable.bic,
            )
        assertEquals(SupplierBankAccountTable.bic, ordered.orderColumn)

        val summed =
            ExportAggregate(
                key = "sum",
                function = AggregateFunction.SUM,
                column = InvoiceTable.taxInclusiveTotal,
                label = "Summe",
                type = ExportFieldType.MONEY,
            )
        assertNull(summed.orderColumn, "a single reduced value has nothing to order")
    }

    /** `.` separates a column field's path from its column name; the two namespaces stay disjoint. */
    @Test
    fun `a key containing a dot is refused`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                ExportAggregate(
                    key = "invoice.count",
                    function = AggregateFunction.COUNT,
                    column = null,
                    label = "Anzahl",
                    type = ExportFieldType.NUMBER,
                )
            }
        assertTrue(failure.message.orEmpty().contains("'.'"), failure.message.orEmpty())
    }

    @Test
    fun `a blank key is refused`() {
        assertFailsWith<IllegalArgumentException> {
            ExportAggregate(
                key = "  ",
                function = AggregateFunction.COUNT,
                column = null,
                label = "Anzahl",
                type = ExportFieldType.NUMBER,
            )
        }
        Unit
    }

    @Test
    fun `two aggregates of one table may not share a key`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                ExportTable(
                    table = SupplierBankAccountTable,
                    label = "Bankverbindung",
                    capability = Capability.CREDITOR_READ,
                    columns = emptyList(),
                    aggregates =
                        listOf(
                            ExportAggregate("count", AggregateFunction.COUNT, null, "Anzahl", ExportFieldType.NUMBER),
                            ExportAggregate("count", AggregateFunction.COUNT, null, "Nochmal", ExportFieldType.NUMBER),
                        ),
                )
            }
        assertTrue(failure.message.orEmpty().contains("'count' twice"), failure.message.orEmpty())
    }

    /** A subquery over the wrong table answers a question nobody asked, and says nothing about it. */
    @Test
    fun `an aggregate reading another table's column is refused`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                ExportTable(
                    table = SupplierBankAccountTable,
                    label = "Bankverbindung",
                    capability = Capability.CREDITOR_READ,
                    columns = emptyList(),
                    aggregates =
                        listOf(
                            ExportAggregate(
                                key = "sum",
                                function = AggregateFunction.SUM,
                                column = InvoiceTable.taxInclusiveTotal,
                                label = "Summe",
                                type = ExportFieldType.MONEY,
                            ),
                        ),
                )
            }
        assertTrue(failure.message.orEmpty().contains("another table"), failure.message.orEmpty())
    }

    /** The same, reached through the order column rather than the aggregated one. */
    @Test
    fun `an aggregate ordered by another table's column is refused`() {
        assertFailsWith<IllegalArgumentException> {
            ExportTable(
                table = SupplierBankAccountTable,
                label = "Bankverbindung",
                capability = Capability.CREDITOR_READ,
                columns = emptyList(),
                aggregates =
                    listOf(
                        ExportAggregate(
                            key = "list",
                            function = AggregateFunction.LIST,
                            column = SupplierBankAccountTable.iban,
                            label = "IBANs",
                            type = ExportFieldType.TEXT,
                            orderColumn = InvoiceTable.invoiceNumber,
                        ),
                    ),
            )
        }
        Unit
    }

    /**
     * Over the real registry, not a fixture: the guards above only protect the tables somebody
     * remembers to construct in a test, and this is what covers the ones added next month.
     */
    @Test
    fun `every declared aggregate reads its own table and carries a dot-free key`() {
        val violations =
            ExportTableRegistry.entries.flatMap { entry ->
                entry.aggregates.flatMap { aggregate ->
                    buildList {
                        if (aggregate.key.contains('.')) add("${entry.table.tableName}: key '${aggregate.key}'")
                        listOfNotNull(aggregate.column, aggregate.orderColumn)
                            .filter { it.table != entry.table }
                            .forEach { add("${entry.table.tableName}.${aggregate.key} reads ${it.table.tableName}.${it.name}") }
                    }
                }
            }
        assertTrue(violations.isEmpty(), "$violations")
    }

    /** The two entries this slice declares, spelled out so a silent deletion is visible. */
    @Test
    fun `the registry declares the invoice and bank-account aggregates`() {
        val invoice = assertNotNull(ExportTableRegistry.entryFor(InvoiceTable))
        assertEquals(InvoiceTable.issueDate.name, assertNotNull(invoice.periodColumn).name)
        assertEquals(
            listOf("count", "sum", "first", "last"),
            invoice.aggregates.map { it.key },
        )
        assertEquals(ExportFieldType.MONEY, assertNotNull(invoice.aggregates.first { it.key == "sum" }).type)

        val bank = assertNotNull(ExportTableRegistry.entryFor(SupplierBankAccountTable))
        assertNull(bank.periodColumn, "an IBAN is not an event; a Zeitraum over it answers nothing")
        assertEquals(listOf("list", "count"), bank.aggregates.map { it.key })
        assertEquals(SupplierBankAccountTable.iban, bank.aggregates.first { it.key == "list" }.orderColumn)
    }
}
