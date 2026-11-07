package org.example.project.admin.features.zBon.closing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.features.dashboard.FakeKassenbuchApiClient
import org.example.project.admin.features.dashboard.FakeZBonApiClient
import org.example.project.admin.features.kassenbuch.FakeCashBookApiClient
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.domain.enums.zBon.ClosingSignerProblem
import org.example.project.core.domain.enums.zBon.TipPayoutMethod
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException
import org.example.project.core.presentation.error.UiError
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ZBonClosingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val zBon = FakeZBonApiClient()
    private lateinit var vm: ZBonClosingViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        vm = ZBonClosingViewModel(zBon, FakeKassenbuchApiClient({ emptyList() }, { "0.00" }), FakeCashBookApiClient())
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun openingLoadsReadiness() =
        runTest(dispatcher) {
            zBon.closingReadinessResult = ZBonDto.ClosingReadinessDto(signerName = "Kasse 2")
            vm.open(includeTipPayouts = false)
            advanceUntilIdle()
            assertEquals(
                "Kasse 2",
                vm.state.draft
                    ?.readiness
                    ?.signerName,
            )
        }

    @Test
    fun aPlainClosingWithoutASignerSendsAnEmptyRequest() =
        runTest(dispatcher) {
            zBon.closingReadinessResult = ZBonDto.ClosingReadinessDto(signerProblem = ClosingSignerProblem.NOT_DESIGNATED)
            vm.open(includeTipPayouts = false)
            advanceUntilIdle()
            vm.updateCount("12,00")
            vm.confirm()
            advanceUntilIdle()
            assertEquals(listOf(ZBonDto.CreateZBonRequestDto()), zBon.createZBonRequests)
            assertEquals(zBon.createZBonResult.id, vm.state.created?.id)
        }

    @Test
    fun withoutASignerATipIsSentAsBankPayrollAndNoCount() =
        runTest(dispatcher) {
            zBon.closingReadinessResult = ZBonDto.ClosingReadinessDto(signerProblem = ClosingSignerProblem.INACTIVE, signerName = "Kasse 1")
            zBon.openPeriodTipsResult =
                listOf(ZBonDto.OpenPeriodTipDto("e1", "W1", "Mia", tipTotal = "3.0000", cashTip = "3.0000", cardTip = "0.0000"))
            vm.open(includeTipPayouts = true)
            advanceUntilIdle()
            vm.updateCount("12,00")
            vm.setTipMethod("e1", TipPayoutMethod.CASH)
            vm.confirm()
            advanceUntilIdle()
            assertEquals(
                listOf(
                    ZBonDto.CreateZBonRequestDto(
                        tipPayouts = listOf(ZBonDto.CreateTipPayoutRequestDto("e1", "3.00", TipPayoutMethod.BANK_PAYROLL)),
                    ),
                ),
                zBon.createZBonRequests,
            )
        }

    @Test
    fun theReadinessCurrencyOutlivesTheDraftWithTheCreatedZBon() =
        runTest(dispatcher) {
            zBon.closingReadinessResult = ZBonDto.ClosingReadinessDto(signerName = "Kasse 1", currency = "CHF")
            vm.open(includeTipPayouts = false)
            advanceUntilIdle()
            vm.confirm()
            advanceUntilIdle()
            assertEquals(null, vm.state.draft)
            assertEquals("CHF", vm.state.createdCurrency)
            vm.consumeCreated()
            assertEquals(null, vm.state.createdCurrency)
        }

    @Test
    fun aBlankReadinessCurrencyStaysUnknown() =
        runTest(dispatcher) {
            zBon.closingReadinessResult = ZBonDto.ClosingReadinessDto(signerName = "Kasse 1")
            vm.open(includeTipPayouts = false)
            advanceUntilIdle()
            vm.confirm()
            advanceUntilIdle()
            assertEquals(null, vm.state.createdCurrency)
        }

    @Test
    fun aBlockedClosingSendsNothing() =
        runTest(dispatcher) {
            zBon.closingReadinessResult =
                ZBonDto.ClosingReadinessDto(signerProblem = ClosingSignerProblem.NOT_DESIGNATED, dailyTransferConfigured = true)
            vm.open(includeTipPayouts = false)
            advanceUntilIdle()
            vm.confirm()
            advanceUntilIdle()
            assertTrue(zBon.createZBonRequests.isEmpty())
        }

    @Test
    fun aRefusalIsKeptAsError() =
        runTest(dispatcher) {
            zBon.createZBonThrows = PosApiException(PosErrorCode.ZBON_SIGNER_NOT_DESIGNATED, 409, "no signer", null)
            vm.open(includeTipPayouts = false)
            advanceUntilIdle()
            vm.confirm()
            advanceUntilIdle()
            val error = assertIs<UiError.FromServer>(vm.state.error)
            assertEquals(PosErrorCode.ZBON_SIGNER_NOT_DESIGNATED, error.code)
        }
}
