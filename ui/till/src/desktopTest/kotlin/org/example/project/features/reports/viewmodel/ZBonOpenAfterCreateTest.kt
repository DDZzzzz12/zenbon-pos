package org.example.project.features.reports.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.data.remote.BillApiClient
import org.example.project.core.data.remote.EmployeeApiClient
import org.example.project.core.data.remote.PaymentTypeApiClient
import org.example.project.core.data.remote.StatisticsApiClient
import org.example.project.core.domain.dto.report.ZBonDto
import org.example.project.core.network.error.NetworkApiException
import org.example.project.core.presentation.strings.TranslationRegistry
import org.example.project.core.presentation.strings.defaultTranslations
import org.example.project.core.utils.LanguageManager
import org.example.project.features.reports.presentation.viewmodel.ReportsViewModel
import org.example.project.features.reports.presentation.viewmodel.ZBonViewModel
import org.example.project.testutils.fakes.FakeCashBookApiClient
import org.example.project.testutils.fakes.FakeKassenbuchApiClient
import org.example.project.testutils.fakes.FakeZBonApiClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** A client this test does not exercise: every call fails like a transport error. */
private inline fun <reified T : Any> notExercised(): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        throw NetworkApiException(UnsupportedOperationException("${T::class.simpleName}.${method.name}"))
    } as T

/**
 * Creating a Z-Bon on the Berichte screen never prints; the new closing opens on screen instead.
 * An operator who may read the history lands on the "Z-Bon Verlauf" tab with the closing selected;
 * one who may not stays on the daily report and gets the closing in a dialog. A refused closing
 * moves nothing. Each test wires the two view models exactly as `PosReportsScreen` does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ZBonOpenAfterCreateTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var zBonApi: FakeZBonApiClient
    private lateinit var zBonViewModel: ZBonViewModel
    private lateinit var reportsViewModel: ReportsViewModel

    private val created =
        ZBonDto.ZBonResponseDto(
            id = 42L,
            zbonNummer = 7L,
            shopInfoId = 1L,
            shopName = "Test Shop",
            employeeNumber = "EMP001",
            startTime = "2026-03-07T08:00:00",
            endTime = "2026-03-07T22:00:00",
            cancelledAmount = "0.00",
            totalRevenue = "5000.00",
            openingBalance = "100.00",
            expenses = "50.00",
            closingBalance = "5050.00",
            createdAt = "2026-03-07T22:00:00",
        )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        TranslationRegistry.initialize(defaultTranslations())
        zBonApi = FakeZBonApiClient()
        zBonViewModel = ZBonViewModel(zBonApi, FakeKassenbuchApiClient(), LanguageManager(), FakeCashBookApiClient())
        reportsViewModel =
            ReportsViewModel(
                statisticsApiClient = notExercised<StatisticsApiClient>(),
                zBonApiClient = zBonApi,
                employeeApiClient = notExercised<EmployeeApiClient>(),
                billApiClient = notExercised<BillApiClient>(),
                paymentTypeApiClient = notExercised<PaymentTypeApiClient>(),
            )
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** The screen's confirm action, verbatim in shape. */
    private fun closeTheDay(readsHistory: Boolean) {
        zBonViewModel.createZBonWithCount("130.00", opensHistory = readsHistory) {
            reportsViewModel.onZBonCreated(opensHistory = readsHistory)
        }
    }

    @Test
    fun `with history access the new closing opens selected in the history tab`() =
        runTest(testDispatcher) {
            zBonApi.createZBonResult = created

            closeTheDay(readsHistory = true)
            advanceUntilIdle()

            assertEquals(ReportsViewModel.ZBON_HISTORY_TAB, reportsViewModel.uiState.value.selectedTabIndex)
            assertEquals(
                42L,
                zBonViewModel.zBonState.value.selectedZBon
                    ?.id,
            )
            assertNull(zBonViewModel.zBonState.value.createdZBon, "no dialog over the history tab")
            assertEquals(1, zBonApi.getZBonHistoryCallCount, "the list reloads so it carries the new closing")
            assertEquals(0, zBonApi.printZBonCallCount, "creating a Z-Bon must not print")
        }

    @Test
    fun `without history access the closing shows in the dialog and the tab stays put`() =
        runTest(testDispatcher) {
            zBonApi.createZBonResult = created

            closeTheDay(readsHistory = false)
            advanceUntilIdle()

            assertEquals(0, reportsViewModel.uiState.value.selectedTabIndex, "the withheld tab is never selected")
            assertEquals(
                42L,
                zBonViewModel.zBonState.value.createdZBon
                    ?.id,
            )
            assertNull(zBonViewModel.zBonState.value.selectedZBon)
            assertEquals(0, zBonApi.getZBonHistoryCallCount, "the history routes are ZBON_MANAGE")
            assertEquals(0, zBonApi.printZBonCallCount, "creating a Z-Bon must not print")

            zBonViewModel.dismissCreatedZBon()
            assertNull(zBonViewModel.zBonState.value.createdZBon)
        }

    @Test
    fun `a refused closing leaves the tab unchanged and shows its error`() =
        runTest(testDispatcher) {
            zBonApi.createZBonThrows = NetworkApiException(RuntimeException("ZBON_TABLES_OPEN"))

            closeTheDay(readsHistory = true)
            advanceUntilIdle()

            assertEquals(0, reportsViewModel.uiState.value.selectedTabIndex)
            val state = zBonViewModel.zBonState.value
            assertFalse(state.error == null, "the existing error surfaces")
            assertNull(state.selectedZBon)
            assertNull(state.createdZBon)
            assertEquals(0, zBonApi.printZBonCallCount)
        }
}
