package org.example.project.features.customexport

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.customexport.CustomExportDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.features.customexport.data.service.CustomExportServiceImpl
import org.example.project.features.customexport.domain.catalogue.ExportCatalogue
import org.example.project.features.customexport.domain.catalogue.ExportRelationPath
import org.example.project.features.customexport.domain.catalogue.SchemaGraph
import org.example.project.features.debtor.database.tables.DebtorTable
import org.example.project.features.partner.database.tables.BusinessPartnerTable
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.supplier.database.tables.SupplierTable
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Reaching a table costs its capability, whether or not a column of it is read.
 *
 * The rule: **if the query touches a table, the caller must hold that table's capability.** A path
 * through a table one may not read leaks it by inference — on `invoice → supplier →
 * business_partner` the field lives on the partner, but the query touches `supplier`, so a value at
 * the far end proves the creditor row exists and a blank cell says it does not. Either answer is one
 * about `supplier`, a table never shown to that caller.
 *
 * ## The registry can now express the discriminating case
 *
 * Every entry in [org.example.project.features.customexport.domain.catalogue.ExportTableRegistry]
 * used to carry [Capability.CREDITOR_READ], so every reachable path's `requiredCapabilities` was the
 * single-element set `{CREDITOR_READ}` — exactly what the old per-field check compared against. No
 * reachable path could tell the two rules apart, and the discriminating test had to hand-set a
 * required set on a copied path, which proves the *method* and not the *catalogue*.
 *
 * `debtor` carries [Capability.DEBTOR_READ], so the real path `business_partner → debtor` requires
 * two: `DEBTOR_READ` for the table its fields live on, `CREDITOR_READ` for the root the query starts
 * from. Every test below now runs against that real path, so the fixture and the catalogue can no
 * longer disagree — and the run-time refusal names a capability nothing else on the path requires.
 */
@DisplayName("Path capability")
class PathCapabilityTest {
    private val service = CustomExportServiceImpl()

    /** The creditor role's group heading — `SupplierTable`'s registry label. */
    private val creditorRole = "Kreditor-Rolle"

    /** A column of `supplier`, reached from the partner root over one hop. */
    private val creditorNoField = "supplier:by:partner_id.creditor_no"

    /** The debtor role's group heading — `DebtorTable`'s registry label. */
    private val debtorRole = "Debitor-Rolle"

    /** A column of `debtor`, reached from the partner root over one hop. Costs two capabilities. */
    private val debtorNoField = "debtor:by:partner_id.debtor_no"

    /** An aggregate over `supplier_bank_account`, reached from the partner root by a to-many hop. */
    private val bankAccountCount = "supplier_bank_account:by:partner_id~count"

    /** An aggregate over `invoice`, two hops out, the second of them the to-many one. */
    private val invoiceSumInPeriod = "supplier:by:partner_id.invoice:by:supplier_id~sum@period"

    /**
     * The real `business_partner → debtor` path, taken from the graph.
     *
     * Offerable at all only because the business-partner migration made `debtor.partner_id` unique — before it the graph
     * derived this relation as to-many and `ExportSubject.paths` filtered it away, so there was no
     * two-capability path to test against.
     */
    private fun debtorPath(): ExportRelationPath =
        assertNotNull(
            SchemaGraph.pathsFrom(BusinessPartnerTable).firstOrNull { it.target == DebtorTable },
            "the debtor role must be reachable from the partner root, or the tests below prove nothing",
        )

    @Test
    fun `a caller without the creditor capability is offered no creditor-role field`() {
        val fields =
            ExportCatalogue
                .visibleTo(setOf(Capability.CUSTOM_EXPORT_READ, Capability.DEBTOR_READ))
                .flatMap { it.fields }

        assertTrue(
            fields.none { it.group == creditorRole },
            "a caller who may not read the creditor role must not be offered a column of it",
        )
    }

    @Test
    fun `a caller holding the creditor capability is offered them`() {
        val fields =
            ExportCatalogue
                .visibleTo(setOf(Capability.CUSTOM_EXPORT_READ, Capability.CREDITOR_READ))
                .flatMap { it.fields }

        assertTrue(fields.any { it.group == creditorRole }, "holding every capability on the path offers its fields")
        assertTrue(fields.any { it.key == creditorNoField })
    }

    /** A subject with nothing left in it is not offered at all — an export of nothing is not a request. */
    @Test
    fun `a caller holding nothing is offered no subject`() {
        assertEquals(emptyList(), ExportCatalogue.visibleTo(emptySet()))
    }

