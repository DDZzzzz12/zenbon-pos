package org.example.project.features.contract

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.example.project.core.config.ShopConfigurationManager
import org.example.project.core.domain.dto.contract.ContractDto
import org.example.project.core.error.PosErrorCode
import org.example.project.features.allocation.data.repository.AllocationRepositoryImpl
import org.example.project.features.contract.data.repository.ContractRepositoryImpl
import org.example.project.features.contract.data.service.ContractServiceImpl
import org.example.project.features.contract.domain.models.Contract
import org.example.project.features.contract.domain.models.ContractInstalment
import org.example.project.features.contract.domain.models.ContractInstalmentStatus
import org.example.project.features.contract.domain.models.ContractInterval
import org.example.project.infrastructure.errors.PosException
import org.example.project.testutils.TestData
import org.example.project.testutils.testApp
import org.example.project.utils.formatter.currentTimeStamp
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The guards and the mapping. Money leaves as a decimal string with its scale intact, a contract
 * deactivates rather than being deleted, and an instalment's status is **derived** — the column
 * itself never holds `OVERDUE`.
 *
 * Every assertion reads in its own transaction: within one, Exposed's identity map can serve back
 * the instance just written rather than what the column actually stored.
 */
@DisplayName("Contract service")
class ContractServiceTest {
    // The real manager: with no DEFAULT_CURRENCY configured it falls back to "EUR", which is what a
    // freshly set-up shop reports anyway — so the currency on the wire is the shop's, not a constant.
    private val service = ContractServiceImpl(ContractRepositoryImpl(), AllocationRepositoryImpl(), ShopConfigurationManager)

    private fun setup() {
        TestData.createShopInfo()
    }

    private fun request(
        name: String = "Miete Ladenlokal",
        interval: String = ContractInterval.MONTHLY.name,
        dueDay: Int = 1,
        startDate: String = "2026-01-01",
        expectedAmount: String? = "1250.0000",
        tolerancePct: String? = null,
    ) = ContractDto.CreateRequestDto(
        name = name,
        interval = interval,
        dueDay = dueDay,
        startDate = startDate,
        expectedAmount = expectedAmount,
        tolerancePct = tolerancePct,
    )

    @Test
    fun `creating a contract returns its money fields as decimal strings`() {
        testApp(setupData = { setup() }) { _ ->
            val created =
                runBlocking { service.create(request(expectedAmount = "1250.0000", tolerancePct = "5.0000")) }

            assertEquals("1250.0000", created.expectedAmount, "scale 4 survives — toDecimalString would round it")
            assertEquals("5.0000", created.tolerancePct)
            assertNull(created.toleranceAbs, "an absent tolerance is not a zero")
            assertEquals("2026-01-01", created.startDate)
            assertEquals(ContractInterval.MONTHLY.name, created.interval)
            assertTrue(created.isActive)
        }
    }

    @Test
    fun `listing returns active contracts, and inactive ones only when asked`() {
        testApp(setupData = { setup() }) { _ ->
            val active = runBlocking { service.create(request(name = "Miete")) }
            val dormant = runBlocking { service.create(request(name = "Strom")) }
            runBlocking { service.update(dormant.id, updateOf(dormant, isActive = false)) }

            val listed = runBlocking { service.list(includeInactive = false, search = null) }
            assertEquals(listOf(active.id), listed.items.map { it.id })
            assertEquals(1, listed.total)

            val all = runBlocking { service.list(includeInactive = true, search = null) }
            assertEquals(setOf(active.id, dormant.id), all.items.map { it.id }.toSet())
            assertEquals(2, all.total)
        }
    }

    @Test
    fun `deactivating keeps the row — an allocated instalment is history`() {
        testApp(setupData = { setup() }) { _ ->
            val created = runBlocking { service.create(request()) }

            runBlocking { service.update(created.id, updateOf(created, isActive = false)) }

            transaction {
                val reloaded = assertNotNull(Contract.findById(created.id), "deactivation must not delete")
                assertEquals(false, reloaded.isActive)
            }
        }
    }

