package org.example.project.features.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.example.project.core.domain.dto.openinghours.OpeningHoursDto
import org.example.project.features.settings.presentation.state.DayScheduleState
import org.example.project.features.settings.presentation.state.DaySlot
import org.example.project.features.settings.presentation.state.OpeningHoursEvent
import org.example.project.features.settings.presentation.state.hasInvalidSlot
import org.example.project.features.settings.presentation.state.opensBeforeCloses
import org.example.project.features.settings.presentation.state.overrideHoursAreValid
import org.example.project.features.settings.presentation.viewmodel.OpeningHoursViewModel
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** admin19 FAIL-D: a slot opening at or after it closes is flagged and never sent. */
@OptIn(ExperimentalCoroutinesApi::class)
class OpeningHoursSlotValidationTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun slot_opensBeforeCloses_comparesHourMinute() {
        assertTrue(DaySlot("09:00", "22:00").opensBeforeCloses())
        assertTrue(DaySlot("09:00:00", "22:00").opensBeforeCloses())
        assertFalse(DaySlot("16:30", "15:00").opensBeforeCloses())
        assertFalse(DaySlot("12:00", "12:00:00").opensBeforeCloses())
    }

    @Test
    fun closedDay_isIgnored() {
        val schedule = mapOf(0 to DayScheduleState(isClosed = true, slots = listOf(DaySlot("16:30", "15:00"))))
        assertFalse(schedule.hasInvalidSlot())
        assertTrue(mapOf(0 to DayScheduleState(slots = listOf(DaySlot("16:30", "15:00")))).hasInvalidSlot())
    }

    @Test
    fun save_withInvertedSlot_isNotSent() =
        runTest(dispatcher) {
            val api = RecordingOpeningHoursApi()
            val vm = OpeningHoursViewModel(api)
            vm.onEvent(OpeningHoursEvent.UpdateSlot(dayOfWeek = 4, index = 0, openTime = "16:30", closeTime = "15:00"))
            vm.onEvent(OpeningHoursEvent.SaveSchedule)
            advanceUntilIdle()
            assertNull(api.bulk)
            assertFalse(vm.uiState.value.isSaving)
        }

    @Test
    fun save_withValidSlots_isSent() =
        runTest(dispatcher) {
            val api = RecordingOpeningHoursApi()
            val vm = OpeningHoursViewModel(api)
            vm.onEvent(OpeningHoursEvent.UpdateSlot(dayOfWeek = 4, index = 0, openTime = "11:00", closeTime = "15:00"))
            vm.onEvent(OpeningHoursEvent.SaveSchedule)
            advanceUntilIdle()
            val sent = assertNotNull(api.bulk)
            assertEquals(7, sent.days.size)
        }

    // admin20 NOTE-E: an inverted override reached the server, whose 400 surfaced as raw English.
    @Test
    fun override_hoursRule_matchesTheWeeklyRule() {
        assertTrue(overrideHoursAreValid(isClosed = false, openTime = "09:00", closeTime = "22:00"))
        assertFalse(overrideHoursAreValid(isClosed = false, openTime = "23:00", closeTime = "22:00"))
        assertFalse(overrideHoursAreValid(isClosed = false, openTime = null, closeTime = "22:00"))
        assertTrue(overrideHoursAreValid(isClosed = true, openTime = "23:00", closeTime = "22:00"), "a closed day needs no times")
    }

    @Test
    fun createOverride_withInvertedHours_isNotSent() =
        runTest(dispatcher) {
            val api = RecordingOpeningHoursApi()
            val vm = OpeningHoursViewModel(api)
            val inverted = OpeningHoursDto.CreateOverrideRequestDto(overrideDate = "2026-10-24", openTime = "23:00", closeTime = "22:00")
            vm.onEvent(OpeningHoursEvent.CreateOverride(inverted))
            advanceUntilIdle()
            assertNull(api.createdOverride)

            vm.onEvent(OpeningHoursEvent.CreateOverride(inverted.copy(openTime = "10:00")))
            advanceUntilIdle()
            assertEquals("10:00", api.createdOverride?.openTime)
        }
}
