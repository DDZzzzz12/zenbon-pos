package org.example.project.admin.features.dashboard.sections

import kotlinx.coroutines.test.runTest
import org.example.project.admin.features.dashboard.FakeApprovalRequestApiClient
import org.example.project.admin.features.dashboard.presentation.sections.PendingAuthorizationsViewModel
import org.example.project.core.domain.dto.auth.AuthDto
import org.example.project.core.network.error.NetworkApiException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun request(id: String) =
    AuthDto.ApprovalRequestDto(
        requestId = id,
        requestingEmployeeId = "emp-1",
        requestingEmployeeNumber = "04",
        deviceId = "dev-1",
        terminalName = "Kasse 1",
        operation = "CANCEL_ORDER_ITEMS",
        requiredCapabilities = setOf("ORDER_CANCEL"),
        details = emptyMap(),
        timestamp = "2026-08-28T12:00:00",
    )

class PendingAuthorizationsViewModelTest {
    @Test
    fun pendingRequests_populateTheList() =
        runTest {
            val vm = PendingAuthorizationsViewModel(FakeApprovalRequestApiClient { listOf(request("r1")) })
            vm.refresh()
            assertEquals(1, vm.state.requests.size)
            assertFalse(vm.state.isEmpty)
        }

    @Test
    fun noRequests_isEmpty() =
        runTest {
            val vm = PendingAuthorizationsViewModel(FakeApprovalRequestApiClient { emptyList() })
            vm.refresh()
            assertFalse(vm.state.failed)
            assertTrue(vm.state.isEmpty)
        }

    @Test
    fun approve_sendsTheIdAndReloads() =
        runTest {
            val api = FakeApprovalRequestApiClient { listOf(request("r1")) }
            val vm = PendingAuthorizationsViewModel(api)
            vm.refresh()
            val callsBefore = api.pendingCallCount
            vm.approve("r1")
            assertEquals(listOf("r1"), api.approvedIds)
            assertTrue(api.pendingCallCount > callsBefore)
        }

    @Test
    fun deny_sendsTheIdAndClearsTheDialog() =
        runTest {
            val api = FakeApprovalRequestApiClient { listOf(request("r1")) }
            val vm = PendingAuthorizationsViewModel(api)
            vm.refresh()
            vm.startDeny("r1")
            vm.deny("r1", "nicht autorisiert", "Kein Grund angegeben")
            assertEquals(listOf("r1"), api.deniedIds)
            assertEquals(null, vm.state.denyingRequestId)
        }

    @Test
    fun deny_withBlankReason_sendsTheLocalizedDefault() =
        runTest {
            val api = FakeApprovalRequestApiClient { listOf(request("r1")) }
            val vm = PendingAuthorizationsViewModel(api)
            vm.deny("r1", "  ", "Kein Grund angegeben")
            assertEquals(listOf("Kein Grund angegeben"), api.deniedReasons)
        }

    @Test
    fun deny_withReason_sendsItVerbatim() =
        runTest {
            val api = FakeApprovalRequestApiClient { listOf(request("r1")) }
            val vm = PendingAuthorizationsViewModel(api)
            vm.deny("r1", "nicht autorisiert", "Kein Grund angegeben")
            assertEquals(listOf("nicht autorisiert"), api.deniedReasons)
        }

    @Test
    fun failedLoad_isFailedNotEmpty() =
        runTest {
            val vm =
                PendingAuthorizationsViewModel(
                    FakeApprovalRequestApiClient { throw NetworkApiException(RuntimeException("down")) },
                )
            vm.refresh()
            assertTrue(vm.state.failed)
            assertFalse(vm.state.isLoading)
        }
}
