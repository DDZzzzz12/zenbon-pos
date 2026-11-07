package org.example.project.admin.features.settings

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.features.product.FakeProductCategoryApiClient
import org.example.project.core.data.remote.PrintJobHistoryApiClient
import org.example.project.core.data.remote.PrinterDiscoveryApiClient
import org.example.project.core.data.remote.PrinterLocationApiClient
import org.example.project.core.domain.dto.printer.PrinterDto
import org.example.project.features.settings.presentation.components.common.DateRangeOption
import org.example.project.features.settings.presentation.state.PrintJobHistoryState
import org.example.project.features.settings.presentation.viewmodel.PrinterSettingsViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** FO-80 / FO-82: the print-history load carries the selected printer location; the default range is wide. */
@OptIn(ExperimentalCoroutinesApi::class)
class PrinterSettingsViewModelHistoryTest {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class RecordingHistory : PrintJobHistoryApiClient {
        val locationIds = mutableListOf<Long?>()

        override suspend fun getPrintJobHistory(
            startDate: String,
            endDate: String,
            limit: Int,
            status: String?,
            printType: String?,
            locationId: Long?,
        ): List<PrinterDto.PrintJobHistoryDto> {
            locationIds += locationId
            return emptyList()
        }
    }

    private class NoLocations : PrinterLocationApiClient {
        override suspend fun getAllPrinterLocations(includeInactive: Boolean) = emptyList<PrinterDto.PrinterLocationResponseDto>()

        override suspend fun createPrinterLocation(request: PrinterDto.CreatePrinterLocationRequestDto) = throw NotImplementedError()

        override suspend fun updatePrinterLocation(
            id: Long,
            request: PrinterDto.UpdatePrinterLocationRequestDto,
        ) = throw NotImplementedError()

        override suspend fun deactivatePrinterLocation(id: Long) = throw NotImplementedError()

        override suspend fun activatePrinterLocation(id: Long) = throw NotImplementedError()
    }

    private class NoDiscovery : PrinterDiscoveryApiClient {
        override suspend fun discoverPrinters() = throw NotImplementedError()

        override suspend fun getPrinterStatus(uri: String) = throw NotImplementedError()

        override suspend fun getSupportedFormats(uri: String) = throw NotImplementedError()

        override suspend fun printPdf(
            pdfBytes: ByteArray,
            printerLocationId: Long,
        ) = throw NotImplementedError()

        override suspend fun testPrint(printerLocationId: Long) = throw NotImplementedError()
    }

    private fun makeVm(history: RecordingHistory) =
        PrinterSettingsViewModel(
            printerLocationApiClient = NoLocations(),
            productCategoryApiClient = FakeProductCategoryApiClient(),
            ecrConfigurationApiClient = FakeShopConfigApiClient(),
            printJobHistoryApiClient = history,
            printerDiscoveryApiClient = NoDiscovery(),
            shopId = 1L,
        )

    @Test
    fun `history defaults to the last 7 days and no location`() {
        val state = PrintJobHistoryState()
        assertEquals(DateRangeOption.LAST_7_DAYS, state.selectedDateRange)
        assertNull(state.locationId)
    }

    @Test
    fun `selecting a location reloads the history with that location id`() =
        runTest(testDispatcher) {
            val history = RecordingHistory()
            val vm = makeVm(history)
            runCurrent()

            vm.selectHistoryLocation(7L)
            runCurrent()

            assertEquals(7L, vm.uiState.value.printJobHistorySection.locationId)
            assertEquals(7L, history.locationIds.last())
            vm.viewModelScope.cancel()
        }

    @Test
    fun `clearing the location reloads the unfiltered history`() =
        runTest(testDispatcher) {
            val history = RecordingHistory()
            val vm = makeVm(history)
            runCurrent()
            vm.selectHistoryLocation(7L)
            runCurrent()

            vm.selectHistoryLocation(null)
            runCurrent()

            assertNull(vm.uiState.value.printJobHistorySection.locationId)
            assertNull(history.locationIds.last())
            vm.viewModelScope.cancel()
        }

    @Test
    fun `resetting the location clears the filter without loading`() =
        runTest(testDispatcher) {
            val history = RecordingHistory()
            val vm = makeVm(history)
            runCurrent()
            vm.selectHistoryLocation(7L)
            runCurrent()
            val requestsBefore = history.locationIds.size

            vm.resetHistoryLocation()
            runCurrent()

            assertNull(vm.uiState.value.printJobHistorySection.locationId)
            assertEquals(requestsBefore, history.locationIds.size, "a closed sheet must not trigger a request")
            vm.viewModelScope.cancel()
        }
}
