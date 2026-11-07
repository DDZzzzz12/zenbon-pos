package org.example.project.features.customexport

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.customexport.CustomExportDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.features.customexport.data.service.CustomExportServiceImpl
import org.example.project.features.customexport.domain.catalogue.Cardinality
import org.example.project.features.customexport.domain.catalogue.ExportCatalogue
import org.example.project.features.customexport.domain.catalogue.ExportRelationPath
import org.example.project.features.customexport.domain.catalogue.ExportSubject
import org.example.project.features.customexport.domain.catalogue.RelationHop
import org.example.project.features.customexport.domain.catalogue.SchemaGraph
import org.example.project.features.customexport.domain.catalogue.subjects.PartnerSubject
import org.example.project.features.document.database.tables.InvoiceTable
import org.example.project.features.partner.database.tables.BusinessPartnerTable
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.supplier.database.tables.SupplierTable
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Running a definition that reaches a **related** table, and the three guards that bound what such a
 * run may do.
 *
 * The one property every test here is really about is the **row count**. A relation joined wrongly
 * does not fail; it returns a file that looks right and is not — more rows than there are records, or
 * a cell holding another route's value. So the assertions are on how many rows came out and on the
 * exact cells, never on the SQL.
 */
@DisplayName("Custom export relation run")
class RelationRunTest {
    private val service = CustomExportServiceImpl()

    /** Everything the `partner` subject's fields require, root and relation alike. */
    private val held = setOf(Capability.CREDITOR_READ)

    /** The creditor role, reached from the partner by the foreign key the role holds. */
    private val creditorNoKey = "supplier:by:partner_id.creditor_no"

    @Test
    fun `a to-one relation does not multiply rows`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                val shop = firstShop()
                val withRole = partner(shop, name = "Alpha GmbH")
                partner(shop, name = "Beta AG")
                partner(shop, name = "Gamma KG")
                supplier(withRole, creditorNo = "70001")

                val saved = service.save(relationRequest(), held, "admin")
                val result = service.run(saved.id, held)