    /**
     * The discriminating case, on a **real** path rather than a hand-set fixture.
     *
     * `business_partner → debtor` crosses two tables with two different capabilities. Its fields
     * carry `DEBTOR_READ` as their own, so the old per-field rule would offer them to a caller
     * holding only that — while the query would touch `business_partner`, which that caller may not
     * read. That is the leak, and it is now expressible without copying a path.
     */
    @Test
    fun `a path is invisible when a table it crosses is not held`() {
        val crossesTwo: ExportRelationPath = debtorPath()

        assertEquals(
            setOf(Capability.CREDITOR_READ, Capability.DEBTOR_READ),
            crossesTwo.requiredCapabilities,
            "the catalogue itself must produce the two-capability path, or this test is a fixture again",
        )
        assertTrue(
            crossesTwo.fields.all { it.capability == Capability.DEBTOR_READ },
            "this only proves something if the field's own capability is the one held below",
        )
        assertFalse(
            crossesTwo.isVisibleTo(setOf(Capability.DEBTOR_READ)),
            "holding the field's own capability is not enough to traverse the path it sits on",
        )
        assertEquals(Capability.CREDITOR_READ, crossesTwo.missingCapabilityFor(setOf(Capability.DEBTOR_READ)))
        assertTrue(crossesTwo.isVisibleTo(setOf(Capability.CREDITOR_READ, Capability.DEBTOR_READ)))
        assertEquals(null, crossesTwo.missingCapabilityFor(setOf(Capability.CREDITOR_READ, Capability.DEBTOR_READ)))
    }

    /** The same rule as the catalogue applies it: a half-held path contributes nothing to the picker. */
    @Test
    fun `a caller holding only the debtor capability is offered no debtor-role field`() {
        val fields =
            ExportCatalogue
                .visibleTo(setOf(Capability.CUSTOM_EXPORT_READ, Capability.DEBTOR_READ))
                .flatMap { it.fields }

        assertTrue(
            fields.none { it.group == debtorRole },
            "the debtor role is reached through business_partner, so its columns cost CREDITOR_READ too",
        )
    }

    @Test
    fun `a caller holding both capabilities is offered the debtor-role fields`() {
        val fields =
            ExportCatalogue
                .visibleTo(setOf(Capability.CUSTOM_EXPORT_READ, Capability.CREDITOR_READ, Capability.DEBTOR_READ))
                .flatMap { it.fields }

        assertTrue(fields.any { it.key == debtorNoField }, "holding every capability on the path offers its fields")
    }

    /**
     * The subject's requirement lookup — the single one both server-side guards read — hands a
     * relation field its **path's** set, not the field's own capability.
     */
    @Test
    fun `a relation field's requirement is its whole path's`() {
        val subject = assertNotNull(ExportCatalogue.subject("partner"))
        val path = assertNotNull(subject.paths.firstOrNull { it.target == SupplierTable })

        assertEquals(path.requiredCapabilities, subject.capabilityRequirements()[creditorNoField])
    }

    /**
     * An aggregate is gated by exactly the same rule, with no exception for being a subquery.
     *
     * Reading a to-many table through a correlated subquery instead of a join changes how the SQL is
     * written and not which tables it touches — `Anzahl Rechnungen` still proves the creditor row
     * exists, and a `0` still says it does not. So an aggregate has to cost every capability on its
     * path, and this pins that the filter is actually applied to the aggregate branch and not only to
     * the column one.
     *
     * The literal keys are deliberate: recomputing them from the path key would agree with any key
     * the catalogue produced.
     */
    @Test
    fun `an aggregate is offered only to a caller holding every capability on its path`() {
        val subject = assertNotNull(ExportCatalogue.subject("partner"))

        val holder = subject.offeringsVisibleTo(setOf(Capability.CREDITOR_READ)).map { it.key }
        assertTrue(bankAccountCount in holder, "holding the whole path offers its aggregates; got $holder")
        assertTrue(invoiceSumInPeriod in holder, "a two-hop aggregate path is gated the same way; got $holder")

        // DEBTOR_READ alone reaches neither table: the partner root itself costs CREDITOR_READ, and
        // every aggregate here hangs off that root.
        assertEquals(
            emptyList(),
            subject.offeringsVisibleTo(setOf(Capability.DEBTOR_READ)),
            "a caller lacking a capability on the path must be offered neither its columns nor its aggregates",
        )
    }

    /**
     * The aggregate's entry in the **same** map a column's requirement comes from.
     *
     * [org.example.project.features.customexport.domain.catalogue.ExportAggregateField.capability] is
     * the owning table's alone and would under-state what running the aggregate costs; what belongs in
     * this map is the path union, exactly as for a column. One map, because the `~` in an aggregate
     * key cannot occur in a column key — and because two maps are two things the save check and the
     * run check could read differently.
     */
    @Test
    fun `an aggregate's requirement is its whole path's, in the same map as a column's`() {
        val subject = assertNotNull(ExportCatalogue.subject("partner"))
        val invoices =
            assertNotNull(
                subject.aggregatePaths.firstOrNull { it.key == "supplier:by:partner_id.invoice:by:supplier_id" },
                "found ${subject.aggregatePaths.map { it.key }}",
            )
        val requirements = subject.capabilityRequirements()

        assertEquals(invoices.requiredCapabilities, requirements[invoiceSumInPeriod])
        assertEquals(setOf(Capability.CREDITOR_READ), requirements[invoiceSumInPeriod])
        assertEquals(setOf(Capability.CREDITOR_READ), requirements[bankAccountCount])
        assertNotNull(
            requirements[creditorNoField],
            "the column keys must still be in the map the aggregates joined — one lookup, not two",
        )
    }

