package org.example.project.admin.features.dashboard.sections

import kotlinx.coroutines.test.runTest
import org.example.project.admin.features.dashboard.FakeAllocationApiClient
import org.example.project.admin.features.dashboard.FakeInvoiceListApiClient
import org.example.project.admin.features.dashboard.presentation.sections.OpenWorkViewModel
import org.example.project.core.domain.dto.allocation.AllocationDto
import org.example.project.core.domain.dto.document.InvoiceListDto
import org.example.project.core.network.error.NetworkApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun invoices(counts: Map<String, Long>) = InvoiceListDto.ListResponseDto(items = emptyList(), total = 0, bucketCounts = counts)

private fun queue(total: Long) = AllocationDto.QueueResponseDto(items = emptyList(), total = total)

class OpenWorkViewModelTest {
    @Test
    fun mapsBucketCountsAndQueueTotal() =
        runTest {
            val invoiceApi =
                FakeInvoiceListApiClient {
                    invoices(mapOf("ZU_ERFASSEN" to 7L, "ZU_PRUEFEN" to 3L, "UEBERFAELLIG" to 1L))
                }
            val vm = OpenWorkViewModel(invoiceApi, FakeAllocationApiClient { queue(12L) })
            vm.refresh()
            assertEquals(7L, vm.state.toCapture)
            assertEquals(3L, vm.state.toReview)
            assertEquals(1L, vm.state.overdue)
            assertEquals(12L, vm.state.unassignedPayments)
            assertFalse(vm.state.isEmpty)
        }

    @Test
    fun asksForCountsNotRows() =
        runTest {
            val invoiceApi = FakeInvoiceListApiClient { invoices(emptyMap()) }
            val allocationApi = FakeAllocationApiClient { queue(0L) }
            OpenWorkViewModel(invoiceApi, allocationApi).refresh()
            assertEquals(1, invoiceApi.lastLimit)
            assertEquals(AllocationDto.QueueFilter.NICHT_ZUGEORDNET, allocationApi.lastFilter)
        }

    @Test
    fun allZero_isEmpty() =
        runTest {
            val vm =
                OpenWorkViewModel(
                    FakeInvoiceListApiClient { invoices(emptyMap()) },
                    FakeAllocationApiClient { queue(0L) },
                )
            vm.refresh()
            assertFalse(vm.state.failed)
            assertTrue(vm.state.isEmpty)
        }

    @Test
    fun failedLoad_isFailedNotEmpty() =
        runTest {
            val vm =
                OpenWorkViewModel(
                    FakeInvoiceListApiClient { throw NetworkApiException(RuntimeException("down")) },
                    FakeAllocationApiClient { queue(0L) },
                )
            vm.refresh()
            assertTrue(vm.state.failed)
            assertEquals(0L, vm.state.toCapture)
        }
}
