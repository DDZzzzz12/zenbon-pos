package org.example.project.admin.features.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.features.settings.presentation.sections.PaymentTypeViewModel
import org.example.project.admin.features.settings.presentation.sections.VatViewModel
import org.example.project.core.domain.dto.setup.VatDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException
import org.example.project.features.settings.presentation.viewmodel.LocationTypeViewModel
import org.example.project.testutils.fakes.FakeLocationTypeApiClient
import org.example.project.testutils.fakes.FakePaymentTypeApiClient
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The payment-type, location-type and VAT-rate dialogs show their save in progress and ignore a second press:
 * [PaymentTypeViewModel] / [LocationTypeViewModel] raise `isSaving` synchronously, so a double click
 * produces one create, not two.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SaveInFlightTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun paymentTypeDoublePressCreatesOnce() =
        runTest(dispatcher) {
            val api = FakePaymentTypeApiClient()
            val vm = PaymentTypeViewModel(api)
            vm.showAddDialog()

            vm.create("Karte", null, null, isCash = false, createsReceivable = false)
            assertTrue(vm.state.isSaving, "saving shows at once")
            vm.create("Karte", null, null, isCash = false, createsReceivable = false)
            advanceUntilIdle()

            assertEquals(1, api.created.size)
            assertFalse(vm.state.isSaving)
            assertFalse(vm.state.showAddDialog, "a successful save closes the dialog")
        }

    @Test
    fun paymentTypeFailedSaveReleasesTheButton() =
        runTest(dispatcher) {
            val api = FakePaymentTypeApiClient()
            api.failure = PosApiException(PosErrorCode.INTERNAL_ERROR, 500, "boom", null)
            val vm = PaymentTypeViewModel(api)
            vm.showAddDialog()

            vm.create("Karte", null, null, isCash = false, createsReceivable = false)
            advanceUntilIdle()

            assertFalse(vm.state.isSaving)
            assertTrue(vm.state.showAddDialog, "the dialog stays open with the operator's input")
            assertNotNull(vm.state.error)
        }

    @Test
    fun locationTypeDoublePressCreatesOnce() =
        runTest(dispatcher) {
            val api = FakeLocationTypeApiClient()
            val vm = LocationTypeViewModel(api)
            vm.showAddLocationTypeDialog()

            vm.addLocationType("Terrasse", "Terrace", "露台", isVirtual = false, isTakeaway = false, sortOrder = 0)
            assertTrue(vm.uiState.value.isSaving, "saving shows at once")
            vm.addLocationType("Terrasse", "Terrace", "露台", isVirtual = false, isTakeaway = false, sortOrder = 0)
            advanceUntilIdle()

            assertEquals(1, api.created.size)
            assertFalse(vm.uiState.value.isSaving)
            assertFalse(vm.uiState.value.showAddDialog)
        }

    @Test
    fun locationTypeFailedSaveReleasesTheButton() =
        runTest(dispatcher) {
            val api = FakeLocationTypeApiClient()
            api.failure = PosApiException(PosErrorCode.INTERNAL_ERROR, 500, "boom", null)
            val vm = LocationTypeViewModel(api)
            vm.showAddLocationTypeDialog()

            vm.addLocationType("Terrasse", "Terrace", "露台", isVirtual = false, isTakeaway = false, sortOrder = 0)
            advanceUntilIdle()

            assertFalse(vm.uiState.value.isSaving)
            assertTrue(vm.uiState.value.showAddDialog)
            assertNotNull(vm.uiState.value.error)
        }

    @Test
    fun vatRateDoublePressCreatesOnce() =
        runTest(dispatcher) {
            val api = CountingVatApiClient()
            val vm = VatViewModel(api)
            vm.showAddDialog()

            vm.create("D", "REDUCED_7", 0.07)
            assertTrue(vm.state.isSaving, "saving shows at once")
            vm.create("D", "REDUCED_7", 0.07)
            advanceUntilIdle()

            assertEquals(1, api.created)
            assertFalse(vm.state.isSaving)
            assertFalse(vm.state.showDialog, "a successful save closes the dialog")
        }

    @Test
    fun vatRateFailedSaveReleasesTheButton() =
        runTest(dispatcher) {
            val api = CountingVatApiClient(failure = PosApiException(PosErrorCode.INTERNAL_ERROR, 500, "boom", null))
            val vm = VatViewModel(api)
            vm.showAddDialog()

            vm.create("D", "REDUCED_7", 0.07)
            advanceUntilIdle()

            assertFalse(vm.state.isSaving)
            assertTrue(vm.state.showDialog, "the dialog stays open with the operator's input")
            assertNotNull(vm.state.error)
        }

    private class CountingVatApiClient(
        private val failure: PosApiException? = null,
    ) : org.example.project.core.data.remote.VatApiClient {
        var created = 0

        override suspend fun getAllVatRates(): List<VatDto.VatResponseDto> = emptyList()

        override suspend fun createVatRate(request: VatDto.CreateVatRequestDto): VatDto.VatResponseDto {
            failure?.let { throw it }
            created++
            return VatDto.VatResponseDto(vatId = request.vatId, vatClass = request.vatClass, vatRate = request.vatRate, vatPercentage = "")
        }

        override suspend fun updateVatRate(
            vatId: String,
            request: VatDto.UpdateVatRequestDto,
        ): VatDto.VatResponseDto = throw NotImplementedError()

        override suspend fun deleteVatRate(vatId: String) = throw NotImplementedError()
    }
}
