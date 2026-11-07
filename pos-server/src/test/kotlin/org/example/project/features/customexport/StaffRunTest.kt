package org.example.project.features.customexport

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import org.example.project.core.domain.dto.customexport.CustomExportDto
import org.example.project.core.domain.enums.auth.Capability
import org.example.project.features.customexport.data.service.CustomExportServiceImpl
import org.example.project.features.customexport.domain.models.ExportDefinition
import org.example.project.features.customexport.domain.models.ExportDefinitionField
import org.example.project.features.employee.database.tables.EmployeeInfoTable
import org.example.project.features.employee.database.tables.EmployeeTable
import org.example.project.features.employee.domain.models.Employee
import org.example.project.features.setup.domain.models.ShopInfo
import org.example.project.features.terminal.domain.TerminalEmployee
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The staff subject, run end to end through the CSV writer.
 *
 * The catalogue tests say what is offered; only a produced file says what a recipient actually reads.
 * Two things are worth proving here and nowhere else:
 *
 *  - a member of staff with **no personnel record** still appears, with blank name cells. The name
 *    lives one hop away on an optional table, so the alternative failure mode is a staff list that
 *    silently omits people — and nothing in the file would say it had.
 *  - the file carries no pay, no date of birth, no address and no login name, which is asserted as
 *    the **whole** heading row rather than as a set of absences.
 */
@DisplayName("Personal, end to end")
class StaffRunTest {
    private val service = CustomExportServiceImpl()