    @Test
    fun `an update replaces the editable fields and leaves createdAt alone`() {
        testApp(setupData = { setup() }) { _ ->
            val created = runBlocking { service.create(request()) }
            val createdAt = transaction { Contract.findById(created.id)!!.createdAt }

            val updated =
                runBlocking {
                    service.update(
                        created.id,
                        updateOf(created).copy(
                            name = "Miete Lager",
                            dueDay = 15,
                            expectedAmount = "1400.0000",
                            description = "nach Indexanpassung",
                        ),
                    )
                }

            assertEquals("Miete Lager", updated.name)
            assertEquals(15, updated.dueDay)
            assertEquals("1400.0000", updated.expectedAmount)
            assertEquals("nach Indexanpassung", updated.description)
            transaction {
                val reloaded = Contract.findById(created.id)!!
                assertEquals(createdAt, reloaded.createdAt, "an edit must not restamp the creation time")
                assertNotNull(reloaded.updatedAt)
            }
        }
    }

    @Test
    fun `fetching a missing contract is a 404`() {
        testApp(setupData = { setup() }) { _ ->
            val error = assertFailsWith<PosException> { runBlocking { service.get(999_999) } }
            assertEquals(PosErrorCode.RESOURCE_NOT_FOUND, error.code)
        }
    }

    @Test
    fun `instalments come back due date ascending with derived statuses`() {
        testApp(setupData = { setup() }) { _ ->
            val created = runBlocking { service.create(request()) }
            val today = currentTimeStamp().date
            val past = LocalDateTime(today.minus(30, DateTimeUnit.DAY), LocalTime(0, 0))
            val future = LocalDateTime(today.plus(30, DateTimeUnit.DAY), LocalTime(0, 0))
            transaction {
                val contract = Contract.findById(created.id)!!
                // Written out of order on purpose: the read model, not the insert order, decides.
                newInstalment(contract, future)
                newInstalment(contract, past)
            }

            val instalments = runBlocking { service.instalments(created.id) }

            assertEquals(2, instalments.total)
            assertEquals(
                listOf(past.date.toString(), future.date.toString()),
                instalments.items.map { it.dueDate },
                "due date ascending — an unstable order makes the contract strip jump between loads",
            )
            assertEquals(
                listOf(ContractInstalmentStatus.OVERDUE.name, ContractInstalmentStatus.OPEN.name),
                instalments.items.map { it.status },
                "both rows store OPEN; OVERDUE is derived from the due date",
            )
            assertEquals("1250.0000", instalments.items.first().expectedAmount)
            transaction {
                assertTrue(
                    ContractInstalment.all().all { it.status == ContractInstalmentStatus.OPEN.name },
                    "the column itself never holds OVERDUE",
                )
            }
        }
    }

    @Test
    fun `the earliest unsettled instalment is the next due date`() {
        testApp(setupData = { setup() }) { _ ->
            val created = runBlocking { service.create(request()) }
            transaction {
                val contract = Contract.findById(created.id)!!
                newInstalment(contract, LocalDateTime(2026, 9, 1, 0, 0))
                newInstalment(contract, LocalDateTime(2026, 7, 1, 0, 0))
            }

            val contract = runBlocking { service.get(created.id) }

            assertEquals("2026-07-01", contract.nextDueDate)
            assertNull(contract.lastPaymentDate, "nothing has been paid")
        }
    }

    @Test
    fun `a settled instalment is the last payment date and the open one the next due date`() {
        testApp(setupData = { setup() }) { _ ->
            val created = runBlocking { service.create(request()) }
            transaction {
                val contract = Contract.findById(created.id)!!
                newInstalment(contract, LocalDateTime(2026, 6, 1, 0, 0), openAmount = BigDecimal("0.0000"))
                newInstalment(contract, LocalDateTime(2026, 7, 1, 0, 0))
            }

            val listed = runBlocking { service.list(includeInactive = false, search = null) }.items.single()

            assertEquals("2026-07-01", listed.nextDueDate)
            assertEquals("2026-06-01", listed.lastPaymentDate, "the residual is zero, so it derives as PAID")
        }
    }

    @Test
    fun `a contract without instalments reports neither date`() {
        testApp(setupData = { setup() }) { _ ->
            val created = runBlocking { service.create(request()) }

            val contract = runBlocking { service.get(created.id) }

            assertNull(contract.nextDueDate, "no instalment means no date — not an empty string, and not today")
            assertNull(contract.lastPaymentDate)
        }
    }