    /**
     * The enforcement half, and the one that matters: a definition outlives whoever built it, so what
     * was checked at save time says nothing about who is running it today.
     */
    @Test
    fun `a stored definition naming a relation field is refused when the runner lacks a capability on its path`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                partner(firstShop(), name = "Alpha GmbH")

                val saved = service.save(relationFieldRequest(), setOf(Capability.CREDITOR_READ), "admin")

                val thrown = failureOf { service.run(saved.id, emptySet()) }
                assertTrue(thrown is PosException, "expected a refusal, but got: $thrown")
                assertContains(
                    thrown.message.orEmpty(),
                    Capability.CREDITOR_READ.name,
                    message = "a refusal that does not name the missing capability sends the operator to the wrong person",
                )
                assertContains(thrown.message.orEmpty(), creditorNoField)
            }
        }
    }

    /**
     * The case that could not be written before a second capability entered the registry: the runner
     * holds the field's **own** capability and is still refused, because the path crosses a table
     * they may not read.
     *
     * The refusal under the old per-field rule would simply not have happened — `debtor_no` carries
     * `DEBTOR_READ`, which this runner holds. What is missing is `CREDITOR_READ` for the
     * `business_partner` root the query starts from, and the message has to name *that*, or the
     * operator asks their admin for the wrong permission and is refused again.
     */
    @Test
    fun `a runner holding the field's own capability is refused for one further along its path, by name`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                partner(firstShop(), name = "Alpha GmbH")

                val bothRoles = setOf(Capability.CREDITOR_READ, Capability.DEBTOR_READ)
                val saved = service.save(debtorFieldRequest(), bothRoles, "admin")

                // Holds DEBTOR_READ — the capability of the table the column lives on — and nothing else.
                val thrown = failureOf { service.run(saved.id, setOf(Capability.DEBTOR_READ)) }
                assertTrue(thrown is PosException, "expected a refusal, but got: $thrown")
                assertContains(
                    thrown.message.orEmpty(),
                    Capability.CREDITOR_READ.name,
                    message = "the refusal must name the capability that is actually missing, not the one held",
                )
                assertContains(thrown.message.orEmpty(), debtorNoField)
            }
        }
    }

    /** The same definition run by someone holding the whole path succeeds. */
    @Test
    fun `holding every capability on the path runs it`() {
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                partner(firstShop(), name = "Alpha GmbH")

                val bothRoles = setOf(Capability.CREDITOR_READ, Capability.DEBTOR_READ)
                val saved = service.save(debtorFieldRequest(), bothRoles, "admin")

                val result = service.run(saved.id, bothRoles)
                assertEquals(1, result.rowCount, "a partner with no debtor role is still a partner: left join, one row")
            }
        }
    }

    /** One column of the debtor role, which costs `DEBTOR_READ` and `CREDITOR_READ` together. */
    private fun debtorFieldRequest() =
        CustomExportDto.SaveDefinitionRequestDto(
            name = "Debitorennummern",
            subjectKey = "partner",
            fields = listOf(CustomExportDto.DefinitionFieldDto(debtorNoField, 0, "Debitorennummer")),
        )

    /**
     * One column, and deliberately a relation one.
     *
     * A root column alongside it would be refused first — it needs the same capability — and the
     * message would then name the root field, proving nothing about the path.
     */
    private fun relationFieldRequest() =
        CustomExportDto.SaveDefinitionRequestDto(
            name = "Kreditorennummern",
            subjectKey = "partner",
            fields = listOf(CustomExportDto.DefinitionFieldDto(creditorNoField, 0, "Kreditorennummer")),
        )

    private fun firstShop(): ShopInfo = transaction { ShopInfo.all().first() }

    private fun partner(
        shop: ShopInfo,
        name: String,
    ) {
        transaction {
            BusinessPartnerTable.insert {
                it[BusinessPartnerTable.shopId] = shop.id
                it[BusinessPartnerTable.name] = name
            }
        }
    }

    /**
     * `assertFailsWith` cannot take a suspending block, and a JUnit 5 test whose last statement is one
     * is silently skipped. Catching the throwable ourselves avoids both.
     */
    private suspend fun failureOf(block: suspend () -> Unit): Throwable? = runCatching { block() }.exceptionOrNull()
}
