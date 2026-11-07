package org.example.project.admin.features.staff.time

import kotlinx.coroutines.test.runTest
import org.example.project.admin.features.staff.presentation.time.TimeEntriesState
import org.example.project.admin.features.staff.presentation.time.TimeEntriesViewModel
import org.example.project.core.presentation.error.UiError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimeEntriesViewModelTest {
    @Test
    fun load_populatesEntriesAndOverview() =
        runTest {
            val fake = FakeTimeTrackingApiClient(entries = listOf(summary("e1", "2026-07-03")))
            val vm = TimeEntriesViewModel(fake)
            vm.load("7", 2026, 7)
            val data = vm.state as TimeEntriesState.Data
            assertEquals(1, data.entries.size)
            assertEquals(2026, data.year)
            assertEquals(7, data.month)
            assertEquals("7", fake.queriedEmployeeId)
        }

    @Test
    fun prevMonth_rollsOverAndReloads() =
        runTest {
            val fake = FakeTimeTrackingApiClient()
            val vm = TimeEntriesViewModel(fake)
            vm.load("7", 2026, 1)
            vm.prevMonth()
            val data = vm.state as TimeEntriesState.Data
            assertEquals(2025, data.year)
            assertEquals(12, data.month)
        }

    @Test
    fun createEntry_sendsManualRequest() =
        runTest {
            val fake = FakeTimeTrackingApiClient()
            val vm = TimeEntriesViewModel(fake)
            vm.load("7", 2026, 7)
            vm.openCreate("2026-07-10")
            vm.onEntryField { it.copy(startTime = "09:00", endTime = "17:00", breakMinutes = "30") }
            vm.saveEntry()
            assertEquals("7", fake.createdRequest?.employeeId)
            assertEquals("2026-07-10", fake.createdRequest?.date)
            assertEquals("09:00", fake.createdRequest?.startTime)
            assertEquals(30, fake.createdRequest?.breakMinutes)
            assertNull(vm.entryEditor)
        }

    @Test
    fun editEntry_sendsUpdateRequest() =
        runTest {
            val fake = FakeTimeTrackingApiClient(entries = listOf(summary("e1", "2026-07-03")))
            val vm = TimeEntriesViewModel(fake)
            vm.load("7", 2026, 7)
            vm.openEdit((vm.state as TimeEntriesState.Data).entries.first())
            vm.onEntryField { it.copy(endTime = "18:30", comment = "late") }
            vm.saveEntry()
            assertEquals("e1", fake.updatedId)
            assertEquals("18:30", fake.updatedRequest?.endTime)
            assertEquals("late", fake.updatedRequest?.comment)
            assertNull(vm.entryEditor) // closed on success
        }

    @Test
    fun saveEntry_invalidTime_doesNotCallApi() =
        runTest {
            val fake = FakeTimeTrackingApiClient()
            val vm = TimeEntriesViewModel(fake)
            vm.load("7", 2026, 7)
            vm.openCreate("2026-07-10")
            vm.onEntryField { it.copy(startTime = "9am", endTime = "17:00") }
            vm.saveEntry()
            assertNull(fake.createdRequest)
            assertTrue(vm.entryEditor?.error is UiError.Unknown)
        }

    @Test
    fun deleteEntry_callsApiAndReloads() =
        runTest {
            val fake = FakeTimeTrackingApiClient(entries = listOf(summary("e1", "2026-07-03")))
            val vm = TimeEntriesViewModel(fake)
            vm.load("7", 2026, 7)
            vm.deleteEntry("e1")
            assertEquals("e1", fake.deletedId)
        }
}
