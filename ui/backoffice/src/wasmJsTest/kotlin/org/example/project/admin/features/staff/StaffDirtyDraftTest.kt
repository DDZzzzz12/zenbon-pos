package org.example.project.admin.features.staff

import kotlinx.coroutines.test.runTest
import org.example.project.admin.features.permissions.FakePermissionGroupApiClient
import org.example.project.admin.features.staff.presentation.CredentialsEditor
import org.example.project.admin.features.staff.presentation.CredentialsMode
import org.example.project.admin.features.staff.presentation.EmployeeEditor
import org.example.project.admin.features.staff.presentation.GroupPicker
import org.example.project.admin.features.staff.presentation.StaffViewModel
import org.example.project.admin.features.staff.presentation.hr.HrEditorError
import org.example.project.admin.features.staff.presentation.hr.HrProfileEditor
import org.example.project.admin.features.staff.presentation.schedule.DayEditor
import org.example.project.admin.features.staff.presentation.schedule.ShiftDraft
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pure "is this form unsaved" checks behind the leave guard of the staff dialogs. A form that
 * was only opened, or edited and put back, must stay clean; the transient save flags never count.
 */
class StaffDirtyDraftTest {
    private val employee = EmployeeEditor(id = "1", username = "alice", role = "CASHIER", notes = "n")

    @Test
    fun `an untouched employee editor is clean`() {
        assertFalse(employee.isDirtyFrom(employee))
    }

    @Test
    fun `no recorded original means clean`() {
        assertFalse(employee.isDirtyFrom(null))
    }

    @Test
    fun `a changed field makes the employee editor dirty and putting it back makes it clean`() {
        val edited = employee.copy(notes = "changed")
        assertTrue(edited.isDirtyFrom(employee))
        assertFalse(edited.copy(notes = "n").isDirtyFrom(employee))
    }

    @Test
    fun `saving and error flags do not make the employee editor dirty`() {
        assertFalse(employee.copy(saving = true, fieldError = "save").isDirtyFrom(employee))
    }

    @Test
    fun `the view model tracks the open editor against how it was opened`() =
        runTest {
            val vm = StaffViewModel(FakeEmployeeApiClient(), FakePermissionGroupApiClient())
            assertFalse(vm.editorDirty)

            vm.openCreate()
            assertFalse(vm.editorDirty)

            vm.onEditorField { it.copy(username = "carol") }
            assertTrue(vm.editorDirty)

            vm.onEditorField { it.copy(username = "") }
            assertFalse(vm.editorDirty)

            vm.onEditorField { it.copy(username = "carol") }
            vm.closeEditor()
            assertFalse(vm.editorDirty)
        }

    @Test
    fun `credentials are dirty when any of the three fields holds text`() {
        val clean = CredentialsEditor(employeeId = "1", mode = CredentialsMode.PASSWORD)
        assertFalse(clean.isDirty)
        assertTrue(clean.copy(newValue = "x").isDirty)
        assertTrue(clean.copy(confirmValue = "x").isDirty)
        assertTrue(clean.copy(managerPassword = "x").isDirty)
        assertFalse(clean.copy(newValue = "  ", saving = true).isDirty)
    }

    @Test
    fun `a group picker is dirty only once another group is chosen`() {
        val picker = GroupPicker(employeeId = "1", selectedCode = "CASHIER")
        assertFalse(picker.isDirty)
        assertTrue(picker.copy(selectedCode = "MANAGER").isDirty)
        assertFalse(picker.copy(selectedCode = "MANAGER").copy(selectedCode = "CASHIER").isDirty)
    }

    @Test
    fun `the HR editor is dirty only when a typed field differs`() {
        val original = HrProfileEditor(employeeId = "1", isCreate = false, firstName = "Ada", lastName = "Lovelace")
        assertFalse(original.isDirtyFrom(original))
        assertFalse(original.copy(saving = true, error = HrEditorError.SAVE_FAILED).isDirtyFrom(original))
        assertTrue(original.copy(city = "Berlin").isDirtyFrom(original))
        assertFalse(original.isDirtyFrom(null))
    }

    @Test
    fun `the day editor is dirty only when the shifts differ`() {
        val original = DayEditor(dayOfWeek = 1, shifts = listOf(ShiftDraft("09:00", "17:00", "30")))
        assertFalse(original.isDirtyFrom(original))
        assertFalse(original.copy(saving = true).isDirtyFrom(original))
        assertTrue(original.copy(shifts = original.shifts + ShiftDraft()).isDirtyFrom(original))
        assertTrue(original.copy(shifts = listOf(ShiftDraft("09:00", "18:00", "30"))).isDirtyFrom(original))
        assertFalse(original.isDirtyFrom(null))
    }
}
