package org.example.project.admin.features.staff.schedule

import kotlinx.coroutines.test.runTest
import org.example.project.admin.core.presentation.strings.AdminStringKeys
import org.example.project.admin.features.staff.presentation.schedule.DayEditor
import org.example.project.admin.features.staff.presentation.schedule.ScheduleState
import org.example.project.admin.features.staff.presentation.schedule.ScheduleViewModel
import org.example.project.admin.features.staff.presentation.schedule.ShiftDraft
import org.example.project.admin.features.staff.presentation.schedule.ShiftField
import org.example.project.admin.features.staff.presentation.schedule.ShiftPart
import org.example.project.admin.features.staff.presentation.schedule.validateDayEditor
import org.example.project.core.domain.dto.employee.WorkScheduleDto
import org.example.project.core.presentation.strings.keys.CommonKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun weekly(vararg days: WorkScheduleDto.DayScheduleDto) =
    WorkScheduleDto.WeeklyScheduleResponseDto(
        employeeId = "7",
        contractedWeeklyHours = 40.0,
        scheduledWeeklyMinutes = 0,
        days = days.toList(),
    )

class ScheduleViewModelTest {
    @Test
    fun load_populatesData() =
        runTest {
            val fake =
                FakeWorkScheduleApiClient(
                    weekly(
                        WorkScheduleDto.DayScheduleDto(
                            1,
                            listOf(WorkScheduleDto.ShiftDto(startTime = "09:00", endTime = "17:00", breakDuration = 30)),
                        ),
                    ),
                )
            val vm = ScheduleViewModel(fake)
            vm.load("7")
            val data = vm.state as ScheduleState.Data
            assertEquals(1, data.weekly.days.size)
        }

    @Test
    fun saveDay_sendsUpdateAndReplacesWeekly() =
        runTest {
            val fake = FakeWorkScheduleApiClient(weekly())
            val vm = ScheduleViewModel(fake)
            vm.load("7")
            vm.openEditDay(2)
            vm.addShift()
            vm.onShift(0) { it.copy(startTime = "10:00", endTime = "14:00", breakDuration = "0") }
            vm.saveDay()
            assertEquals("7", fake.updatedEmployeeId)
            assertEquals(2, fake.updatedRequest?.dayOfWeek)
            assertEquals(1, fake.updatedRequest?.shifts?.size)
            assertEquals(
                "10:00",
                fake.updatedRequest
                    ?.shifts
                    ?.first()
                    ?.startTime,
            )
            assertNull(vm.dayEditor) // closed on success
        }

    @Test
    fun saveDay_invalidTime_doesNotCallApi() =
        runTest {
            val fake = FakeWorkScheduleApiClient(weekly())
            val vm = ScheduleViewModel(fake)
            vm.load("7")
            vm.openEditDay(3)
            vm.addShift()
            vm.onShift(0) { it.copy(startTime = "9am", endTime = "17:00", breakDuration = "0") }
            vm.saveDay()
            assertNull(fake.updatedRequest) // validation blocked it
            val editor = assertNotNull(vm.dayEditor)
            assertEquals(setOf(ShiftField(0, ShiftPart.START)), editor.fieldErrors.keys, "the refusal is on the start field")
            assertEquals(1, editor.validationAttempt)
            assertNull(editor.error, "a field refusal is not a form-level error")
        }

    @Test
    fun saveDay_emptyTimes_marksBothFieldsRequired_thenSavesOnceFixed() =
        runTest {
            val fake = FakeWorkScheduleApiClient(weekly())
            val vm = ScheduleViewModel(fake)
            vm.load("7")
            vm.openEditDay(3)
            vm.addShift()
            vm.onShift(0) { it.copy(startTime = "", endTime = "", breakDuration = "") }
            vm.saveDay()

            assertNull(fake.updatedRequest)
            val refused = assertNotNull(vm.dayEditor)
            assertEquals(CommonKeys.fieldRequired, refused.fieldErrors[ShiftField(0, ShiftPart.START)])
            assertEquals(CommonKeys.fieldRequired, refused.fieldErrors[ShiftField(0, ShiftPart.END)])
            assertTrue(ShiftField(0, ShiftPart.BREAK) !in refused.fieldErrors, "a blank break means none")

            vm.onShift(0) { it.copy(startTime = "08:00") }
            assertEquals(setOf(ShiftField(0, ShiftPart.END)), assertNotNull(vm.dayEditor).fieldErrors.keys, "editing clears that field only")

            vm.onShift(0) { it.copy(endTime = "12:00") }
            vm.saveDay()
            assertEquals(
                0,
                fake.updatedRequest
                    ?.shifts
                    ?.single()
                    ?.breakDuration,
            )
        }

    @Test
    fun validateDayEditor_rejectsOutOfRangeTimes() {
        val editor = DayEditor(dayOfWeek = 1, shifts = listOf(ShiftDraft(startTime = "24:00", endTime = "12:60", breakDuration = "0")))
        val errors = validateDayEditor(editor)
        assertEquals(AdminStringKeys.ScheduleErrorTimeFormat, errors[ShiftField(0, ShiftPart.START)])
        assertEquals(AdminStringKeys.ScheduleErrorTimeFormat, errors[ShiftField(0, ShiftPart.END)])
        assertTrue(validateDayEditor(editor.copy(shifts = listOf(ShiftDraft("00:00", "23:59", "15")))).isEmpty())
    }

    @Test
    fun validationFlags_doNotMakeTheDayDirty() {
        val opened = DayEditor(dayOfWeek = 1, shifts = listOf(ShiftDraft()))
        val refused = opened.copy(fieldErrors = mapOf(ShiftField(0, ShiftPart.START) to CommonKeys.fieldRequired), validationAttempt = 3)
        assertFalse(refused.isDirtyFrom(opened))
    }

    @Test
    fun removeShift_dropsRow() =
        runTest {
            val fake =
                FakeWorkScheduleApiClient(
                    weekly(
                        WorkScheduleDto.DayScheduleDto(
                            4,
                            listOf(
                                WorkScheduleDto.ShiftDto(startTime = "09:00", endTime = "12:00", breakDuration = 0),
                                WorkScheduleDto.ShiftDto(startTime = "13:00", endTime = "17:00", breakDuration = 0),
                            ),
                        ),
                    ),
                )
            val vm = ScheduleViewModel(fake)
            vm.load("7")
            vm.openEditDay(4)
            assertEquals(2, vm.dayEditor?.shifts?.size)
            vm.removeShift(0)
            assertEquals(1, vm.dayEditor?.shifts?.size)
            assertTrue(
                vm.dayEditor
                    ?.shifts
                    ?.first()
                    ?.startTime == "13:00",
            )
        }
}
