package org.example.project.features.customexport

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.customexport.CustomExportDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.features.customexport.data.service.CustomExportServiceImpl
import org.example.project.features.customexport.domain.catalogue.ExportAggregateField
import org.example.project.features.customexport.domain.catalogue.ExportCatalogue
import org.example.project.features.customexport.domain.catalogue.ExportTableRegistry
import org.example.project.features.customexport.domain.models.ExportDefinition
import org.example.project.features.customexport.domain.models.ExportDefinitionField
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **No export summarises figures per person, on any Datenbereich, by any route.**
 *
 * `ExportSubject.offersAggregates` closed the Personal subject's front door. It closed nothing else:
 * `bill.employee_id` and `kassenbuch_header.employee_id` are ordinary foreign keys, so the graph put
 * `employees` mid-path and carried on to `sales_invoice`, which declares aggregates. An operator on
 * Kassenbelege or Kassenbuch could therefore tick Personalnummer, Vorname, Nachname **and** Umsatz
 * (Zeitraum) and receive exactly the per-employee league table the staff-list suppression removed —
 * a different root, the same file, and the same **Leistungs- und Verhaltenskontrolle** under § 87
 * Abs. 1 Nr. 6 BetrVG.
 *
 * The rule that closes it is per-path: `ExportTable.aggregatesAreMonitoring`, set on the `employees`
 * entry, suppresses any aggregate whose path crosses that table. This class asserts it over the
 * **whole catalogue** rather than per subject, because the defect it fixes was precisely a rule
 * stated per subject.
 *
 * **Ausgangsrechnungen turned out not to be a third route.** From `sales_invoice` the graph reaches
 * `employees` at hop one (`issued_by`, `cancelled_by`), but the only aggregate-declaring tables
 * beyond it are `sales_invoice` itself — which the walk may not re-enter, it being the root — so that
 * subject offered no employee-crossing aggregate to lose. Its six aggregates are its own line and
 * VAT-breakdown rollups. [`no subject offers an aggregate reached through the staff table`] therefore
 * also holds it to zero going forward: the day `bill` or `kassenbuch_header` declares an aggregate,
 * Ausgangsrechnungen becomes the third route, and this test is what notices.
 */
@DisplayName("No export summarises figures per person")
class MonitoringAggregateSuppressionTest {
    private val service = CustomExportServiceImpl()

    /** Whether an offered aggregate is reached by travelling through `employees`. */
    private fun ExportAggregateField.crossesStaff(): Boolean = path.hops.any { it.target == EmployeeTable }

    /**
     * The catalogue-wide rule, asserted as an emptiness over every subject at once.
     *
     * Named literals sit alongside it because emptiness alone is a weak assertion: it would keep
     * passing if the whole graph stopped producing aggregates for an unrelated reason, and just as
     * quietly if these cells came back under new keys. The five below were all **offered** before this
     * change — three on Kassenbelege, two on Kassenbuch — so the test fails if any returns.
     */
    @Test
    fun `no subject offers an aggregate reached through the staff table`() {
        val offending =
            ExportCatalogue.subjects.flatMap { subject ->
                subject.allAggregates.filter { it.crossesStaff() }.map { "${subject.key}: ${it.key}" }
            }

        assertEquals(emptyList(), offending, "a per-head figure is offered somewhere in the catalogue")

        val gone =
            mapOf(
                "bill" to
                    listOf(
                        "employees:via:employee_id.sales_invoice:by:issued_by~sum@period",
                        "employees:via:employee_id.sales_invoice:by:issued_by~count",
                        "employees:via:employee_id.sales_invoice:by:cancelled_by~last@period",
                    ),
                "kassenbuch" to
                    listOf(
                        "employees:via:employee_id.sales_invoice:by:issued_by~sum",
                        "employees:via:employee_id.sales_invoice:by:cancelled_by~first@period",
                    ),
                // Never offered — see the class KDoc. Pinned as a route that must stay shut.
                "sales_invoice" to emptyList(),
            )
        gone.forEach { (subjectKey, keys) ->
            val subject = assertNotNull(ExportCatalogue.subject(subjectKey))
            val offered = subject.allOfferings.map { it.key }.toSet()
            assertEquals(emptyList(), keys.filter { it in offered }, "$subjectKey must not offer these again")
            assertTrue(
                subject.capabilityRequirements().keys.none { it in keys },
                "a suppressed key left in the capability map is a key the save check still accepts",
            )
            assertEquals(
                emptyList(),
                subject
                    .offeringsVisibleTo(Capability.entries.toSet())
                    .filterIsInstance<ExportAggregateField>()
                    .filter { it.crossesStaff() },
                "not even a caller holding every capability is offered one on $subjectKey",
            )
            // Withheld, not unreachable: the runner needs them by name to refuse with a reason.
            assertTrue(
                subject.suppressedAggregates.map { it.key }.containsAll(keys),
                "the cells must stay enumerable: ${subject.suppressedAggregates.map { it.key }}",
            )
        }
    }

