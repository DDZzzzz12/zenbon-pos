package org.example.project.admin.features.invoices

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.admin.features.finance.FakeBookkeepingStartApiClient
import org.example.project.admin.features.invoices.presentation.InvoicesViewModel
import org.example.project.core.domain.dto.document.InvoiceListDto
import org.example.project.core.error.PosErrorCode
import org.example.project.core.network.error.PosApiException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun docRow(id: Long) =
    InvoiceListDto.RowDto(
        bucket = InvoiceListDto.Bucket.ZU_ERFASSEN,
        documentId = id,
        filename = "beleg-$id.pdf",
    )

private fun invoiceRow(
    id: Long,
    documentId: Long? = null,
) = InvoiceListDto.RowDto(
    bucket = InvoiceListDto.Bucket.ZU_PRUEFEN,
    invoiceId = id,
    documentId = documentId,
    supplierName = "Metro AG",
    grossTotal = "238.00",
)

@OptIn(ExperimentalCoroutinesApi::class)
class InvoicesViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `load fills the first bucket and its counts`() =
        runTest(dispatcher) {
            val api = FakeInvoiceListApiClient()
            api.rowsByBucket = mapOf(InvoiceListDto.Bucket.ZU_ERFASSEN to listOf(docRow(1)))
            api.counts = mapOf("ZU_ERFASSEN" to 1L, "ZU_PRUEFEN" to 0L, "FERTIG" to 0L)

            val vm = InvoicesViewModel(api, FakeBookkeepingStartApiClient())
            vm.load()
            advanceUntilIdle()

            assertEquals(InvoiceListDto.Bucket.ZU_ERFASSEN, vm.state.bucket)
            assertEquals(1, vm.state.items.size)
            assertEquals(0L, vm.state.bucketCounts["FERTIG"], "zero counts must survive, not vanish")
            assertFalse(vm.state.isLoading)
            assertNull(vm.state.error)
        }

    @Test
    fun `switching bucket re-requests from the server`() =
        runTest(dispatcher) {
            val api = FakeInvoiceListApiClient()
            api.rowsByBucket =
                mapOf(
                    InvoiceListDto.Bucket.ZU_ERFASSEN to listOf(docRow(1)),
                    InvoiceListDto.Bucket.ZU_PRUEFEN to listOf(invoiceRow(9)),
                )

            val vm = InvoicesViewModel(api, FakeBookkeepingStartApiClient())
            vm.load()
            advanceUntilIdle()
            vm.selectBucket(InvoiceListDto.Bucket.ZU_PRUEFEN)
            advanceUntilIdle()

            assertEquals(
                listOf(InvoiceListDto.Bucket.ZU_ERFASSEN, InvoiceListDto.Bucket.ZU_PRUEFEN),
                api.listCalls,
            )
            val shown = vm.state.items.single()
            assertEquals(9L, shown.invoiceId)
        }

    @Test
    fun `selecting the bucket already shown does not re-request`() =
        runTest(dispatcher) {
            val api = FakeInvoiceListApiClient()
            val vm = InvoicesViewModel(api, FakeBookkeepingStartApiClient())
            vm.load()
            advanceUntilIdle()
            vm.selectBucket(InvoiceListDto.Bucket.ZU_ERFASSEN)
            advanceUntilIdle()

            assertEquals(1, api.listCalls.size)
        }

    @Test
    fun `marking reviewed re-reads so the row leaves the bucket`() =
        runTest(dispatcher) {
            val api = FakeInvoiceListApiClient()
            api.rowsByBucket = mapOf(InvoiceListDto.Bucket.ZU_PRUEFEN to listOf(invoiceRow(9)))

            val vm = InvoicesViewModel(api, FakeBookkeepingStartApiClient())
            vm.load()
            advanceUntilIdle()
            vm.selectBucket(InvoiceListDto.Bucket.ZU_PRUEFEN)
            advanceUntilIdle()

            api.rowsByBucket = emptyMap()
            vm.markReviewed(9)
            advanceUntilIdle()

            assertEquals(listOf(9L), api.reviewed)
            assertTrue(vm.state.items.isEmpty(), "the list must be re-read, not mutated locally")
        }

    @Test
    fun `a failure surfaces a typed error and stops loading`() =
        runTest(dispatcher) {
            val api = FakeInvoiceListApiClient()
            api.failWith = PosApiException(PosErrorCode.INTERNAL_ERROR, 500, "boom", null)

            val vm = InvoicesViewModel(api, FakeBookkeepingStartApiClient())
            vm.load()
            advanceUntilIdle()

            assertNotNull(vm.state.error)
            assertFalse(vm.state.isLoading)
        }

    @Test
    fun `an empty opening bucket hands over to the first bucket that has work`() =
        runTest(dispatcher) {
            val api = FakeInvoiceListApiClient()
            val vm = InvoicesViewModel(api, FakeBookkeepingStartApiClient())
            api.rowsByBucket = mapOf(InvoiceListDto.Bucket.ZU_PRUEFEN to listOf(docRow(1)))
            api.counts = mapOf(InvoiceListDto.Bucket.ZU_PRUEFEN.name to 112L, InvoiceListDto.Bucket.OFFEN.name to 111L)
            vm.load()
            advanceUntilIdle()
            assertEquals(InvoiceListDto.Bucket.ZU_PRUEFEN, vm.state.bucket)
            assertEquals(1, vm.state.items.size)
            // exactly one hand-over: the opening request, then the redirected one
            assertEquals(
                listOf(InvoiceListDto.Bucket.ZU_ERFASSEN, InvoiceListDto.Bucket.ZU_PRUEFEN),
                api.listCalls,
            )
        }

    @Test
    fun `picking an empty bucket is honoured rather than bounced`() =
        runTest(dispatcher) {
            val api = FakeInvoiceListApiClient()
            val vm = InvoicesViewModel(api, FakeBookkeepingStartApiClient())
            api.rowsByBucket = mapOf(InvoiceListDto.Bucket.ZU_PRUEFEN to listOf(docRow(1)))
            api.counts = mapOf(InvoiceListDto.Bucket.ZU_PRUEFEN.name to 112L)
            vm.load()
            advanceUntilIdle()
            api.listCalls.clear()
            // the operator asks for the empty bucket on purpose — the screen must stay there
            vm.selectBucket(InvoiceListDto.Bucket.UEBERZAHLT)
            advanceUntilIdle()
            assertEquals(InvoiceListDto.Bucket.UEBERZAHLT, vm.state.bucket)
            assertTrue(vm.state.items.isEmpty())
            assertEquals(listOf(InvoiceListDto.Bucket.UEBERZAHLT), api.listCalls)
        }

    @Test
    fun `a wholly empty inbox stays on the opening bucket`() =
        runTest(dispatcher) {
            val api = FakeInvoiceListApiClient()
            val vm = InvoicesViewModel(api, FakeBookkeepingStartApiClient())
            api.rowsByBucket = emptyMap()
            api.counts = emptyMap()
            vm.load()
            advanceUntilIdle()
            assertEquals(InvoiceListDto.Bucket.ZU_ERFASSEN, vm.state.bucket)
            assertEquals(listOf(InvoiceListDto.Bucket.ZU_ERFASSEN), api.listCalls)
        }
}
