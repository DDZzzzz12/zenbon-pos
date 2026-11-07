package org.example.project.features.setup

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.employee.EmployeeDto
import org.example.project.core.domain.dto.setup.InitialSetupDto
import org.example.project.core.domain.enums.employee.EmployeeRole
import org.example.project.core.domain.enums.setup.SetupSteps
import org.example.project.features.auth.data.repository.RefreshTokenRepositoryImpl
import org.example.project.features.auth.data.service.RefreshTokenServiceImpl
import org.example.project.features.employee.data.repository.EmployeeInfoRepositoryImpl
import org.example.project.features.employee.data.repository.EmployeeRepositoryImpl
import org.example.project.features.employee.data.repository.PermissionGroupRepositoryImpl
import org.example.project.features.employee.data.service.EmployeeServiceImpl
import org.example.project.features.employee.data.service.GroupCapabilityResolver
import org.example.project.features.order.data.service.RotationStaffRosterListener
import org.example.project.features.payment.data.repository.PaymentTypeRepositoryImpl
import org.example.project.features.setup.data.repository.ConfigurationRepository
import org.example.project.features.setup.data.repository.ShopInfoRepositoryImpl
import org.example.project.features.setup.data.repository.VatRepositoryImpl
import org.example.project.features.setup.data.service.InitialSetupServiceImpl
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestDatabase
import org.example.project.utils.dbQuery
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The setup wizard's whole purpose is to produce an account the operator can then log in with.
 * A live check on 2026-09-23 ran `POST /api/setup/initialize` with `admin` / `Admin!2345`, got
 * `{"success":true}`, and then got 401 from `POST /api/auth/login` for exactly those credentials —
 * an install nobody can get into.
 */
@DisplayName("InitialSetup — the owner the wizard creates can actually log in")
class OwnerSetupLoginTest {
    private val setup =
        InitialSetupServiceImpl(
            EmployeeRepositoryImpl(),
            EmployeeInfoRepositoryImpl(),
            ShopInfoRepositoryImpl(),
            ConfigurationRepository(),
            PaymentTypeRepositoryImpl(),
            VatRepositoryImpl(),
            ShopConfigurationManager,
        )

    private val employees =
        EmployeeServiceImpl(
            EmployeeRepositoryImpl(),
            RefreshTokenServiceImpl(RefreshTokenRepositoryImpl()) { 0L },
            GroupCapabilityResolver(),
            PermissionGroupRepositoryImpl(),
            listOf(RotationStaffRosterListener()),
        )

    @BeforeEach
    fun setUp() = TestDatabase.setup()

    @AfterEach
    fun tearDown() = TestDatabase.teardown()

    private fun ownerStep() =
        InitialSetupDto.InitialSetupRequestDto(
            setupStep = SetupSteps.OWNER_SETUP,
            data =
                InitialSetupDto.OwnerSetupRequestDto(
                    adminUsername = "admin",
                    adminPassword = "Admin!2345",
                    adminEmail = "admin@example.com",
                    adminFirstName = "Live",
                    adminLastName = "Check",
                    adminPin = "1234",
                ),
        )

    @Test
    fun `the chosen password authenticates`() =
        runBlocking {
            setup.executeSetupStep(ownerStep())

            val employee = employees.authenticateEmployee("admin", "Admin!2345")

            assertNotNull(employee, "the password the operator chose in the wizard was rejected")
            assertEquals("admin", employee.username)
        }

    /**
     * The live reproduction: a `JWT_DEVELOPMENT_MODE` boot seeds `dev-user` with role ADMIN, the
     * old "an admin exists" guard then skipped the whole step, and setup still reported success.
     */
    @Test
    fun `an unrelated admin account does not silence the step`(): Unit =
        runBlocking {
            EmployeeRepositoryImpl().let { repo ->
                dbQuery {
                    repo.createEmployee(
                        EmployeeDto.CreateEmployeeRequestDto(
                            username = "dev-user",
                            password = "",
                            role = EmployeeRole.ADMIN.toString(),
                            pin = "0000",
                        ),
                        "DEV-001",
                    )
                }
            }

            setup.executeSetupStep(ownerStep())

            assertNotNull(
                employees.authenticateEmployee("admin", "Admin!2345"),
                "setup reported success but created no owner account",
            )
        }

    @Test
    fun `the chosen PIN authenticates`(): Unit =
        runBlocking {
            setup.executeSetupStep(ownerStep())

            assertNotNull(
                employees.authenticateEmployee("admin", "1234"),
                "the PIN the operator chose in the wizard was rejected",
            )
        }

    /**
     * `employee_info.first_name`/`last_name` are where DSFinV-K's `BEDIENER_NAME` comes from, so
     * an owner without that record signs every receipt as nobody. The wizard collected both names
     * and dropped them.
     */
    @Test
    fun `the owner's name is written to employee_info`(): Unit =
        runBlocking {
            setup.executeSetupStep(ownerStep())

            val info =
                dbQuery {
                    val owner = EmployeeRepositoryImpl().getEmployeeByUsername("admin")
                    assertNotNull(owner, "no owner account")
                    EmployeeInfoRepositoryImpl().getEmployeeInfoByEmployeeId(owner.id.value)
                }

            assertNotNull(info, "the names the operator entered were dropped")
            assertEquals("Live", info.firstName)
            assertEquals("Check", info.lastName)
        }

    /**
     * The record existing is not enough: DSFinV-K's `employeeDisplayName` — and every other reader
     * of `Employee.employeeInfo` — resolves it through `employees.info_id`. E2E round 4 (R4-0) found
     * the record written and `info_id` NULL, so the owner's receipts still carried the username.
     * This reads the name the way the export does.
     */
    @Test
    fun `the owner's name is reachable the way the DSFinV-K export reads it`(): Unit =
        runBlocking {
            setup.executeSetupStep(ownerStep())

            val names =
                dbQuery {
                    val owner = EmployeeRepositoryImpl().getEmployeeByUsername("admin")
                    assertNotNull(owner, "no owner account")
                    owner.employeeInfo?.let { it.firstName to it.lastName }
                }

            assertEquals("Live" to "Check", names, "employees.info_id is not linked to the owner's record")
        }

    @Test
    fun `a blank name is refused before anything is written`(): Unit =
        runBlocking {
            val blank =
                InitialSetupDto.InitialSetupRequestDto(
                    setupStep = SetupSteps.OWNER_SETUP,
                    data =
                        InitialSetupDto.OwnerSetupRequestDto(
                            adminUsername = "admin",
                            adminPassword = "Admin!2345",
                            adminEmail = "admin@example.com",
                            adminFirstName = "  ",
                            adminLastName = "Check",
                            adminPin = "1234",
                        ),
                )

            val refusal = assertFailsWith<PosException> { setup.executeSetupStep(blank) }

            assertEquals(HttpStatusCode.BadRequest, refusal.httpStatus)
            assertNull(
                dbQuery { EmployeeRepositoryImpl().getEmployeeByUsername("admin") },
                "the refusal must not leave a half-created owner behind",
            )
        }
}