    @Test
    fun `a Personal export writes the staff list and leaves a missing personnel record blank`() {
        val held = setOf(Capability.EMPLOYEE_READ)
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                val manager = TestData.createManager(employeeNumber = "MGR-1", username = "berger").id.value
                TestData.createCashier(employeeNumber = "CSH-1", username = "claus")
                personnelRecord(
                    manager,
                    first = "Anna",
                    last = "Berger",
                    jobTitle = "Filialleitung",
                    hireDate = LocalDate.parse("2024-04-01"),
                )

                val saved = service.save(staffRequest(), held, "admin")
                val result = service.run(saved.id, held)
                val lines = result.bytes.asCsvLines()

                assertEquals(2, result.rowCount, "one row per member of staff, personnel record or not")
                assertEquals(3, lines.size, "heading plus two data rows, counted in the produced file")
                assertEquals("Personalnummer;Rolle;Status;Vorname;Nachname;Position;Eintritt", lines.first())
                assertEquals(
                    setOf(
                        "MGR-1;MANAGER;ACTIVE;Anna;Berger;Filialleitung;2024-04-01",
                        "CSH-1;CASHIER;ACTIVE;;;;",
                    ),
                    lines.drop(1).toSet(),
                    "the colleague with no personnel record is still on the list, with the four cells " +
                        "that come from it left empty rather than the row dropped",
                )
            }
        }
    }

    /**
     * The negative half, in the produced file rather than in the catalogue.
     *
     * A definition naming `employees.username` is refused at save: the key resolves to nothing,
     * because an unregistered column is not a field. Same for the salary. The refusal is what an
     * operator would hit if a client offered the column anyway — and it is the reason the run above
     * can only ever write what the registry allows.
     */
    @Test
    fun `a definition naming a withheld staff column cannot be saved`() {
        val held = setOf(Capability.EMPLOYEE_READ)
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                listOf("personal.username", "employee_info:by:employee_id.salary", "personal.password_hash").forEach { key ->
                    val thrown =
                        runCatching {
                            service.save(
                                CustomExportDto.SaveDefinitionRequestDto(
                                    name = "Verboten",
                                    subjectKey = "personal",
                                    fields = listOf(CustomExportDto.DefinitionFieldDto(key, 0, "Spalte")),
                                ),
                                held,
                                "admin",
                            )
                        }.exceptionOrNull()

                    assertTrue(thrown != null, "'$key' was accepted as an export column")
                }
            }
        }
    }

    /**
     * The save-time half of the aggregate suppression.
     *
     * The keys below were all offered by this subject until `ExportSubject.offersAggregates` was set
     * false on it — `sales_invoice` holds two foreign keys into `employees`, so the graph derived
     * per-head revenue without anybody choosing it. They are named as literals because that is what
     * makes this test notice their return.
     *
     * The refusal is expected to **say why**: an operator told "unknown field" goes looking for a bug,
     * and there is none — the figure is derivable, cheap, and withheld on purpose.
     *
     * The same refusal on a subject the flag does *not* cover — a cash-book export reaching the same
     * figures through `kassenbuch_header.employee_id` — is in `MonitoringAggregateSuppressionTest`.
     * Both go through one `suppressedAggregateAmong`, so neither leg can drift from the other.
     */
    @Test
    fun `a definition naming a per-head figure is refused at save`() {
        val held = setOf(Capability.EMPLOYEE_READ, Capability.INVOICE_READ, Capability.CASHBOOK_READ, Capability.BILL_READ)
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                listOf(
                    "sales_invoice:by:issued_by~sum@period",
                    "sales_invoice:by:issued_by~count",
                    "sales_invoice:by:cancelled_by~last",
                    "kassenbuch_header:by:employee_id.kassenbuch_line_item:by:header_id~sum",
                ).forEach { key ->
                    val thrown =
                        runCatching {
                            service.save(
                                CustomExportDto.SaveDefinitionRequestDto(
                                    name = "Leistung",
                                    subjectKey = "personal",
                                    fields =
                                        listOf(
                                            CustomExportDto.DefinitionFieldDto("personal.employee_number", 0, "Nr"),
                                            CustomExportDto.DefinitionFieldDto(key, 1, "Umsatz"),
                                        ),
                                ),
                                held,
                                "admin",
                            )
                        }.exceptionOrNull()

                    assertTrue(thrown is PosException, "'$key' was accepted onto a staff export: $thrown")
                    assertTrue(
                        thrown.message.orEmpty().contains("Leistungsdaten"),
                        "the refusal must say why rather than read as a missing column: ${thrown.message}",
                    )
                }
            }
        }
    }

    /**
     * The run-time half, and the reason the guard cannot live in [CustomExportServiceImpl.save] alone.
     *
     * A definition saved **before** the suppression is already in the database, and a hand-crafted one
     * never went through `save` at all. So this stores the row the way such a definition exists —
     * straight through the DAO — and proves the runner refuses it rather than producing the file.
     */
    @Test
    fun `a definition stored before the suppression is refused at run`() {
        val held = setOf(Capability.EMPLOYEE_READ, Capability.INVOICE_READ)
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                TestData.createManager(employeeNumber = "MGR-9", username = "vogel")
                val definitionId =
                    transaction {
                        val definition =
                            ExportDefinition.new {
                                shop = ShopInfo.all().first()
                                name = "Umsatz je Mitarbeiter"
                                subjectKey = "personal"
                                createdByEmployeeId = "admin"
                            }
                        ExportDefinitionField.new {
                            this.definition = definition
                            fieldKey = "personal.employee_number"
                            position = 0
                            heading = "Personalnummer"
                        }
                        ExportDefinitionField.new {
                            this.definition = definition
                            fieldKey = "sales_invoice:by:issued_by~sum"
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

    /**
     * The device account is not a member of staff, asserted against the produced file.
     *
     * The install seeds one login-less `terminal-device` employee — a device principal with an empty
     * password hash, one row however many order stations a shop runs — so an unfiltered export of
     * `employees` writes it as a person. A recipient cannot tell that row from a colleague, and it
     * raises a headcount taken from the file by one.
     *
     * The row's presence is asserted **before** the run, so this cannot pass because bootstrap
     * stopped seeding it. And it is asserted as an absence from the file rather than only through the
     * row count, so the exclusion cannot be satisfied by dropping some other row.
     */
    @Test
    fun `the shared terminal-device account is not exported as staff`() {
        val held = setOf(Capability.EMPLOYEE_READ)
        testApp(setupData = { TestData.createShopInfo() }) {
            runBlocking {
                val seeded =
                    transaction {
                        Employee.find { EmployeeTable.username eq TerminalEmployee.USERNAME }.count()
                    }
                assertEquals(1, seeded, "the device account must exist for this test to mean anything")

                TestData.createCashier(employeeNumber = "CSH-2", username = "moor")

                val saved = service.save(staffRequest(), held, "admin")
                val result = service.run(saved.id, held)
                val lines = result.bytes.asCsvLines()

                assertEquals(1, result.rowCount, "the cashier, and nothing standing in for a device")
                assertEquals(listOf("CSH-2;CASHIER;ACTIVE;;;;"), lines.drop(1))
                assertTrue(
                    lines.none { it.contains(TerminalEmployee.EMPLOYEE_NUMBER) || it.contains("TERMINAL_DEVICE") },
                    "the device account was written into the staff export: $lines",
                )
            }
        }
    }

    private fun staffRequest() =
        CustomExportDto.SaveDefinitionRequestDto(
            name = "Personalliste",
            subjectKey = "personal",
            fields =
                listOf(
                    CustomExportDto.DefinitionFieldDto("personal.employee_number", 0, "Personalnummer"),
                    CustomExportDto.DefinitionFieldDto("personal.role", 1, "Rolle"),
                    CustomExportDto.DefinitionFieldDto("personal.employment_status", 2, "Status"),
                    // The authoritative link, not the `via:info_id` back-reference: `employee_info.employee_id`
                    // is what every lookup uses, and the back-reference is nullable.
                    CustomExportDto.DefinitionFieldDto("employee_info:by:employee_id.first_name", 3, "Vorname"),
                    CustomExportDto.DefinitionFieldDto("employee_info:by:employee_id.last_name", 4, "Nachname"),
                    CustomExportDto.DefinitionFieldDto("employee_info:by:employee_id.job_title", 5, "Position"),
                    CustomExportDto.DefinitionFieldDto("employee_info:by:employee_id.hire_date", 6, "Eintritt"),
                ),
        )

    /** The personnel record, written on both sides exactly as `EmployeeInfoRepositoryImpl` does. */
    private fun personnelRecord(
        employee: UUID,
        first: String,
        last: String,
        jobTitle: String,
        hireDate: LocalDate,
    ) {
        transaction {
            val infoId: EntityID<Int> =
                EmployeeInfoTable.insert {
                    it[EmployeeInfoTable.employeeId] = employee
                    it[EmployeeInfoTable.firstName] = first
                    it[EmployeeInfoTable.lastName] = last
                    it[EmployeeInfoTable.jobTitle] = jobTitle
                    it[EmployeeInfoTable.hireDate] = hireDate
                    it[EmployeeInfoTable.createdAt] = STAMP
                    it[EmployeeInfoTable.updatedAt] = STAMP
                }[EmployeeInfoTable.id]

            EmployeeTable.update({ EmployeeTable.id eq employee }) { it[EmployeeTable.infoId] = infoId }
        }
    }

    private companion object {
        val STAMP: LocalDateTime = LocalDateTime.parse("2026-03-01T10:00")
    }
}