    /**
     * The size of what was closed, stated rather than implied.
     *
     * Sixteen cells each on Kassenbelege and Kassenbuch — four functions (`count`, `sum`, `first`,
     * `last`), two foreign keys into `sales_invoice` (`issued_by`, `cancelled_by`), each twinned into
     * `(Zeitraum)` and `(gesamt)`. Written out as the full product rather than as a count, so a
     * schema change that silently drops half of them fails here with the list in the message.
     */
    @Test
    fun `Kassenbelege and Kassenbuch each withhold sixteen per-head cells`() {
        val expected =
            listOf("count", "sum", "first", "last").flatMap { function ->
                listOf("issued_by", "cancelled_by").flatMap { fk ->
                    listOf(
                        "employees:via:employee_id.sales_invoice:by:$fk~$function@period",
                        "employees:via:employee_id.sales_invoice:by:$fk~$function",
                    )
                }
            }

        listOf("bill", "kassenbuch").forEach { subjectKey ->
            val subject = assertNotNull(ExportCatalogue.subject(subjectKey))
            assertEquals(
                expected.sorted(),
                subject.suppressedAggregates.map { it.key }.sorted(),
                "$subjectKey withholds the invoice figures of the person who booked the row, and only those",
            )
        }
    }

    /**
     * The regression that would make the fix overshoot, and the one that matters most.
     *
     * The decision is about **summaries**, not about staff. Which member of staff booked a receipt is
     * operational fact, on the screen the export mirrors, and stays exportable — asserted as concrete
     * ticked keys on both subjects, not as "some staff column exists".
     */
    @Test
    fun `staff name columns are still offered on Kassenbelege and Kassenbuch`() {
        listOf("bill", "kassenbuch").forEach { subjectKey ->
            val subject = assertNotNull(ExportCatalogue.subject(subjectKey))
            val offered = subject.allFields.map { it.key }.toSet()

            listOf(
                "employees:via:employee_id.employee_number",
                "employees:via:employee_id.employee_info:by:employee_id.first_name",
                "employees:via:employee_id.employee_info:by:employee_id.last_name",
            ).forEach { key ->
                assertTrue(key in offered, "$subjectKey lost the staff column '$key'; offered: $offered")
            }
            val staffColumnCost = subject.capabilityRequirements()["employees:via:employee_id.employee_number"]
            assertTrue(
                staffColumnCost != null && Capability.EMPLOYEE_READ in staffColumnCost,
                "and it still costs EMPLOYEE_READ on top of the subject's own capability",
            )
        }
    }

    /**
     * Everything that is **not** a per-head figure is untouched — the assertion that would have caught
     * a fix reaching too far, e.g. one keyed on "any aggregate over `sales_invoice`".
     */
    @Test
    fun `other subjects keep their aggregates`() {
        val partner = assertNotNull(ExportCatalogue.subject("partner"))
        assertTrue(
            partner.allAggregates.any { it.key.startsWith("debtor:by:partner_id.sales_invoice:by:debtor_id~") },
            "the partner subject's outgoing-invoice summaries are not about a person: ${partner.allAggregates.map { it.key }}",
        )
        assertEquals(emptyList(), partner.suppressedAggregates, "and nothing on it is withheld")

        val voucher = assertNotNull(ExportCatalogue.subject("gutschein"))
        val customer = assertNotNull(ExportCatalogue.subject("kunde"))
        assertTrue(
            voucher.suppressedAggregates.isEmpty() && customer.allAggregates.isNotEmpty(),
            "the voucher and customer subjects are unaffected by a decision about staff",
        )

        val bill = assertNotNull(ExportCatalogue.subject("bill"))
        assertTrue(
            "bill_vat_detail:by:bill_id~count" in bill.allAggregates.map { it.key },
            "Kassenbelege keeps its VAT-detail counts: they summarise a receipt, not a person",
        )
        val kassenbuch = assertNotNull(ExportCatalogue.subject("kassenbuch"))
        assertTrue("kassenbuch_line_item:by:header_id~sum" in kassenbuch.allAggregates.map { it.key })
    }

