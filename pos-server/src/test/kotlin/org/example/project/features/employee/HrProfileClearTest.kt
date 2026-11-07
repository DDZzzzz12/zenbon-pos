package org.example.project.features.employee

import kotlinx.coroutines.runBlocking
import org.example.project.core.domain.dto.employee.EmployeeInfoDto
import org.example.project.features.audit.database.tables.TransactionAuditLogTable
import org.example.project.features.employee.data.repository.EmployeeInfoRepositoryImpl
import org.example.project.features.employee.data.repository.EmployeeRepositoryImpl
import org.example.project.features.employee.data.service.EmployeeInfoServiceImpl
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.TestDatabase
import org.example.project.utils.dbQuery
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FO-77 (GDPR): `""` in an HR profile update clears the stored value, and the clear is audited. */
@DisplayName("HR profile: a blank value clears the field")
class HrProfileClearTest {
    private val service = EmployeeInfoServiceImpl(EmployeeInfoRepositoryImpl(), EmployeeRepositoryImpl())
    private lateinit var actor: UUID
    private lateinit var target: UUID

    @BeforeEach
    fun setUp() {
        TestDatabase.setup()
        actor = TestData.createAdmin().id.value
        target = TestData.createCashier().id.value
        runBlocking {
            service.createEmployeeInfo(
                EmployeeInfoDto.CreateEmployeeInfoRequestDto(
                    employeeId = target.toString(),
                    firstName = "Klara",
                    lastName = "Kern",
                    email = "klara@example.com",
                    phoneNumber = "0151234",
                    street = "Hauptstr. 1",
                    city = "Berlin",
                    state = "BE",
                    zipCode = "10115",
                    country = "DE",
                    dateOfBirth = "1990-01-01",
                    hireDate = "2024-01-01",
                    jobTitle = "Service",
                    hourlyRate = "15.00",
                    contractedHours = "20.00",
                ),
            )
        }
    }

    @AfterEach
    fun tearDown() = TestDatabase.teardown()

    private fun update(dto: EmployeeInfoDto.UpdateEmployeeInfoRequestDto) =
        runBlocking {
            service.updateEmployeeInfoByEmployeeId(target, dto, actor.toString())
        }

    @Test
    fun `an empty string clears every string, date and decimal field`() {
        val result =
            update(
                EmployeeInfoDto.UpdateEmployeeInfoRequestDto(
                    email = "",
                    phoneNumber = "",
                    street = "",
                    city = "",
                    state = "",
                    zipCode = "",
                    country = "",
                    jobTitle = "",
                    dateOfBirth = "",
                    hireDate = "",
                    hourlyRate = "",
                    salary = "",
                    contractedHours = "",
                ),
            )

        assertNull(result.email)
        assertNull(result.phoneNumber)
        assertNull(result.street)
        assertNull(result.city)
        assertNull(result.state)
        assertNull(result.zipCode)
        assertNull(result.country)
        assertNull(result.jobTitle)
        assertNull(result.dateOfBirth)
        assertNull(result.hireDate)
        assertNull(result.hourlyRate)
        assertNull(result.salary)
        assertNull(result.contractedHours)
        assertEquals("Klara", result.firstName, "the names are untouched")
    }

    @Test
    fun `a null field is left unchanged`() {
        val result = update(EmployeeInfoDto.UpdateEmployeeInfoRequestDto(city = "Hamburg"))

        assertEquals("Hamburg", result.city)
        assertEquals("0151234", result.phoneNumber)
        assertEquals("klara@example.com", result.email)
        assertEquals("15.00", result.hourlyRate)
    }

    @Test
    fun `two profiles can both clear their email - blank is stored as null, not as a duplicate empty string`() {
        val second = TestData.createCashier(employeeNumber = "CSH002", username = "cashier2").id.value
        runBlocking {
            service.createEmployeeInfo(
                EmployeeInfoDto.CreateEmployeeInfoRequestDto(second.toString(), "Max", "Mann", email = "max@example.com"),
            )
            update(EmployeeInfoDto.UpdateEmployeeInfoRequestDto(email = ""))
            val cleared =
                service.updateEmployeeInfoByEmployeeId(
                    second,
                    EmployeeInfoDto.UpdateEmployeeInfoRequestDto(email = ""),
                    actor.toString(),
                )
            assertNull(cleared.email)
        }
    }

    @Test
    fun `a clearing update writes an UPDATE_EMPLOYEE audit row naming the cleared fields but not their values`() {
        update(EmployeeInfoDto.UpdateEmployeeInfoRequestDto(phoneNumber = "", city = "Hamburg"))

        val row =
            runBlocking {
                dbQuery {
                    TransactionAuditLogTable
                        .selectAll()
                        .where { TransactionAuditLogTable.operation eq "UPDATE_EMPLOYEE" }
                        .single()
                }
            }
        val state = row[TransactionAuditLogTable.afterState].orEmpty()
        assertTrue("\"clearedFields\":[\"phoneNumber\"]" in state, state)
        assertTrue("\"updatedFields\":[\"city\"]" in state, state)
        assertTrue("0151234" !in state && "Hamburg" !in state, "personnel values must stay out of the audit row: $state")
    }

    @Test
    fun `a malformed email is rejected and a blank one is accepted`() {
        val bad = assertFailsWith<PosException> { update(EmployeeInfoDto.UpdateEmployeeInfoRequestDto(email = "nope")) }
        assertEquals(400, bad.httpStatus.value)
        update(EmployeeInfoDto.UpdateEmployeeInfoRequestDto(email = ""))
    }

    @Test
    fun `the names cannot be cleared`() {
        val ex = assertFailsWith<PosException> { update(EmployeeInfoDto.UpdateEmployeeInfoRequestDto(firstName = " ")) }
        assertEquals(400, ex.httpStatus.value)
    }
}
