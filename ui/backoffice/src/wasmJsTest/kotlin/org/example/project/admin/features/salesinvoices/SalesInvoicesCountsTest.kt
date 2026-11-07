package org.example.project.admin.features.salesinvoices

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.features.salesinvoices.presentation.SalesInvoiceBucket
import org.example.project.admin.features.salesinvoices.presentation.SalesInvoicesViewModel
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Belegfluss strip's counts: one total per bucket, read alongside the list. */
@OptIn(ExperimentalCoroutinesApi::class)
class SalesInvoicesCountsTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun loadingTheListAlsoFillsTheCountOfEveryBucket() =
        runTest(dispatcher) {
            val vm = SalesInvoicesViewModel(FakeSalesInvoiceApiClient())

            assertTrue(vm.state.bucketCounts.isEmpty(), "unknown until the first answer, drawn as dashes")
            vm.load()
            advanceUntilIdle()

            assertEquals(SalesInvoiceBucket.entries.toSet(), vm.state.bucketCounts.keys)
        }

    @Test
    fun aFailingCountLeavesTheStripUnknown() =
        runTest(dispatcher) {
            val api = FakeSalesInvoiceApiClient()
            api.listThrows = PosApiException(PosErrorCode.INTERNAL_ERROR, 500, "boom", null)
            val vm = SalesInvoicesViewModel(api)

            vm.load()
            advanceUntilIdle()

            assertTrue(vm.state.bucketCounts.isEmpty())
        }
}