    /** The rule is declared on the table, not hardcoded in the walker — and on exactly one table. */
    @Test
    fun `the staff table is the only one marked as monitoring`() {
        val marked = ExportTableRegistry.entries.filter { it.aggregatesAreMonitoring }

        assertEquals(listOf(EmployeeTable), marked.map { it.table })
        assertTrue(marked.single().columns.isNotEmpty(), "the flag withholds summaries, never the columns")
    }

    /**
     * Refused at **save** on a subject whose own `offersAggregates` is true — the leg the old
     * per-subject rule had no way to reach.
     *
     * The refusal must name the reason: told "unknown field", an operator goes looking for a bug that
     * is not there. The column is derivable, cheap, and withheld on purpose.
     */
    @Test
    fun `a per-head figure on a cash-book export is refused at save`() {
        val held = setOf(Capability.CASHBOOK_READ, Capability.EMPLOYEE_READ, Capability.INVOICE_READ)
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                listOf(
                    "employees:via:employee_id.sales_invoice:by:issued_by~sum@period",
                    "employees:via:employee_id.sales_invoice:by:cancelled_by~count",
                ).forEach { key ->
                    val thrown =
                        runCatching {
                            service.save(
                                CustomExportDto.SaveDefinitionRequestDto(
                                    name = "Umsatz je Buchender",
                                    subjectKey = "kassenbuch",
                                    fields =
                                        listOf(
                                            CustomExportDto.DefinitionFieldDto("kassenbuch.beleg_nr", 0, "Beleg"),
                                            CustomExportDto.DefinitionFieldDto(key, 1, "Umsatz"),
                                        ),
                                ),
                                held,
                                "admin",
                            )
                        }.exceptionOrNull()

                    assertTrue(thrown is PosException, "'$key' was accepted onto a cash-book export: $thrown")
                    assertTrue(
                        thrown.message.orEmpty().contains("Leistungsdaten"),
                        "the refusal must say why rather than read as a missing column: ${thrown.message}",
                    )
                }
            }
        }
    }

    /**
     * Refused at **run**, for the definition that never went through `save`.
     *
     * A cash-book export with a per-head Umsatz column could be built and stored on the day before
     * this change; it is in the database now. So the row is written straight through the DAO, the way
     * such a definition exists, and the runner must refuse it — with the reason, not as a broken
     * definition, which is what the operator's list would otherwise show.
     */
    @Test
    fun `a cash-book definition stored before the rule is refused at run`() {
        val held = setOf(Capability.CASHBOOK_READ, Capability.EMPLOYEE_READ, Capability.INVOICE_READ)
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                val definitionId =
                    transaction {
                        val definition =
                            ExportDefinition.new {
                                shop = ShopInfo.all().first()
                                name = "Umsatz je Buchender"
                                subjectKey = "kassenbuch"
                                createdByEmployeeId = "admin"
                            }
                        ExportDefinitionField.new {
                            this.definition = definition
                            fieldKey = "kassenbuch.beleg_nr"
                            position = 0
                            heading = "Beleg"
                        }
                        ExportDefinitionField.new {
                            this.definition = definition
                            fieldKey = "employees:via:employee_id.sales_invoice:by:issued_by~sum"
                            position = 1
                            heading = "Umsatz"
                        }
                        definition.id.value
                    }

                val thrown = runCatching { service.run(definitionId, held) }.exceptionOrNull()

                assertTrue(thrown is PosException, "the stored per-head figure ran: $thrown")
                assertTrue(
                    thrown.message.orEmpty().contains("Leistungsdaten"),
                    "the refusal must name the reason, not report a broken definition: ${thrown.message}",
                )
            }
        }
    }
}