    /**
     * A written-off instalment is not a payment that is coming: surfacing it as the next due date
     * would promise money that will never arrive.
     */
    @Test
    fun `a waived instalment is not the next due date`() {
        testApp(setupData = { setup() }) { _ ->
            val created = runBlocking { service.create(request()) }
            transaction {
                val contract = Contract.findById(created.id)!!
                newInstalment(
                    contract,
                    LocalDateTime(2026, 6, 1, 0, 0),
                    status = ContractInstalmentStatus.WAIVED.name,
                )
                newInstalment(contract, LocalDateTime(2026, 7, 1, 0, 0))
            }

            val contract = runBlocking { service.get(created.id) }

            assertEquals("2026-07-01", contract.nextDueDate, "the waived June instalment is skipped")
            assertNull(contract.lastPaymentDate, "a write-off is not a payment")
        }
    }

    @Test
    fun `instalments of a missing contract is a 404`() {
        testApp(setupData = { setup() }) { _ ->
            val error = assertFailsWith<PosException> { runBlocking { service.instalments(999_999) } }
            assertEquals(PosErrorCode.RESOURCE_NOT_FOUND, error.code)
        }
    }

    @Test
    fun `a blank name is rejected`() {
        assertRejects(request(name = "   "))
    }

    /**
     * `InstalmentSchedule` already `require`s 1..31, but an `IllegalArgumentException` surfacing
     * from the 02:00 job is a much worse way to learn about a bad contract than a 400 at creation.
     */
    @Test
    fun `a due day outside 1 to 31 is rejected`() {
        assertRejects(request(dueDay = 32))
    }

    @Test
    fun `an unknown interval is rejected`() {
        assertRejects(request(interval = "FORTNIGHTLY"))
    }

    @Test
    fun `an unparseable money string is rejected`() {
        assertRejects(request(expectedAmount = "1.250,00 €"))
    }

    @Test
    fun `an unparseable start date is rejected`() {
        assertRejects(request(startDate = "01.01.2026"))
    }

    private fun assertRejects(request: ContractDto.CreateRequestDto) {
        testApp(setupData = { setup() }) { _ ->
            val error = assertFailsWith<PosException> { runBlocking { service.create(request) } }
            assertEquals(PosErrorCode.INVALID_REQUEST, error.code)
        }
    }

    @Test
    fun `the SEPA mandate id round-trips through the update, and an absent one stays null`() {
        testApp(setupData = { setup() }) { _ ->
            val created = runBlocking { service.create(request()) }
            // Not sent, so not invented: an empty string would look like a recorded mandate and
            // would make signal 3 fire on a direct debit carrying no mandate id at all.
            assertNull(created.sepaMandateId, "an absent identifier must stay null, not become \"\"")

            val updated =
                runBlocking {
                    service.update(created.id, updateOf(created).copy(sepaMandateId = "MNDT-2026-0001"))
                }
            assertEquals("MNDT-2026-0001", updated.sepaMandateId)
            assertEquals("MNDT-2026-0001", runBlocking { service.get(created.id) }.sepaMandateId)

            val cleared =
                runBlocking { service.update(created.id, updateOf(created).copy(sepaMandateId = "   ")) }
            assertNull(cleared.sepaMandateId, "a blank is the same statement as no mandate recorded")
        }
    }

    private fun newInstalment(
        contract: Contract,
        dueDate: LocalDateTime,
        openAmount: BigDecimal? = null,
        status: String = ContractInstalmentStatus.OPEN.name,
    ) = ContractInstalment.new {
        this.contract = contract
        this.dueDate = dueDate
        this.expectedAmount = BigDecimal("1250.0000")
        this.openAmount = openAmount
        this.status = status
    }

    private fun updateOf(
        contract: ContractDto.ResponseDto,
        isActive: Boolean = true,
    ) = ContractDto.UpdateRequestDto(
        name = contract.name,
        interval = contract.interval,
        dueDay = contract.dueDay,
        startDate = contract.startDate,
        expectedAmount = contract.expectedAmount,
        isActive = isActive,
    )
}
