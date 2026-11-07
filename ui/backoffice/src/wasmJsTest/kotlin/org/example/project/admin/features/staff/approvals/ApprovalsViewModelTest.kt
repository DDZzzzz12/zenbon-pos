package org.example.project.admin.features.staff.approvals

import kotlinx.coroutines.test.runTest
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.staff.presentation.approvals.ApprovalsState
import org.example.project.admin.features.staff.presentation.approvals.ApprovalsViewModel
import org.example.project.admin.features.staff.presentation.approvals.RejectField
import org.example.project.admin.features.staff.time.FakeTimeTrackingApiClient
import org.example.project.admin.features.staff.time.pendingApproval
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApprovalsViewModelTest {
    @Test
    fun load_filtersPendingToEmployee() =
        runTest {
            val fake = FakeTimeTrackingApiClient(pending = listOf(pendingApproval("a1", "7"), pendingApproval("a2", "9")))
            val vm = ApprovalsViewModel(fake)
            vm.load("7")
            val data = vm.state as ApprovalsState.Data
            assertEquals(1, data.pending.size)
            assertEquals("a1", data.pending.first().id)
        }

    @Test
    fun approve_callsApi() =
        runTest {
            val fake = FakeTimeTrackingApiClient(pending = listOf(pendingApproval("a1", "7")))
            val vm = ApprovalsViewModel(fake)
            vm.load("7")
            vm.approve("a1")
            assertEquals("a1", fake.approvedId)
        }

    @Test
    fun reject_withReason_callsApi() =
        runTest {
            val fake = FakeTimeTrackingApiClient(pending = listOf(pendingApproval("a1", "7")))
            val vm = ApprovalsViewModel(fake)
            vm.load("7")
            vm.openReject("a1")
            vm.onRejectReason("not enough detail")
            vm.confirmReject()
            assertEquals("a1", fake.rejectedId)
            assertEquals("not enough detail", fake.rejectReason)
            assertNull(vm.rejectDraft) // closed on success
        }

    @Test
    fun reject_blankReason_doesNotCallApi() =
        runTest {
            val fake = FakeTimeTrackingApiClient(pending = listOf(pendingApproval("a1", "7")))
            val vm = ApprovalsViewModel(fake)
            vm.load("7")
            vm.openReject("a1")
            vm.onRejectReason("   ")
            vm.confirmReject()
            assertNull(fake.rejectedId) // validation blocked it
            val draft = assertNotNull(vm.rejectDraft)
            assertEquals(AdminStringKeys.ApprovalsErrorReasonRequired, draft.fieldErrors[RejectField.REASON])
            assertEquals(1, draft.validationAttempt)
            assertNull(draft.error, "a blank reason is a field error, not a form-level one")

            vm.onRejectReason("missing Sunday")
            assertTrue(assertNotNull(vm.rejectDraft).fieldErrors.isEmpty(), "typing clears the field error")
            vm.confirmReject()
            assertEquals("missing Sunday", fake.rejectReason)
        }
}