                assertEquals(
                    3,
                    result.rowCount,
                    "one row per partner: a to-one join must leave the record count alone",
                )
                assertEquals(
                    4,
                    result.bytes.asCsvLines().size,
                    "heading plus three data rows, counted in the produced file rather than the DTO",
                )
            }
        }
    }

    @Test
    fun `a partner without the related row still exports, with a blank cell`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                val shop = firstShop()
                val withRole = partner(shop, name = "Alpha GmbH")
                partner(shop, name = "Beta AG")
                supplier(withRole, creditorNo = "70001")

                val saved = service.save(relationRequest(), held, "admin")
                val rows =
                    service
                        .run(saved.id, held)
                        .bytes
                        .asCsvLines()
                        .drop(1)

                assertEquals(
                    setOf("Alpha GmbH;70001", "Beta AG;"),
                    rows.toSet(),
                    "LEFT, not inner: a partner with no creditor role is still a partner, and an " +
                        "inner join would drop exactly the rows an operator is hunting for",
                )
            }
        }
    }

    @Test
    fun `selecting only root fields is unaffected by the related rows`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                val shop = firstShop()
                val withRole = partner(shop, name = "Alpha GmbH")
                partner(shop, name = "Beta AG")

                val saved = service.save(rootOnlyRequest(), held, "admin")
                val before = service.run(saved.id, held).bytes.asCsvLines()

                supplier(withRole, creditorNo = "70001")
                val after = service.run(saved.id, held).bytes.asCsvLines()

                assertEquals(
                    listOf("Firma", "Alpha GmbH", "Beta AG"),
                    before,
                    "a root-only definition joins nothing",
                )
                assertEquals(
                    before,
                    after,
                    "the creditor role must not reach a definition that never asked for it — a table " +
                        "nobody ticked costs a join on every row and can only change the answer",
                )
            }
        }
    }

    @Test
    fun `a definition past the join cap is refused, naming the count`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                val shop = firstShop()
                val withRole = partner(shop, name = "Alpha GmbH")
                supplier(withRole, creditorNo = "70001")

                // The cap is a constructor parameter, so the refusal is provable without a schema
                // wide enough to reach eight tables. Zero refuses the very first join.
                val capped = CustomExportServiceImpl(maxJoinedTables = 0)
                val saved = service.save(relationRequest(), held, "admin")

                val thrown = failureOf { capped.run(saved.id, held) }
                assertTrue(thrown is PosException, "expected a refusal, but got: $thrown")
                assertContains(
                    thrown.message.orEmpty(),
                    "1 related tables",
                    message = "the refusal names how many tables the export would join",
                )
                assertContains(
                    thrown.message.orEmpty(),
                    "limit of 0",
                    message = "and the limit it exceeded, so the operator knows what to cut back to",
                )
            }
        }
    }

    /**
     * The to-many guard, now driven at **construction** rather than through
     * [CustomExportServiceImpl.joinSourceFor].
     *
     * `run` cannot reach it and that is by design: `ExportSubject.paths` filters to
     * [Cardinality.ONE], so a to-many field is never offered, `save` refuses it as unknown, and even a
     * key written straight into the table is refused earlier by `resolveColumns` as one the catalogue
     * no longer knows. Testing it through `run` would therefore pass on the wrong refusal.
     *
     * [ExportSubject.alwaysJoined] was the door that *is* open — it is taken straight from
     * `SchemaGraph.pathsFrom`, which returns to-many paths too, and `IncomingInvoiceSubject` filters
     * it by target table and not by cardinality. So this is not a contrived situation: it is one
     * missing filter in a future subject. `ExportSubject`'s `init` now closes it at construction,
     * which is the earliest moment there is: a subject is a constant written in code, so a bad one
     * should fail on the first attempt to build it rather than on the hundredth run of an export that
     * has been quietly multiplying rows ever since.
     *
     * That makes the runner's own `requireNoToManyPath` unreachable through any `ExportSubject` that
     * can be constructed at all. It stays as defence in depth — it is the guard that would still
     * catch a to-many path arriving by some future route — and this test now asserts the refusal that
     * actually fires, because a test asserting the unreachable one would prove nothing.
     */
    @Test
    fun `a to-many path in alwaysJoined is refused at construction, saying it must be aggregated`() {
        val toMany =
            SchemaGraph
                .pathsFrom(BusinessPartnerTable)
                .first { it.cardinality == Cardinality.MANY }

        val thrown =
            runCatching { PartnerSubject.subject.copy(alwaysJoined = listOf(toMany)) }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException, "expected a refusal, but got: $thrown")
        assertContains(thrown.message.orEmpty(), toMany.key, message = "the refusal names the relation")
        assertContains(
            thrown.message.orEmpty(),
            "aggregated",
            message =
                "and says what has to happen instead of the join: a to-many belongs in a subquery, " +
                    "because joining it multiplies every other column",
        )
    }

    /** Every subject the catalogue ships still constructs — the new `init` refuses none of them. */
    @Test
    fun `the registered subjects survive the alwaysJoined cardinality guard`() {
        assertTrue(ExportCatalogue.subjects.size >= 2, "the catalogue lost a subject")
        ExportCatalogue.subjects.forEach { subject ->
            subject.alwaysJoined.forEach { path ->
                assertEquals(
                    Cardinality.ONE,
                    path.cardinality,
                    "subject '${subject.key}' always-joins the to-many relation '${path.key}'",
                )
            }
        }
    }

    /**
     * The same-table-two-routes guard, tested in isolation.
     *
     * No registered subject can produce the pair: from `business_partner` the only route to `supplier`
     * is the role's own `partner_id`, and from `invoice` it is `supplier_id` — a root yields one route
     * per table today. So the paths are built by hand out of real hops. The check must hold before
     * such a pair becomes reachable, because the failure it prevents is a wrong cell rather than an
     * error.
     */
    @Test
    fun `two paths reaching one table by different foreign keys are refused`() {
        val fromPartner = pathTo(SupplierTable, from = BusinessPartnerTable.id, to = SupplierTable.partnerId)
        val fromInvoice = pathTo(SupplierTable, from = InvoiceTable.supplierId, to = SupplierTable.id)

        assertEquals(
            SupplierTable,
            ExportRelationPath.tableJoinedTwoWays(listOf(fromPartner, fromInvoice)),
            "the two routes are detected by their hops, not by their target alone",
        )
        assertNull(
            ExportRelationPath.tableJoinedTwoWays(listOf(fromPartner, fromPartner)),
            "the same table by the same hop is the prefix case, which is deduped rather than refused",
        )

        val subject = PartnerSubject.subject.copy(alwaysJoined = listOf(fromPartner, fromInvoice))
        val thrown = runCatching { service.joinSourceFor(subject, emptyList()) }.exceptionOrNull()
        assertTrue(thrown is PosException, "expected a refusal, but got: $thrown")
        assertContains(
            thrown.message.orEmpty(),
            SupplierTable.tableName,
            message = "the refusal names the table that would be joined twice",
        )
    }

    /** Partner name plus the creditor number, which lives one hop away on the creditor role. */
    private fun relationRequest() =
        CustomExportDto.SaveDefinitionRequestDto(
            name = "Kreditoren",
            subjectKey = "partner",
            fields =
                listOf(
                    CustomExportDto.DefinitionFieldDto("partner.name", 0, "Firma"),
                    CustomExportDto.DefinitionFieldDto(creditorNoKey, 1, "Kreditorennummer"),
                ),
        )

    private fun rootOnlyRequest() =
        CustomExportDto.SaveDefinitionRequestDto(
            name = "Firmen",
            subjectKey = "partner",
            fields = listOf(CustomExportDto.DefinitionFieldDto("partner.name", 0, "Firma")),
        )

    /** A single-hop path, for the isolation tests — the fields are irrelevant to a join check. */
    private fun pathTo(
        target: Table,
        from: Column<*>,
        to: Column<*>,
    ) = ExportRelationPath(
        key = "${target.tableName}:test:${to.name}",
        label = target.tableName,
        hops = listOf(RelationHop(target = target, from = from, to = to)),
        cardinality = Cardinality.ONE,
        fields = emptyList(),
        requiredCapabilities = setOf(Capability.CREDITOR_READ),
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

    private fun supplier(
        partner: EntityID<Long>,
        creditorNo: String,
    ) {
        transaction {
            SupplierTable.insert {
                it[SupplierTable.partnerId] = partner
                it[SupplierTable.creditorNo] = creditorNo
            }
        }
    }

    /**
     * `assertFailsWith` cannot take a suspending block, and a test body ending in one is silently
     * skipped by JUnit 5 anyway. Catching the throwable ourselves avoids both.
     */
    private suspend fun failureOf(block: suspend () -> Unit): Throwable? = runCatching { block() }.exceptionOrNull()
}
