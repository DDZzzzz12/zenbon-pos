package org.example.project.admin.features.contracts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.contracts.presentation.ContractDetailViewModel
import org.example.project.admin.features.contracts.presentation.ContractsViewModel
import org.example.project.admin.features.contracts.presentation.alertKindKey
import org.example.project.admin.features.contracts.presentation.showsAlertBadge
import org.example.project.admin.features.supplier.FakeSupplierApiClient
import org.example.project.core.domain.dto.contract.ContractAlertDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun alert(
    contractId: Long,
    kind: String = "PAYMENT_MISSING",
    instalmentId: Long = 1,
) = ContractAlertDto.AlertDto(
    kind = kind,
    contractId = contractId,
    contractName = "Miete Ladenlokal",
    instalmentId = instalmentId,
    dueDate = "2026-07-01",
)

/**
 * The alerts on the Verträge list and on one contract.
 *
 * The nothing-not-a-zero rule is asserted through the pure [showsAlertBadge] rather than a
 * composition: a contract with nothing wrong must render **nothing**, not a zero, because a run of
 * noughts reads as noise and the eye stops checking it — which is the whole point of the count.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContractAlertsTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `loading the list also loads the alerts`() =
        runTest(dispatcher) {
            val api = FakeContractApiClient()
            api.contracts = listOf(contract(1))
            api.total = 1
            api.alerts = listOf(alert(1), alert(1, kind = "INVOICE_MISSING", instalmentId = 2))

            val vm = ContractsViewModel(api)
            vm.load()
            advanceUntilIdle()

            assertEquals(1, api.alertCalls)
            assertEquals(2, vm.state.alertCount, "the summary answers whether anything needs me at all")
            assertNull(vm.state.error)
        }

    @Test
    fun `an alerts failure surfaces without blanking the contracts already loaded`() =
        runTest(dispatcher) {
            val api = FakeContractApiClient()
            api.contracts = listOf(contract(1), contract(2, "Strom"))
            api.total = 2
            api.alertsFailWith = PosApiException(PosErrorCode.INTERNAL_ERROR, 500, "boom", null)

            val vm = ContractsViewModel(api)
            vm.load()
            advanceUntilIdle()

            assertNotNull(vm.state.error)
            assertEquals(2, vm.state.items.size, "a table emptied by a failed second request is worse than no summary")
            assertTrue(vm.state.alerts.isEmpty())
        }

    @Test
    fun `a contract with no alerts shows no badge rather than a zero`() {
        assertEquals(false, showsAlertBadge(0))
        assertEquals(true, showsAlertBadge(3))
    }

    @Test
    fun `each alert kind has its own wording`() {
        assertEquals(AdminStringKeys.ContractAlertPaymentMissing, alertKindKey("PAYMENT_MISSING"))
        assertEquals(AdminStringKeys.ContractAlertInvoiceMissing, alertKindKey("INVOICE_MISSING"))
        assertEquals(AdminStringKeys.ContractAlertAmountOffBand, alertKindKey("AMOUNT_OFF_BAND"))
    }

    @Test
    fun `the detail keeps only its own contract's alerts`() =
        runTest(dispatcher) {
            val api = FakeContractApiClient()
            api.contracts = listOf(contract(1), contract(2, "Strom"))
            api.alerts = listOf(alert(1), alert(2, instalmentId = 9))

            val vm = ContractDetailViewModel(api, FakeSupplierApiClient())
            vm.load(2)
            advanceUntilIdle()

            assertEquals(listOf(9L), vm.state.alerts.map { it.instalmentId })
        }

    @Test
    fun `the create form asks for no alerts at all`() =
        runTest(dispatcher) {
            val api = FakeContractApiClient()

            val vm = ContractDetailViewModel(api, FakeSupplierApiClient())
            vm.load(null)
            advanceUntilIdle()

            assertEquals(0, api.alertCalls, "an unsaved contract has no instalments and nothing to complain about")
            assertTrue(vm.state.alerts.isEmpty())
        }
}
